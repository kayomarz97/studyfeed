# StudyFeed — Android app (Phase 2)

The generic, fully-offline player. It has no idea what MRCP is: the exam name, subjects, tag chips
and every item it shows come from an imported **pack** (`pack_<EXAM>.db`, compiled by `compiler/`).
The app is a renderer + scheduler; the pack is the exam. See `BUILD_PROMPT.md` §8 for the build
order and `.claude/INDEX.md` for the file map.

**Build machine:** this laptop (Android Studio + a physical phone); the compiler ran on the Hetzner
box and is now dormant. **PATCH 02 (2026-07-17) amended `BUILD_PROMPT.md` §0** to split
compiler→box / app→laptop, so the spec and reality now agree — the old §0 divergence is closed and
is history, not a live warning. `JOURNAL.md` (**Phase 2 — app** section) keeps the record of why.
The CLI-buildable intent of §8 A0 still binds and is enforced by `build-logic/`.

---

## What's built (A0–A7)

Feature-complete for the first profile (MRCP Part 1). Everything below runs **fully offline** against an
imported pack; nothing is generated at runtime. See `JOURNAL.md` (Phase 2) + `.claude/INDEX.md` for the map.

- **A0** — skeleton + the build-enforced offline / no-background guarantee (below).
- **A1** — pack import (SAF folder), the **two-DB model** (a read-only pack `ATTACH`-ed beside a
  read-write `progress_<EXAM>.db` that Room owns), a schema-version gate, and §11e export/import of progress.
- **A2 / A3** — the **Study Feed**: an infinite vertical pager over five item types (MCQ · flashcard · reel
  · passage · interlink), FSRS-6 spaced repetition, and an ordering engine (due pool + importance-weighted
  new drip + variety, paced to an editable deadline).
- **A4 / A5** — the **Library** (pre-compiled cross-subject topic dossiers) and the **Concept map** (a
  walkable ego-graph).
- **A6 / A7** — a data-rich **Progress** screen, a dashboard **Home**, **Settings**, and the A7 design pass.
- **A7 cycle 3** — **named, resumable study sessions**; **topic + per-line stars** with approximate PYQ/5★
  line highlighting; a **mock exam** (blueprint-weighted, scored); weak-spot **drills** + leeches; custom
  **decks**; and a branded opening screen. Progress DB migrates `v2 → v3` additively (never destructively).

The pack is not in this repo — it is compiled by `compiler/` from copyrighted sources and imported at
runtime. A fresh clone builds and runs; it just shows an "import a pack" screen until you give it one.

---

## Prerequisites

| Need | This machine has | Notes |
|---|---|---|
| JDK 17+ | JBR 21.0.9, bundled with Android Studio | `java` is **not** on PATH — see below |
| Android SDK | `C:\Users\kayom\AppData\Local\Android\Sdk` | `ANDROID_HOME` is already set; platform **36** + build-tools **36.1.0** installed |
| Gradle | none installed — the **wrapper** is committed | `./gradlew` downloads Gradle 9.5.0 and checksum-verifies it |

### JAVA_HOME (the one gotcha)

`./gradlew` needs a JVM to start, and this machine has no `java` on PATH — only Android Studio's
bundled one. `JAVA_HOME` is set at user level to:

```
C:\Program Files\Android\Android Studio\jbr
```

If a *new* terminal ever says `ERROR: JAVA_HOME is not set`, that's this. Per-shell fix:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Git Bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # PowerShell
```

This is deliberately **not** hardcoded as `org.gradle.java.home` in the committed
`gradle.properties` — that would bake one laptop's path into the repo and break every other
machine. Machine-local settings belong in `~/.gradle/gradle.properties`.

---

## Commands

```bash
./gradlew :app:assembleDebug           # the dev loop -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest       # JVM unit tests (FSRS scheduling) — no device needed
./gradlew :app:verifyOfflineGuarantees # the offline/no-background guard (see below)
./gradlew :app:assembleRelease         # signed + R8-minified -> app/build/outputs/apk/release/app-release.apk
./gradlew :app:check                   # everything: lint + tests + guard
```

Install on the phone (USB, developer mode on):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` reinstalls in place and **keeps app data**. It only works while the signing key stays the
same — which is the whole point of §11d below.

### Sizes

| Artifact | Size |
|---|---|
| `app-debug.apk` | ~13.2 MB (unminified, includes Compose tooling) |
| `app-release.apk` | ~0.8 MB (R8 minified + resource shrinking) |

The pack is **imported via SAF, never bundled into the APK** (§11c). If the APK ever jumps by the
size of the pack, something has gone wrong.

---

## The offline / no-background guarantee

This app must never reach a network and must never run in the background (`BUILD_PROMPT.md` §0, §8
A0, §12). That isn't a convention anyone has to remember — the build enforces it. Every
`assemble<Variant>` depends on two checks, so **an APK that violates the guarantee cannot be
produced**:

1. **`check<Variant>MergedManifest`** — parses the *merged* manifest (not the one we wrote, so a
   library that injects a component transitively is caught too) and fails on network/background
   permissions, any `<service>`, any `<receiver>`, and any `<provider>` outside a justified
   allowlist. It parses XML rather than grepping: an early grep version "found" INTERNET in a clean
   build by matching the warning comment in our own manifest.
2. **`check<Variant>DependencyDenylist`** — walks the variant's whole resolved runtime classpath,
   transitives included, and fails on HTTP clients, WorkManager, Play Services/Firebase and AI SDKs,
   naming *which* dependency pulled the offender in.

Both were verified by deliberate sabotage — adding INTERNET, adding a `<service>`, adding okhttp,
and adding retrofit (whose transitive okhttp was caught with its dependency path) — each failing the
build, then reverted.

Living with it:

- Adding a normal AndroidX/Compose library: nothing to do.
- A dependency injects a component you don't want: strip it in `app/src/main/AndroidManifest.xml`
  with `<service android:name="the.Name" tools:node="remove" />` — that's how
  `androidx.profileinstaller`'s exported receiver is removed.
- Find who pulls something in: `./gradlew :app:dependencies --configuration debugRuntimeClasspath`.
- The rules live in `build-logic/src/main/kotlin/dev/kayomarz/studyfeed/build/`. Every allowlist
  entry carries a written justification. **Adding one is a conversation with the user, not a commit.**

`build-logic` is an *included build*, not `buildSrc`, because a plugin using AGP's Variant API
cannot work from buildSrc's classloader scope. Don't move it back; the comments there explain why.

---

## Signing — read this before you touch it (§11d)

Release builds are signed with a key that lives **outside this repo** and is **not in git**:

```
C:\Users\kayom\keys\studyfeed\studyfeed-release.p12    # PKCS12, RSA 4096, valid to 2053
C:\Users\kayom\keys\studyfeed\keystore.properties      # passwords — never commit, never paste
```

Gradle finds it via `studyfeed.keystoreProperties` in `~/.gradle/gradle.properties` (user-level, not
committed), or the `STUDYFEED_KEYSTORE_PROPERTIES` env var. If neither is present the build still
works — release just comes out unsigned — so a fresh clone is never blocked by a missing secret.

Release certificate fingerprint (SHA-256), so you can tell whether a future APK is signed with the
*same* key:

```
ba:4f:05:a5:74:77:c5:26:04:1a:d7:6c:2f:4c:18:e2:f9:a5:42:88:3e:1d:49:96:7a:15:f5:10:c9:68:83:e3
```

Verify any APK with:

```bash
"$ANDROID_HOME/build-tools/36.1.0/apksigner.bat" verify --print-certs -v <apk>
```

**Why this matters more than it looks.** Android identifies an app by its signing key. Lose that
`.p12` and every future build is a *different app* to the phone: no in-place upgrade, only
uninstall-and-reinstall — which deletes `progress.db` and with it every mistake, tag, star, streak
and FSRS due date. Back up **both files** somewhere off this laptop. Losing them weeks before an
exam is the failure this project cannot afford.

Export/Import progress (§11e, A1) is the second half of that insurance: it makes the data
recoverable even if the key is lost. It is not optional.

---

## Layout

```
app/src/main/java/dev/kayomarz/studyfeed/
    MainActivity.kt          the app's only component — no service/receiver/provider, ever
    pack/                    import a pack (SAF), open it read-only, the schema-version gate
    progress/                Room owns progress_<EXAM>.db (FSRS cards, mistakes, stars, sessions, …)
    feed/ + ui/feed/         the Study Feed — ordering engine, five card types, sessions, FSRS bridge
    dossier/ + ui/dossier/   the Library — topic dossiers + per-line stars/highlights
    concept/ + ui/concept/   the Concept map (ego-graph explorer)
    stats/ + ui/stats/       the data-rich Progress screen
    exam/ + ui/exam/         the mock exam
    ui/home/  ui/settings/   the dashboard Home + Settings
    ui/theme/                AMOLED true-black Material3 (dynamic colour deliberately off)
app/src/test/…               JVM tests (FSRS oracle, markdown-safety, session config, line hash)
app/src/androidTest/…        instrumented tests (real-pack readers, the v2→v3 migration, …)
app/src/main/AndroidManifest.xml    zero permissions — read the comment before editing
build-logic/                 the offline-guarantees Gradle plugin (an included build)
gradle/libs.versions.toml    every version pinned; see .claude/PLAYBOOK.md for why each one
```

`applicationId` is `dev.kayomarz.studyfeed` and is **permanent** — changing it strands the progress
DB on the phone exactly like losing the keystore does.
