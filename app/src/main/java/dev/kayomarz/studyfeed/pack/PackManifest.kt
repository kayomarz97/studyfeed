package dev.kayomarz.studyfeed.pack

/**
 * The pack's self-description — everything the app needs to configure itself for an exam it has never
 * heard of (BUILD_PROMPT §8 A1, spec/SPEC.md §3).
 *
 * This type is the seam. The app knows THIS SHAPE and nothing else about any exam: the app-bar label,
 * the subject chips and the tag chips are all read from here at runtime. That is what lets a future
 * pack (MRCP Part 2, USMLE, another subject entirely) play on the same binary with no code change
 * (§0 `multi_pack`).
 *
 * So: **nothing exam-specific may ever be hardcoded against this type.** No `if (examCode == "...")`,
 * no default that assumes a particular exam, no test fixture promoted into production defaults. If you
 * find yourself typing an exam's name in `app/`, the design has already failed.
 *
 * Source of truth is the `pack_manifest` table (spec/SPEC.md §3: "the app may read either; the DB row
 * is authoritative"). `manifest.json` is a redundant mirror, useful before the DB is opened.
 */
data class PackManifest(
    /** DB shape. Mirrors `PRAGMA user_version`. See [SchemaVersion] — this is NOT a plain Int compare. */
    val schemaVersion: Int,
    /** Content build (`<exam>.<major>.<minor>`), independent of [schemaVersion]. Kept as text: it is a
     *  3-part version string, not a number, and the app only ever displays/compares it for equality. */
    val packVersion: String,
    /** Stable machine id for the exam — namespaces the progress DB. NEVER derived from a filename (D3). */
    val examCode: String,
    /** Human label for the app bar. The ONLY place an exam's name may come from. */
    val examName: String,
    val builtAt: String,
    /**
     * ⚠ **This is NOT `manifest.json`'s `checksum`.** They share a name and hold different values.
     *
     * This one (`pack_manifest.checksum`) is a **content checksum over sorted stable_keys** — the §5j
     * reproducibility anchor, proving a rebuild didn't orphan progress. `manifest.json.checksum` is the
     * **sha256 of the .db file**, for transfer integrity.
     *
     * Comparing the two fails on a perfectly good pack. Verified on the real pack 2026-07-17:
     * `pack_manifest.checksum` = 8551d0c4…, `manifest.json.checksum` = 8005f7d7…. Import verifies the
     * FILE hash, so it reads [PackFileManifest.checksum] — never this.
     */
    val contentChecksum: String,
    val featureFlags: Map<String, Boolean>,
    val counts: Map<String, Long>,
)

/**
 * The `manifest.json` mirror that sits beside the DB — readable *before* SQLite opens anything.
 *
 * Its whole job at import is [checksum]: the sha256 of the .db file, which catches a truncated or
 * corrupted transfer before a single query runs. A half-copied 141 MB DB otherwise surfaces much later
 * as inexplicable corruption.
 */
data class PackFileManifest(
    val schemaVersion: Int,
    val packVersion: String,
    val examCode: String,
    val examName: String,
    /** sha256 of the `.db` FILE. See the warning on [PackManifest.contentChecksum] — different thing. */
    val checksum: String,
)

/**
 * Which asset types a loaded pack actually offers.
 *
 * **Never hardcode this** (BUILD_PROMPT §8 A1, PATCH 03; plan D5). The rule that looks like a detail and
 * is actually the whole `multi_pack` contract: an app that assumes blobs exist because MRCP Part 1 had
 * them is an app that cannot swap its database — and it fails *silently*, which is the only way that
 * contract ever fails. Same class of bug as a hardcoded "MRCP" string, harder to see.
 *
 * Resolution order (spec §8 A1):
 *  1. **actual rows are authoritative** — `SELECT EXISTS(SELECT 1 FROM <table>)` decides;
 *  2. `counts_json` / `feature_flags_json` are the pack's own declaration — a fast hint AND a cross-check;
 *  3. disagreement ⇒ the pack is malformed: trust the rows, surface it, believe neither blindly.
 *
 * Historical note so nobody "restores" the old behaviour: the spec used to say text_blobs / blob_quizzes
 * / interlink_quizzes / figures "ship empty (trimmed at C11)". **C11b undid that trim.** The shipped pack
 * has blobs 2,997 · interlink 2,998 · figures 1,121 — use them. But a *future* pack may legitimately ship
 * without any of them, which is exactly why this is resolved per pack and never assumed.
 */
data class PackCapabilities(
    val hasFlashcards: Boolean,
    val hasReels: Boolean,
    val hasBlobs: Boolean,
    val hasInterlink: Boolean,
    val hasFigures: Boolean,
    val hasDossiers: Boolean,
    val hasConceptMap: Boolean,
    val hasGeneratedMcqs: Boolean,
    /**
     * Where the pack's own declaration disagreed with its actual rows. Empty on a well-formed pack.
     *
     * Non-empty means malformed — surfaced rather than swallowed, because a flag claiming data that
     * isn't there is exactly what produces a dead UI element with no error.
     */
    val declarationMismatches: List<String> = emptyList(),
)
