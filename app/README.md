# StudyFeed — Android app (Phase 2)

The generic, fully-offline player. It has no idea what MRCP is: the exam name, subjects, tag chips
and every item it shows come from an imported **pack** (`pack_<EXAM>.db`, compiled by `compiler/`).
The app is a renderer + scheduler; the pack is the exam. See `BUILD_PROMPT.md` §8 for the build
order and `.claude/INDEX.md` for the file map.

**Build machine:** this laptop (Android Studio + a physical phone). The compiler still runs on the
Hetzner box. `BUILD_PROMPT.md` §0 still says `build_env: hetzner_headless_claude_code` — that is a
locked parameter the user has chosen to leave verbatim; `JOURNAL_APP.md` carries the divergence and
explains it. Don't "fix" §0.

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

### Sizes as of A0 (skeleton, no pack)

| Artifact | Size |
|---|---|
| `app-debug.apk` | ~11.9 MB (unminified, includes Compose tooling) |
| `app-release.apk` | ~0.73 MB (R8 minified + resource shrinking) |

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
    ui/theme/                AMOLED true-black Material3 (dynamic colour deliberately off)
app/src/test/java/.../fsrs/  FSRS-6 scheduling tests, incl. upstream's regression oracle
app/src/main/AndroidManifest.xml    zero permissions — read the comment before editing
build-logic/                 the offline-guarantees Gradle plugin (an included build)
gradle/libs.versions.toml    every version pinned; see .claude/PLAYBOOK.md for why each one
```

`applicationId` is `dev.kayomarz.studyfeed` and is **permanent** — changing it strands the progress
DB on the phone exactly like losing the keystore does.
