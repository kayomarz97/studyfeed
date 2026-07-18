package dev.kayomarz.studyfeed.pack

/**
 * The schema-version gate (BUILD_PROMPT §8 A1, spec/SPEC.md §4).
 *
 * **Why this exists at all:** a pack built against a bumped schema is precisely what a naive loader
 * corrupts — it opens fine, then silently mis-reads a renamed column. This gate is the difference
 * between "plays future packs" being a guarantee and a hope. It must run **before anything else is
 * opened**, so a pack we don't understand never reaches a real query.
 *
 * This file is deliberately pure Kotlin — no Android, no SQLite. The *decision* is testable on the JVM
 * today; reading the numbers out of a DB is a thin adapter around it (plan D7). That split is why the
 * gate's logic can be proven without a device.
 */
object SchemaVersion {

    /**
     * The single source of truth for what this binary can play. **One constant** (§8 A1: "Pin the
     * supported version(s) in one constant").
     *
     * Bumping this is a deliberate act: it asserts the app has been updated to read that schema. Never
     * bump it to make a pack load.
     */
    const val SUPPORTED_MAX = 1

    /**
     * Parse a schema version that arrives in one of **three different representations**.
     *
     * This is a real trap, verified against the shipped pack on 2026-07-17:
     *  - `pack_manifest.schema_version` → SQLite **TEXT**, `"1"`
     *  - `PRAGMA user_version`          → SQLite **INTEGER**, `1`
     *  - `manifest.json.schema_version` → **JSON string**, `"1"`
     *
     * A naive `==` across those refuses a perfectly valid pack. Everything is parsed to Int at the edge
     * so the rest of the code compares numbers to numbers.
     *
     * @return the parsed version, or null if it is absent/blank/not an integer — which the caller must
     *   treat as a malformed pack, never as "probably fine".
     */
    fun parse(raw: String?): Int? = raw?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    /**
     * Decide whether a pack may be opened.
     *
     * spec/SPEC.md §4 is more precise than §8 A1's shorthand and is the contract we implement: the app
     * "declares the max `schema_version` it supports and **refuses to import a newer one** rather than
     * crash; a compatible **older** pack still loads."
     *
     * @param packManifestVersion `pack_manifest.schema_version`, already parsed (authoritative — §3).
     * @param userVersion `PRAGMA user_version`, already parsed (mirrors the above — §4).
     */
    fun evaluate(packManifestVersion: Int?, userVersion: Int?): GateResult = when {
        // Absent/unparseable => not a pack we can reason about. Refuse loudly rather than guess.
        packManifestVersion == null || userVersion == null ->
            GateResult.Malformed(
                "This file isn't a readable StudyFeed pack: its schema version is missing or unreadable " +
                    "(pack_manifest=${packManifestVersion ?: "absent"}, user_version=${userVersion ?: "absent"})."
            )

        // SPEC.md §4: user_version MIRRORS pack_manifest.schema_version. Disagreement means the pack was
        // built wrong or tampered with — refuse rather than pick a side, because either choice could be
        // the wrong one and both mis-read the data.
        packManifestVersion != userVersion ->
            GateResult.Malformed(
                "This pack contradicts itself: it declares schema version $packManifestVersion but the " +
                    "database is stamped $userVersion. It may be corrupt or incorrectly built."
            )

        // The case this gate exists for. Name BOTH versions — "unsupported pack" tells the user nothing
        // and tells a future maintainer even less (§8 A1: "naming both versions").
        packManifestVersion > SUPPORTED_MAX ->
            GateResult.TooNew(
                packVersion = packManifestVersion,
                supportedMax = SUPPORTED_MAX,
                message = "This pack needs a newer version of the app. The pack uses schema version " +
                    "$packManifestVersion; this app supports up to $SUPPORTED_MAX. Update the app, then " +
                    "import it again — your existing progress is untouched.",
            )

        // Older-but-compatible loads (SPEC.md §4). Not an error, not a warning.
        else -> GateResult.Ok(packManifestVersion)
    }

    /** Outcome of the gate. A sealed type so a new case cannot be silently ignored at a call site. */
    sealed interface GateResult {
        data class Ok(val schemaVersion: Int) : GateResult

        /** Pack is from the future. Refused, never crashed — and the message says so in the user's terms. */
        data class TooNew(val packVersion: Int, val supportedMax: Int, val message: String) : GateResult

        /** Self-contradictory or unreadable. Refused for the same reason: never half-load. */
        data class Malformed(val message: String) : GateResult
    }
}
