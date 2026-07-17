# A1 — Pack loading (generic): SAF import, schema gate, manifest-driven UI, progress DB, §11e export/import

**Date:** 2026-07-17 · **Branch:** `dev` · **Stage:** §8 A1 (A0 ✅ done) · **Status:** PLAN — no app code written yet

> Read from disk before writing this: `BUILD_PROMPT.md` §8 A1 + §11e (PATCH 02 rewrote them),
> `spec/SPEC.md` (the contract), `spec/schema.sql`, `CLAUDE.md`, `.claude/INDEX.md`, and the
> ⛔ standing orders at the top of `JOURNAL.md`. Pack facts below were read from the **shipped DB**,
> never from a doc.

---

## 1. What A1 is, in one breath

**Plain:** Teach the app to swallow a pack — let the user pick the pack folder, check it's a pack the
app understands, then configure itself entirely from what's inside it (exam name, subjects, tags), and
keep the user's progress in a separate file that a re-import can never wipe. Plus a way to back that
progress up and restore it.

**Technical:** SAF directory import → schema-version gate → copy pack into app-private storage →
`ATTACH` the read-only content DB beside a per-`exam_code` read-write progress DB → build all UI config
from `pack_manifest`/`subjects`/`tag_defs` at runtime → pack registry + "switch exam" screen → §11e
progress export/import as a ZIP.

**The one rule that governs everything here:** no exam-specific string may enter the app. The word
"MRCP" must not appear in any resource, constant or layout. A0 is verifiably clean (the only "MRCP"
under `app/` is a comment in `strings.xml` forbidding it). A1 is where that guarantee is easiest to
break, because A1 is where exam data first enters.

---

## 2. Ground truth — the pack, as verified on this laptop today

Imported 2026-07-17, sha256 gate passed (`8005f7d7…` matches `manifest.json.checksum`).
`compiler/dist/` = `pack_MRCP_PART1.db` (141,463,552 B) + `manifest.json` (3,602 B) + `figures/` (1,121 PNGs).

| Fact | Value | Why A1 cares |
|---|---|---|
| `PRAGMA user_version` | `1` (INTEGER) | half of the schema gate |
| `pack_manifest.schema_version` | `"1"` (**TEXT**) | other half — **different type** |
| `manifest.json.schema_version` | `"1"` (JSON string) | third source of the same fact |
| `pack_manifest` rows | exactly 1 | gate must handle 0 / >1 rows |
| `exam_code` / `exam_name` | `MRCP_PART1` / `MRCP(UK) Part 1` | **only** source of the app-bar label |
| `subjects` / `tag_defs` | 16 / 5 | filter chips + tag chips, runtime-built |
| `feed_items` | **32,957** | the playable deck |
| `figures.path` | `figures/00107e5661f760f4.png` (**relative**) | directory layout is load-bearing |
| Empty tables | `topics`, `dup_clusters`, `cluster_members` | see §3.1 — **the spec is wrong about this** |

Playable composition: mcqs 14,455 (12,455 curated + 2,000 generated) · flashcards 9,507 · reels 3,000 ·
blob_quizzes 2,997 · interlink_quizzes 2,998 = **32,957**. Also atoms 86,698 · concepts 37,795 ·
concept_edges 67,795 · topic_pages 8,194 · figures 1,121 · sources 99 · 147,142 stable_keys.

---

## 3. ⛔ Settle BEFORE any code — contradictions and decisions

> Standing order #1: *"If the spec and the repo disagree, surface it and ask; never silently
> reconcile."* Two of these are that. The rest are architecture choices I should not make alone.

### 3.1 ✅ RESOLVED — PATCH 03 applied 2026-07-17 (user-authorised)

**Was:** §8 A1 claimed five tables ship empty; four are populated. **Now:** corrected in place at all
four sites (§7 "Known gaps", §7's stat block, §8 A1's rule, the "work with other databases" section) +
**PATCH 03** appended as the audit trail; recorded in `JOURNAL.md`, `CLAUDE.md` and `.claude/INDEX.md`
so a fresh session cannot be misled again.

| Table | Spec claimed | Actually ships |
|---|---|---|
| `text_blobs` | empty | **2,997** |
| `blob_quizzes` | empty | **2,997** |
| `interlink_quizzes` | empty | **2,998** |
| `figures` | empty | **1,121** |
| `dup_clusters` | empty | 0 ✅ (also empty: `topics`, `cluster_members`) |

**PATCH 02's "⚠ this bites A2" warning is RETIRED** — A2's blob and image cards have real data.

**The user's directive resolved (this is the governing principle for A1 — see §3.1a).**
*"Update it in such a way that I can always swap the database for the next exam, but now since c11b is
completed and I have the data use it."* Those conflict only if implemented naively:
**"use the data" is a fact about THIS pack; "never assume the data" is the rule for EVERY pack.**

### 3.1a THE GOVERNING PRINCIPLE — capability is detected per pack, at runtime 🟢

**Plain:** the app should never "know" that blobs exist. It should *look*, every time it opens a pack.
Today's pack is full, so everything lights up. A leaner pack next year quietly offers less. Same app.

**Technical:** resolution order, applied per import and per pack open —

1. **Actual rows are authoritative.** `SELECT EXISTS(SELECT 1 FROM <table>)` decides whether a card type
   is offered. Nothing else overrides this.
2. **`counts_json` + `feature_flags_json` are the pack's own declaration** — a fast hint (avoids counting
   32,957 rows to draw a chip) *and* a cross-check.
3. **Disagreement = malformed pack.** Flag says `has_blobs`, table empty ⇒ trust the rows, surface it.
   Do not silently believe either side.

**Why this IS the swappability contract, not an implementation detail:** an app that hardcodes "blobs
exist" because MRCP Part 1 had them is an app that cannot swap its database. That is §0 `multi_pack`
failing **silently** — the only way it ever fails. It is the exact same class of bug as a hardcoded
"MRCP" string, and it is harder to spot because nothing looks wrong until the second pack arrives.

**Acceptance must test BOTH directions** (this is now a hard requirement, §5):
- today's full pack → every card type offered;
- a **synthetic trimmed pack** → those types absent, no dead UI, no crash;
- **same binary, no code change.**

⚠ **A1 must not use today's pack as its only fixture.** A full pack cannot prove graceful degradation —
it has nothing missing to degrade from. Building the trimmed fixture is part of A1, not a nicety.
*(This does not revive the declined "fixture pack" proposal — standing order #2. That was a plan to build
a synthetic pack **to start A2 early because no real pack existed**. This is a test fixture for a
degradation path, built alongside a real pack that A1 loads for real.)*

### 3.2 SPEC-vs-REPO: the pack directory layout doesn't match the spec 🟡

`spec/SPEC.md` §1 specifies:

```
pack_<EXAM_CODE>/            <- a named parent directory
├── pack_<EXAM_CODE>.db
├── manifest.json
└── figures/
```

What the compiler actually emits is those three **directly inside `compiler/dist/`** — there is no
`pack_MRCP_PART1/` wrapper. It matters because SAF import means "user picks a directory", and the app
must decide what it accepts.

**✅ DECIDED 2026-07-17 — judge the directory by its contents, never its name.** User's call. Accept any
picked directory containing exactly one `pack_*.db` + `manifest.json` (+ optional `figures/`); derive
`exam_code` from **`pack_manifest`**, never from a filename. Filename-derived identity is precisely the
**"same filename ≠ same file"** trap already in the mistakes ledger from C1 — that mistake cost a
misclassification of an entire drop, and it is the same shape of error here.
**Follow-up owed:** add a line to `spec/SPEC.md` §1 saying its tree diagram is **illustrative, not
normative** — the compiler emits the three files directly into `dist/` with no `pack_<EXAM_CODE>/`
wrapper, and the app does not require one. (Do this when A1 lands, so the contract and the code agree in
the same commit — global rule 6.)

### 3.3 TRAP: there are two different `checksum` fields and they hold different values 🔴

Not a contradiction — a design that will bite whoever writes the gate:

```
manifest.json  "checksum": 8005f7d76a686764…   = sha256 of the .db FILE
pack_manifest.checksum:     8551d0c42f0f2764…   = content-checksum over sorted stable_keys
```

Same name, different meaning, both live in the same pack. `manifest.json`'s is a transfer-integrity
check; `pack_manifest`'s is the reproducibility anchor (§5j). **A1 must never compare one to the other.**
Verifying the file hash on import is worth doing (it is what caught nothing today but would catch a
truncated `adb push`) — but it must read `manifest.json`, not the DB row. Documenting here because this
is a ~30-minute debugging trap disguised as a one-line check.

### 3.4 ✅ DECIDED 2026-07-17 — **Option A: Room for progress only; raw SQLite for the pack**

> **User's call.** Room owns `progress_<EXAM>.db` (typed DAOs, verified schema, real migrations — it is
> the only DB whose shape will evolve). The pack is opened read-only via
> `SQLiteDatabase.openDatabase(…, OPEN_READONLY)` and `ATTACH`-ed for cross-DB joins.
> **Consequences accepted:** two access idioms in one codebase; cross-DB joins are hand-written SQL.
> **Versions — RESEARCHED 2026-07-17, not guessed** (full citations: PLAYBOOK "Android app A1
> dependencies"): **Room 2.8.4** · **KSP 2.3.10** (pairs with Kotlin 2.4.10). **KSP2 is mandatory, not a
> choice** — KSP1's own README rules out Kotlin ≥2.3.0 and AGP ≥9.0, and **kapt is a hard error** under
> AGP 9's built-in Kotlin. KSP 2.3.10's release notes explicitly fix "R-class resolution in KSP when AGP
> 9 built-in Kotlin is enabled" — the closest thing to official confirmation our combination works.
> **Do NOT add `room-ktx`** — merged into `room-runtime` at 2.7.0; adding it cargo-cults a dead artifact.
> **Guard note:** Room is not a networking dep and must not trip `CheckDependencyDenylistTask`; if it
> false-positives, the guard is right and the dep list is wrong — investigate, never disable the guard.

<details><summary>Original options as presented (kept as the record of the decision)</summary>

`SPEC.md` §2 requires the app to **`ATTACH` the read-only content DB beside the read-write progress DB
and join across them**. `CLAUDE.md` names the stack "Room + FSRS-6". **These fight each other:** Room
models one database, generates compile-time-verified DAOs against its own schema, and has no first-class
`ATTACH` support. Cross-DB joins would all be `@RawQuery` with no compile-time verification — i.e. paying
Room's full cost for none of its main benefit on the read path.

Three honest options (my recommendation is A):

- **A. Room for progress only; raw SQLite for the pack.** Room owns `progress_<EXAM>.db` (its schema,
  its migrations, its DAOs, typed and verified). The pack is opened read-only via
  `SQLiteDatabase.openDatabase(…, OPEN_READONLY)` and `ATTACH`-ed for the joins. *Plain:* use the nice
  tool for the file we own and change; use the plain tool for the file we only ever read.
  *Cost:* two access styles in one codebase. *Benefit:* neither tool is used against its grain.
- **B. Room everywhere, `ATTACH` via a callback + `@RawQuery`.** One idiom, but every pack read is an
  unverified raw string and Room's schema validation must be disabled for the attached DB. Room fights
  you at every join.
- **C. No Room at all; raw SQLite both sides.** Simplest mental model, fewest deps, and hand-rolls
  migrations + mapping that Room gives free — on the one DB whose schema will actually evolve.

</details>

**Chosen: A.**

### 3.5 DECISION: importing means copying ~370 MB. How should that feel? 🟡

**Plain:** SQLite can't read a file the user merely pointed at through Android's file picker — it needs a
real file it owns. So importing physically copies the pack (135 MB DB + 235 MB of images) into the app's
own storage. That takes time and doubles the disk used until the user deletes the original.

**Technical:** SAF yields `content://` URIs; `SQLiteDatabase.openDatabase` requires a filesystem path.
No supported way around it — so `ACTION_OPEN_DOCUMENT_TREE` → stream `pack_*.db` + `manifest.json` +
1,121 figure files into `filesDir`/`getExternalFilesDir`. Consequences to decide:

- **Progress UI + cancellation:** a multi-minute copy with no feedback reads as a hang. Needs progress
  and a cancel that cleans up a partial import (import to a temp dir, atomically rename on success —
  never leave a half-pack that looks importable).
- **This must NOT become background work.** §5/§12 and A0's build-enforced guard forbid services and
  WorkManager. The copy runs in-app, on a coroutine, and dies if the user leaves. **The guard will fail
  the build if I reach for WorkManager** — that is the guard working, not an obstacle to route around.
- **Figures: copy all 1,121 up front, or lazily?** Up-front is simple and matches "offline, always".
  Recommend up-front.
- **Storage check first:** refuse politely with a real number if free space < pack size × 1.1.

**✅ DECIDED 2026-07-17 — `filesDir` (app-private).** User's call. Up-front full copy, with progress +
cancel, into `getFilesDir()`. **Why it is the right fit beyond preference:** the pack is derived from
**copyrighted** sources, and `filesDir` is the only option not readable by other apps — the same instinct
that keeps `compiler/dist/` out of git. **Consequences accepted:** the pack can't be inspected or
hand-swapped without `adb`, and it counts against app storage in Settings. **Consequence that needs
handling:** `allowBackup=false` + `filesDir` means an uninstall takes the pack with it — a 370 MB
re-import, not a data loss (the pack is reproducible; progress is not — that's what §11e protects).

### 3.7 ✅ DECIDED 2026-07-17 — the ATTACH problem, and how the cross-DB join actually works 🔴

**Found by `docs-researcher`, not by assumption — this would have been discovered mid-build otherwise.**
`SPEC.md` §2's "ATTACH and join across" collides with the stack we chose:

- **SQLite's own docs:** `ATTACH` is scoped to **one connection**. Room **pools** connections ⇒ an
  ATTACH on one pooled connection is **invisible** to a query served by another.
- **Android's `SQLiteDatabase.enableWriteAheadLogging()` javadoc, verbatim:** *"If the database has any
  attached databases, then execution of queries in parallel is **not** possible."* Room enables WAL by
  default.
- **Documented gap, flagged not papered over:** no official Room doc addresses Room + ATTACH at all.

**Decision (user's call): a dedicated, non-pooled, READ-ONLY connection serves every cross-DB read.**

- One `SQLiteDatabase` opened on `progress_<EXAM>.db`, with the pack `ATTACH`-ed **read-only**; used
  **exclusively** for feed/priority `SELECT`s. It **never writes** — so it cannot contend with Room.
- **Room's pool owns every write.** WAL permits concurrent readers alongside a single writer, so the two
  coexist by design rather than by luck.
- **Why this works where the naive version doesn't:** the ATTACH lives on exactly one connection that we
  own and never hand back to a pool, so it cannot vanish. The javadoc's "no parallel queries" warning is
  a **throughput** note about that one connection — not a correctness problem, and irrelevant to a
  single-user reader.

⚠ **This rests on a documented gap and MUST be spiked on the real device before anything is built on
it** (step 1 below). The claim to falsify: *a dedicated read-only connection with an ATTACH can read
`progress_<EXAM>.db` while Room's WAL pool writes it, without error or stale reads.* If the spike fails,
fall back to joining in Kotlin (`fsrs_cards` is sparse and 32,957 rows fit in memory) and amend
`SPEC.md` §2 — **do not** work around it by disabling WAL, which would degrade every write in the app.

### 3.6 DECISION: the schema gate's exact semantics 🟡

§8 A1 says refuse "newer/unknown". `SPEC.md` §4 is more precise: *"declares the max `schema_version` it
supports and refuses to import a **newer** one rather than crash; a compatible **older** pack still
loads."* Implementing SPEC.md's version (it is the contract, and §8 doesn't contradict it — it's terser):

```
SUPPORTED_SCHEMA_MAX = 1        // ONE constant, the single source of truth
pack.schema_version > MAX  -> refuse, naming both versions
pack.schema_version <= MAX -> load
```

Gate order — **verify before opening anything else** (§8 A1's words), i.e. before reading any content
table, so a hostile/corrupt pack never reaches a real query:
1. `PRAGMA user_version` (INTEGER)
2. `SELECT schema_version FROM pack_manifest` (**TEXT** — parse, don't `==`)
3. cross-check they agree (SPEC.md §4: "mirrors"); disagreement = refuse, the pack is malformed
4. `manifest.json.schema_version` is a mirror; DB row is authoritative (SPEC.md §3)

**The type mismatch is the trap:** `"1"` (TEXT) vs `1` (INTEGER) vs `"1"` (JSON string). Parse all three
to Int; never compare across representations. Refusal must **name both versions** and never crash.

---

## 4. Build order (each step ends green: `verifyOfflineGuarantees` + tests)

Nothing here starts until §3 is answered.

0. ✅ **`docs-researcher` — DONE 2026-07-17.** Findings + citations recorded in PLAYBOOK "Android app A1
   dependencies — Room+KSP, SQLite ATTACH, SAF, ZIP, Storage". Headlines: Room 2.8.4 / KSP 2.3.10 (KSP2
   mandatory, kapt a hard error, no `room-ktx`); **Room+ATTACH is a documented gap → §3.7**; use
   `DocumentsContract` + projection, **not** `DocumentFile` (its own javadoc says it "has substantial
   overhead"); SQLite **cannot** open a `content://` URI (hence the copy); zip-slip mitigation is
   official, verbatim code; `StorageManager.getAllocatableBytes()` (API 26+, our exact floor) beats
   `File.getUsableSpace()` per Google's javadoc — it counts reclaimable cache.

1. **SPIKE the ATTACH assumption FIRST — throwaway code, on the real phone (§3.7).** Everything else
   rests on it, and it rests on a documented gap. **Falsify this:** *a dedicated read-only connection
   with an ATTACH can read `progress_<EXAM>.db` while Room's WAL pool writes it, without error or stale
   reads.* Pass ⇒ build step 4 on it. Fail ⇒ Kotlin-side join + amend `SPEC.md` §2; **never** "fix" it by
   disabling WAL. Also measure the real `DocumentsContract` traversal of 1,121 figures here — cheap to
   learn now, expensive to discover in step 3.
2. **Pack contract layer** — data classes for `pack_manifest`, `subjects`, `tag_defs`; parse both
   representations. No UI. Unit-testable without a device.
3. **The schema gate** (§3.6) — one constant, refusal messages, the type-parsing. **Tests first**: a v1
   pack loads; a v2 pack is refused by message, not by crash; a `pack_manifest`-less DB is refused; the
   TEXT/INTEGER mismatch does not false-refuse.
4. **SAF import + copy** (§3.5) — `DocumentsContract` + projection (**not** `DocumentFile` — its own
   javadoc says it "has substantial overhead"), temp dir, progress, cancel, atomic rename,
   `StorageManager.getAllocatableBytes()` precheck, file-hash verify against **`manifest.json.checksum`**
   (§3.3 — *not* `pack_manifest.checksum`). Guard stays green: no WorkManager.
5. **Progress DB + registry + the dedicated read-only ATTACH connection** (§3.7) — per-`exam_code`
   `progress_<EXAM>.db` from SPEC.md §2's DDL verbatim, Room-owned for writes; the spiked read-only
   connection for cross-DB reads. **Test the invariant that justifies the whole two-DB design: re-import
   must not wipe progress.**
6. **Manifest-driven UI + capability detection** (§3.1a) — app bar from `exam_name`, filter chips from
   `subjects`, tag chips from `tag_defs`; **availability from actual rows**, cross-checked against
   `counts_json`/`feature_flags_json`. **Zero exam strings, zero hardcoded asset assumptions** — assert
   both in tests, don't promise them.
7. **"Switch exam" screen** — lists registry, switches active pack. Empty state matters: A1 ships with a
   real pack but a fresh install has none.
8. **§11e export/import ZIP** — `progress.db` + `note_images/`, to Downloads via SAF
   (`ACTION_CREATE_DOCUMENT`). Per-pack. **Zip-slip mitigation mandatory on import** — canonical-path
   containment, per Google's official verbatim code (PLAYBOOK). Acceptance test 12: export → wipe →
   reinstall → import → the note **and its image** return.

**§11e is not optional and not deferrable.** Its "safe to defer" premise was "no real pack exists". A
real 32,957-item pack is now on this laptop; the moment it is studied, progress becomes real and losable
(§11d), and the keystore is the only thing standing between the user and a wiped `progress.db`.

---

## 5. Acceptance (what "A1 done" means — each provable, not asserted)

- [ ] A pack imports from a user-picked directory; progress survives a re-import of the same exam.
- [ ] A `schema_version` newer than `SUPPORTED_SCHEMA_MAX` is **refused with a message naming both
      versions** — no crash, no partial load. Proven with a synthetic v2 pack.
- [ ] Every exam-facing label comes from `pack_manifest`. **`grep -ri "mrcp" app/src/` returns only the
      forbidding comment** — enforced by a test, not a promise.
- [ ] **Swappability proven in BOTH directions with the SAME binary** (§3.1a — the headline acceptance):
      today's full pack offers every card type (blobs/interlink/figures included — **the data exists,
      use it**); a **synthetic trimmed pack** offers fewer, with no dead UI and no crash. Building that
      trimmed fixture is part of A1.
- [ ] Availability is resolved from **actual rows** (authoritative), cross-checked against
      `counts_json`/`feature_flags_json`; a flag/row disagreement is surfaced, never silently believed.
- [ ] **No hardcoded asset-type assumption anywhere** — the quiet twin of the no-hardcoded-exam-string
      rule, and the one that silently breaks pack-swapping.
- [ ] §11e round-trip passes acceptance test 12, image included.
- [ ] `verifyOfflineGuarantees` green: no INTERNET, no background component, no forbidden dep.
- [ ] `testDebugUnitTest --rerun-tasks` green (`--rerun-tasks` mandatory — UP-TO-DATE proves nothing).
- [ ] `.claude/INDEX.md` updated in the **same commit** as any file add/move (global rule 11).

## 6. Risks

- **Two DBs, one transaction boundary.** Progress writes must never depend on the pack being attached;
  a pack swap mid-session must not corrupt progress. Keep the write path progress-only.
- **1,121-file SAF traversal.** `DocumentFile` is notoriously slow per-file; the copy may be dominated by
  URI resolution, not bytes. Measure before optimizing.
- **`allowBackup=false`** (A0) means an uninstall wipes progress — with **no** Auto Backup safety net
  (and per the PLAYBOOK, Auto Backup would not survive a key change anyway). §11e IS the insurance.
- **Scope creep into A2.** A1 imports and configures; it does **not** build the feed. Standing order #2:
  no resequencing.
- **The stale-spec pattern.** §3.1/§3.2 are the second and third stale-spec findings in two days. Trust
  the DB and `schema.sql`; treat prose counts as dated.
