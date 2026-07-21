# StudyFeed — the Android app

The generic, **fully-offline** player. It has no idea what exam you're studying: the exam name,
subjects, tag chips, and every item it shows are read from an imported **pack** (`pack_<EXAM>.db`,
built by [`../compiler`](../compiler)). The app is a renderer + scheduler; the pack is the exam.

> New here? Start with **[`../GET_STARTED.md`](../GET_STARTED.md)** (install & load a pack) and the
> project **[`../README.md`](../README.md)** (how it all fits together).

## What's inside

Everything runs offline against an imported pack — nothing is generated at runtime.

- **Study feed** — an infinite vertical pager over five item types (MCQ · flashcard · reel ·
  passage · interlink) with **FSRS-6** spaced repetition and an importance-weighted ordering engine
  paced to an editable deadline.
- **Library** — pre-compiled cross-subject topic dossiers.
- **Concept map** — a walkable ego-graph of related concepts.
- **Progress, sessions & more** — a data-rich progress screen, named resumable study sessions, a
  blueprint-weighted mock exam, weak-spot drills, custom decks, and a dashboard home.
- **Honest about each pack** — every section states plainly when a lean pack doesn't include its
  content, instead of showing a blank screen. Capabilities are detected per pack at runtime.

The pack is **not** in this repo — you build it from your own material with the compiler, then
import it. A fresh clone builds and runs; it just shows an "import a pack" screen until you give it
one.

## Build & run

Requires a **JDK 17+** and the **Android SDK** (set `ANDROID_HOME`). Gradle itself is not needed —
the committed wrapper fetches and checksum-verifies it.

```bash
./gradlew :app:assembleDebug            # dev build -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # JVM unit tests (FSRS, parsing) — no device needed
./gradlew :app:verifyOfflineGuarantees  # the offline / no-background guard (see below)
./gradlew :app:assembleRelease          # R8-minified release APK (signed only if a keystore is configured)
./gradlew :app:check                    # lint + tests + guard
```

Install over USB (developer mode on): `adb install -r app/build/outputs/apk/debug/app-debug.apk`
(`-r` keeps app data across reinstalls, as long as the signing key is unchanged).

If Gradle reports `JAVA_HOME is not set`, point it at a JDK for that shell (e.g.
`export JAVA_HOME=...` / `$env:JAVA_HOME = "..."`). Don't hardcode a machine path into the committed
`gradle.properties` — put machine-local settings in `~/.gradle/gradle.properties`.

## The offline / no-background guarantee

This app must never reach a network and never run in the background — and the **build enforces it**,
so an APK that violates the guarantee **cannot be produced**. Every `assemble<Variant>` depends on
two checks:

1. **`check<Variant>MergedManifest`** — parses the *merged* manifest (catching components a library
   injects transitively) and fails on network/background permissions, or any unjustified `<service>`,
   `<receiver>`, or `<provider>`. It parses the XML rather than grepping it.
2. **`check<Variant>DependencyDenylist`** — walks the whole resolved runtime classpath (transitives
   included) and fails on HTTP clients, background schedulers, and AI/analytics SDKs, naming which
   dependency pulled the offender in.

Living with it:

- A normal AndroidX/Compose library needs nothing extra.
- If a dependency injects a component you don't want, strip it in `app/src/main/AndroidManifest.xml`
  with `<service android:name="the.Name" tools:node="remove" />`.
- Trace who pulls something in: `./gradlew :app:dependencies --configuration debugRuntimeClasspath`.
- The rules live in [`../build-logic`](../build-logic); each allowlist entry carries a justification.

`build-logic` is an *included build* (not `buildSrc`) because the plugin uses AGP's Variant API.

## Signing (for distribution)

Release builds are signed with a key that lives **outside this repo and is never committed**. Gradle
reads its location from a `keystore.properties` referenced via a user-level Gradle property or an env
var; if none is present the build still succeeds — release just comes out **unsigned** — so a fresh
clone is never blocked by a missing secret. For automated signed releases, keep the keystore and its
passwords in your CI provider's encrypted secrets, never in git.
