# JOURNAL_APP — Phase 2 (Android app) human log

Companion to `JOURNAL.md` (Phase 1 / compiler, written by `state.py` on the Hetzner
box). This file is owned by the laptop. Never append compiler progress here, and
never append app progress to `JOURNAL.md` — that split is what keeps the two
machines from colliding.

## Known divergence from BUILD_PROMPT — READ THIS BEFORE "FIXING" ANYTHING

**Status: KNOWN, INTENTIONAL, pending a spec amendment at A0. Not a bug. Do not
re-litigate this every session, and do NOT edit `BUILD_PROMPT.md` to resolve it.**

`BUILD_PROMPT.md` §0 "Locked parameters" (~line 49) says:

    build_env:  hetzner_headless_claude_code   # Linux, no GUI; Gradle CLI; sideload APK

That is no longer where the app is built. As of 2026-07-16 the Android app is built
on a 16GB Ryzen 5 laptop with Android Studio and a physical phone.

Why the change:
- The box cannot run a Gradle build. It had ~2.7GB RAM available with swap 96%
  exhausted; a Gradle daemon plus the Kotlin compile daemon would trip the OOM
  killer, whose first target is the largest process — a running compiler session.
- The box is headless. §8 A2 says the Study Feed is to be built first and "made
  excellent" — infinite pager, swipe, tap-to-flip, AMOLED dark. That cannot be
  judged without a screen and a real AMOLED panel.
- Disk: the box was at 84% (24GB free) with 9GB already in `sources/` + `work/`.

What survives unchanged:
- §8 A0's "buildable headless via `./gradlew assembleDebug`" — the INTENT (the app
  must build from the CLI and must not depend on IDE-only configuration) is KEPT,
  and is satisfied by running `./gradlew assembleDebug` from the laptop terminal.
  Only the "over SSH" clause is moot.
- §1432 `./gradlew assembleDebug` as the dev loop — still true, different machine.
- The decoupling contract is untouched. The compiler still runs on the box and still
  emits a pack `.db`; nothing about the pack format changes.

**DECIDED 2026-07-16 (user, at the A0 gate): §0 `build_env` is NOT amended.** The user is
building the app on the laptop and has ruled that the locked parameter stays verbatim —
this journal carries the divergence instead. So `BUILD_PROMPT.md` §0 still reads
`hetzner_headless_claude_code`, and that is deliberate, not stale. Do not "reconcile" it.
The INTENT of §8 A0 (CLI-buildable, no IDE-only config, wrapper committed) is what binds,
and it is honoured on the laptop; only the "over SSH" clause is moot.

Open item for A0 (needs the user's explicit decision):
- Keystore (listed as an A0 gate in `.claude/plans/2026-07-15-studyfeed-kickoff.md`)
  is now generated on the laptop. It must be backed up off-machine and must never be
  committed — `*.jks` and `keystore.properties` are gitignored for exactly this
  reason ("losing these is bad, committing them is worse").

How the pack reaches this laptop: `scp` from the box's `compiler/dist/` (gitignored —
it is derived from copyrighted sources and must never enter git), then `adb push` or
SAF-import onto the phone.

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

**⛔ KEYSTORE GATE — OPEN. A0 does not close until the user confirms an off-laptop backup.**
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
