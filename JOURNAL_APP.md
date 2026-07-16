# JOURNAL_APP — Phase 2 (Android app) human log

Companion to `JOURNAL.md` (Phase 1 / compiler, written by `state.py` on the Hetzner
box). This file is owned by the laptop. Never append compiler progress here, and
never append app progress to `JOURNAL.md` — that split is what keeps the two
machines from colliding.

## ⛔ STANDING ORDERS FROM THE USER — READ FIRST, DO NOT VIOLATE.
*(Originally 2026-07-17. **Revised 2026-07-17 by the user** — see the revision note under each.)*

**1. ~~`BUILD_PROMPT.md` IS ABOUT TO CHANGE. THE USER WILL UPDATE IT — YOU WILL NOT.~~**
**↳ REVERSED 2026-07-17 by explicit instruction:** *"edit the buildmd and other files to be relevant
of the future changes."* The user delegated the spec update rather than doing it themselves, and it
is **DONE** — see `BUILD_PROMPT.md` **PATCH 02** (applied in place; the patch section at the file's
end is the audit trail). What still binds from the original order:
- **Read `BUILD_PROMPT.md` from disk before acting. Never act on a memory of the old text** — large
  parts of it (§7, §13, START HERE steps 1–4) are now explicitly marked *history, do not execute*.
- If the spec and the repo disagree, **surface it and ask; never silently reconcile.** A future
  contradiction is still not automatically yours to "fix" — this one was authorised.

**2. NO RESEQUENCING. A1 IS NEXT, IN SPEC ORDER (§8).** ← **STILL BINDS, UNCHANGED.**
A proposal to build a synthetic fixture pack first and jump to A2 (so the UI could be seen sooner)
was **put to the user and DECLINED on 2026-07-17.** Do not revive it, and do not build a fixture
pack. Follow §8's order: A0 → A1 → A2 → …
- The proposal's reasoning was *"no pack exists yet — the compiler is only at C10 of C13."*
  **That premise is now dead** (the compiler finished; a real pack exists) — which removes the
  argument *for* the fixture pack entirely. The decision stands, and now the facts agree with it.
- ⚠ PATCH 02 moved **§11e export/import into A1**. That is **not** a resequencing — §11e always
  said *"treat it as part of A1"*; §8's stage list simply disagreed with it, and the contradiction
  was resolved in §11e's favour. A1's scope grew; §8's order did not change.

**3. ~~THE USER MAKES THE PACKS AND BUILDS THE APP.~~ → PACKS ARE DONE; THE APP IS STILL THEIRS.**
**↳ Half-satisfied 2026-07-16/17:** the user's compiler finished **C0–C13** and produced
`compiler/dist/pack_MRCP_PART1.db` (26,965 playable items). So: **do not drive the compiler** (it is
finished and dormant), and **never invent pack data** — a real pack exists; use it. The app remains
the user's build, on this laptop.

**4. NOTHING GETS PUSHED WITHOUT AN EXPLICIT ASK.** ← **THE RULE STILL BINDS.**
**↳ 2026-07-17: an explicit ask was given** (*"push my changes on local laptop on github"*) and A0
was pushed — `origin/dev` now has it (`a457dd0..ea55417`). The *rule* is unchanged: §11a stands,
**ask before any remote operation.** A past authorisation is not a standing one.

---

## §0 `build_env` divergence — ✅ RESOLVED 2026-07-17. Historical record only.

**Status: CLOSED. This is no longer a live warning — do not act on it as one.**
For ~24h this journal carried a deliberate divergence: `BUILD_PROMPT.md` §0 said the app was built on
the Hetzner box, while it was really built here. **The user amended §0 on 2026-07-17** (PATCH 02),
reversing their 2026-07-16 ruling that the locked parameter stay verbatim. **Spec and reality now
agree — there is nothing left to reconcile, and nothing here to "fix".**

Kept as the record of *why* the app moved to the laptop (the reasoning still explains the split):
- The box **cannot** run a Gradle build: ~2.7GB RAM free, swap 96% exhausted. A Gradle daemon plus the
  Kotlin compile daemon would trip the OOM killer, whose first target is the largest process — a
  running compiler session.
- The box is **headless**. §8 A2's feed must be "made excellent" — infinite pager, swipe, tap-to-flip,
  AMOLED dark. That cannot be judged without a screen and a real AMOLED panel.
- Disk: the box was at 84% (24GB free), 9GB already in `sources/` + `work/`.

What never changed, and still binds:
- **The CLI-buildable INTENT of §8 A0** — the app must build from a terminal with no IDE-only config.
  Honoured on the laptop and **enforced by `build-logic/`**, not merely promised. Only "over SSH" was
  ever moot.
- **The decoupling contract.** The compiler emits a pack `.db`; the app plays any pack. Untouched by
  the move and untouched by PATCH 02.

**How the pack reaches this laptop:** `scp` from the box's `compiler/dist/` (gitignored — derived from
copyrighted sources, must never enter git), then `adb push` or SAF-import onto the phone. The pack
travels **separately from the APK** — that separation is the whole point (§0 `multi_pack`).

## Log

- 2026-07-16 — Laptop provisioned as the Phase 2 build machine. Claude Code + master
  rules installed, Android Studio linked. Divergence above recorded. No app code yet;
  A0 not started.
- 2026-07-16 — A0 plan written to `.claude/plans/2026-07-16-a0-skeleton.md`. Environment
  verified on the laptop: JBR 21.0.9 (`java` NOT on PATH → Gradle needs an explicit
  `org.gradle.java.home`), SDK platforms android-36/36.1 + build-tools 36.0.0/36.1.0
  (→ compileSdk 36), `ANDROID_HOME` set, adb present with no device attached.
  `.gitignore` already covers `*.jks` / `keystore.properties` / `*.apk` — §11d/§11b hold
  before any app file lands.
- 2026-07-16 — User confirmed laptop-builds-app and ruled §0 `build_env` stays UNCHANGED
  (see decision above). APK delivery (§11c: scp-vs-Cockpit) is moot on the laptop —
  default is `adb install -r` over USB from the standard Gradle output path, unless the
  user says otherwise. Still open at the gate: keystore location, minSdk, applicationId.
- 2026-07-16 — Gate answered: keystore at `C:\Users\kayom\keys\studyfeed\`, **minSdk 26**,
  **applicationId `dev.kayomarz.studyfeed`** (permanent — changing it strands progress.db).

### A0 built — 2026-07-16/17

Skeleton + offline/no-background guarantees, per §8 A0. All of the following was RUN, not
assumed; outputs are in the session transcript.

**Toolchain** (versions verified against official sources, cited in PLAYBOOK "Android build
toolchain" — do not bump from memory): AGP 9.3.0 · Gradle 9.5.0 · Kotlin 2.4.10 · Compose BOM
2026.06.01 · fsrs 1.0.0 · compileSdk/targetSdk 36 · minSdk 26 · JVM target 17 on JBR 21.

- Gradle 9.5.0, not the newest 9.6.1: it is AGP 9.3.0's own default and the version Google's
  `agp-9.3` recipe branch ships. Best-tested beats newest for the thing that builds everything.
- Wrapper pins `distributionSha256Sum` — the distribution is checksum-verified, not trusted.
- **AGP 9 has built-in Kotlin**: applying `org.jetbrains.kotlin.android` is now a hard error.
  Compose stays a separate plugin. `jvmTarget` defaults from `compileOptions.targetCompatibility`.
- `JAVA_HOME` set at user level → JBR 21 (this machine has no `java` on PATH). Deliberately NOT
  hardcoded into the committed `gradle.properties`, which would break every other machine.

**The guard** (`build-logic/`, an included build — buildSrc cannot host an AGP-Variant plugin):
every `assemble<Variant>` depends on a merged-manifest check + a runtime-classpath denylist, so a
violating APK cannot be produced. Proven by deliberate sabotage, each failing the build then
reverted: INTERNET permission ✓ caught · `<service>` ✓ caught · okhttp ✓ caught · retrofit ✓
caught *with* its transitive okhttp 3.14.9 and the dependency path.

- The merged manifest revealed AndroidX injecting components we never asked for.
  `androidx.profileinstaller`'s **exported** `ProfileInstallReceiver` is now stripped
  (`tools:node="remove"`) — it only serves Play-Store profile delivery and adb benchmarking,
  neither of which a sideloaded app sees. That keeps an absolute zero-receivers rule.
- `androidx.startup.InitializationProvider` is the ONLY allowlisted component, with written
  justification: exported=false, runs at process start, schedules nothing; removing it would take
  ProcessLifecycleOwner (which A2 needs for "next session" re-reads).
- `allowBackup=false`: Android Auto Backup would copy progress.db to Google Drive. It would soften
  the §11d risk but by shipping study data off-device, which contradicts §0. §11e export/import is
  the sanctioned answer. **Flagged to the user; reversible if they'd rather have cloud backup.**

**FSRS-6**: 3 JVM tests, 0 failures, 0 skipped. Beyond the required Again/Hard/Good/Easy, it
reproduces upstream `FSRSTest.testReviewCard`'s exact interval sequence
`[0,4,14,45,135,372,0,0,2,5,10,20,40]` — that oracle, not the smoke test, is what would catch a
wrong FSRS-6 weight vector on a future version bump.

**Artifacts**: `app-debug.apk` 11.95 MB · `app-release.apk` **0.73 MB** (R8 + resource shrink).
Release APK verified signed: RSA 4096, `CN=StudyFeed`, APK Signature Scheme v2. Shipped release
APK contains **no INTERNET permission** (checked with aapt2 on the artifact itself, not the config).

**⛔ KEYSTORE GATE — CLOSED 2026-07-17.** User confirmed the keystore is backed up to Google
Drive (off-laptop). A0 is therefore complete and A1 may start. Advice given, user's call: the
`.p12` and `keystore.properties` sit in one folder, so a single account compromise yields both key
and password — worth splitting the password into a password manager.

Original gate record:
- `C:\Users\kayom\keys\studyfeed\studyfeed-release.p12` — PKCS12 (NOT legacy JKS), RSA 4096,
  valid to 2053-12-02. Generated once, outside the repo, `CN=StudyFeed, O=StudyFeed` (no personal
  data in the cert).
- `C:\Users\kayom\keys\studyfeed\keystore.properties` — passwords; random, never printed to any
  transcript, never committed. Gradle finds it via `studyfeed.keystoreProperties` in
  `~/.gradle/gradle.properties` (user-level). Absent → release unsigned, debug still builds.
- Release cert SHA-256 (to identify future APKs as same-key):
  `ba:4f:05:a5:74:77:c5:26:04:1a:d7:6c:2f:4c:18:e2:f9:a5:42:88:3e:1d:49:96:7a:15:f5:10:c9:68:83:e3`
- `.gitignore` extended: `*.p12`/`*.pfx` (it covered `*.jks` only — a format change would have
  slipped past the ignore rules).

Six stumbles logged to the PLAYBOOK mistakes ledger (AGP 9 built-in Kotlin; buildSrc-vs-included
build for AGP plugins; never grep a manifest — parse it; keytool's legacy JKS default; backslashes
in .properties; Gradle 9 won't bootstrap a wrapper without a settings file).

### Post-A0 notes — 2026-07-17

**Google Drive backup of progress: researched, NOT built, and the headline finding matters.**
The user asked for it. Full findings + citations in PLAYBOOK "Android Auto Backup". The deciding
fact: **Auto Backup does NOT survive a signing-key change.** Android's restore path checks the
app's signature (AOSP `PerformUnifiedRestoreTask` → `BackupUtils.signaturesMatch()`); on mismatch
the restore is *silently skipped* — clean install, no data, no error. Only APK Signature Scheme v3
key-rotation lineage forgives it, and that must be established **before** the old key is lost. So
Auto Backup does nothing for the §11d lost-keystore disaster it was proposed to mitigate; §11e
export/import remains the only real insurance. It IS still worth having for new-phone / ordinary
reinstall **while the key is intact**. If built (A1): quota is 25 MB/app and **all-or-nothing**, so
the imported pack .db MUST be excluded via `dataExtractionRules` (API 31+) *and* legacy
`fullBackupContent` (minSdk 26 needs both) or it blows the quota and kills progress backup too.
No INTERNET permission needed — the OS does it out-of-process, so it does not violate §5/§12.

**`gradle/gradle-daemon-jvm.properties` — deleted deliberately, 2026-07-17.** Gradle's own
`updateDaemonJvm` generated it during a wrapper run (not by intent). It pinned `toolchainVersion=21`
but also embedded **foojay.io** JDK download URLs for every OS — i.e. a machine without JDK 21 would
silently fetch a JDK over the network at build time. We don't need it (JAVA_HOME supplies JBR 21 and
`app/README.md` documents that), and it adds an unreviewed supply-chain dependency. Removed;
`verifyOfflineGuarantees` re-run afterwards and still green. Regenerate with `./gradlew
updateDaemonJvm` if it is ever genuinely wanted — that is a decision, not a default.

**Open item for whoever does A1:** §11e export/import must land before the user ever studies real
progress. ~~Safe to be after A2 ONLY because no real pack exists yet~~ — **that premise died on
2026-07-17: the pack now exists.** See the §11e escalation below; this is now A1 scope, not deferrable.

### 2026-07-17 — pushed to origin, and the spec reconciled to reality

**The two machines met.** Local `dev` was 4 ahead / 3 behind: the box had pushed C11→C13 while the
laptop's A0 sat unpushed. Rebased the 4 never-pushed local commits onto `origin/dev` (no force-push of
shared history). One conflict — `.claude/PLAYBOOK.md`, where both machines had appended mistakes-ledger
entries; resolved by **keeping both** (compiler C11/C13 + Android A0), never by picking a side.
`.claude/INDEX.md` auto-merged. Verified on the merged tree — a tree that had never existed before —
with `verifyOfflineGuarantees` + `testDebugUnitTest` **force-rerun** (`--rerun-tasks`; the first run
reported UP-TO-DATE, which proves nothing): BUILD SUCCESSFUL. `secret-scanner`: CLEAN. Pushed
`a457dd0..ea55417`. History is now one line: compiler C0–C13, then app A0.

**PHASE 1 IS DONE — this is the fact that moved everything.** `compiler/dist/pack_MRCP_PART1.db`:
86,702 atoms · 14,455 MCQs · 9,510 flashcards · 3,000 reels · 8,194 dossiers · **26,965 playable
items** · 147,146 stable_keys, reproducibility gate cleared. **A1 now has a real pack to open** — no
fixture pack was ever needed, and none must be built.

**`BUILD_PROMPT.md` PATCH 02 — applied in place, authorised by the user.** The user reversed both the
"I'll update the spec myself" order and the 2026-07-16 "§0 stays verbatim" ruling. §0 `build_env` now
splits compiler→box / app→laptop; §7 and §13 and START HERE steps 1–4 are marked **history, do not
execute** (they were actively misdirecting fresh sessions to re-run kickoff on a finished compiler);
§11c drops the box/`scp`/Cockpit delivery for **`adb install -r`** (that question is dead — do not
re-raise it). Full table in the patch section at the end of `BUILD_PROMPT.md`.

**"Work with other databases in future" — scoped, and it needs no new architecture.** The user
clarified it means **other exam packs in the same format** (MRCP Part 2, USMLE, …), **not** foreign
DBs (Anki/CSV) — no importer/adapter layer is to be built. Multi-pack is already the locked contract
(§0 `multi_pack`, §8 A1 "generic"), and **A0 is verifiably clean**: the only "MRCP" anywhere under
`app/` is a comment in `strings.xml` forbidding it. Two things were added to A1 to make the promise
*safe* rather than merely intended:
- **Schema-version gate (NEW):** check `pack_manifest.schema_version` + `PRAGMA user_version` (=1) on
  import; refuse an unsupported pack with a clear message naming both versions. Never crash, never
  half-load. A pack built on a bumped schema is precisely what a naive loader corrupts — this is the
  difference between "plays future packs" being a guarantee and a hope.
- **Degrade gracefully on empty tables:** `text_blobs`/`blob_quizzes`/`interlink_quizzes`/`figures`/
  `dup_clusters` ship **empty** (trimmed at C11). **⚠ This bites A2, not just A1** — its blob and image
  card types have no data. Absent ≠ broken: drive availability off actual rows, never assume.

**§11e escalated to A1 and given one owner.** §11e always said "treat it as part of A1" while §8 listed
it under A7 — a straight contradiction, resolved in §11e's favour (A1 builds it; A7 only surfaces the
Settings entry points). Not a resequencing: standing order #2 is untouched. Per PATCH 01 it is a **ZIP**
(`progress.db` + `note_images/`), per-pack like the progress DB itself.

**Still open / next:** **A1**, per §8 order — pack import via SAF, UI config from `pack_manifest`,
per-exam progress DB, "switch exam" screen, the schema-version gate, and §11e export/import.
