# StudyFeed

**A fully-offline, exam-agnostic MCQ study app.** A Python *compiler* turns copyrighted source PDFs into a
versioned SQLite **content pack**; a generic Kotlin/Jetpack-Compose Android app plays *any* pack —
configuring its subjects, tags and exam label at runtime from the pack. The app itself has **zero
network**: tokens are spent only at build time. First profile: **MRCP(UK) Part 1**.

The two halves are joined only by the pack format, so a new exam is a new profile + new sources → a new
pack, with **no app or compiler code change**.

## Three decoupled parts

| Dir | What | Language |
|---|---|---|
| [`spec/`](spec/) | The **pack contract** — `schema.sql` (source of truth), `SPEC.md`, `manifest.schema.json`, `SCORING.md`. The app knows only this. | SQL + docs |
| [`compiler/`](compiler/) | The **pack compiler** — a resumable 14-stage DAG (unpack → extract/OCR → atomize+ground → cluster → score importance → dossiers → generate assets → package). Runs offline; LLM work goes through the Anthropic Batch API, never into an app. **Done + dormant.** | Python 3.11 (uv) |
| [`app/`](app/) | The **generic player** — Room + FSRS-6 spaced repetition, an AMOLED-dark Compose UI, an infinite study feed, a topic library, a concept map, progress analytics, and a mock exam. See [`app/README.md`](app/README.md). | Kotlin / Compose |
| [`profiles/`](profiles/) | Per-exam config (`mrcp_part1.yaml`). Everything exam-specific lives here — never in code. | YAML |

## The offline guarantee (enforced, not promised)

The app must never reach a network and never run in the background. This isn't a convention — the build
**fails** if an APK gains an `INTERNET` permission, any background component, or a networking dependency
(a Gradle plugin in [`build-logic/`](build-logic/) parses the merged manifest + walks the runtime classpath
on every `assemble`). Resurfacing (spaced-repetition due dates, passage re-reads) happens **in-app on next
open**, never via an alarm or service.

## What's built

- **Compiler (Phase 1):** complete (C0–C13). Produces a shippable pack + `manifest.json` + figures.
- **App (Phase 2):** A0–A7 done — pack import & the two-DB model (a re-import never wipes progress), the
  study feed (5 item types, FSRS-6, an ordering engine), the topic Library with per-line stars, the concept
  map, a data-rich Progress screen, named resumable **sessions**, a **mock exam**, weak-spot drills, and
  custom decks.

## Where to look

- **The authoritative build spec:** [`BUILD_PROMPT.md`](BUILD_PROMPT.md) (read from disk, never memory).
- **The codebase map:** [`.claude/INDEX.md`](.claude/INDEX.md) — one line per file + how the system works.
- **The human log / resume truth:** [`JOURNAL.md`](JOURNAL.md).
- **Working agreement for contributors (and Claude Code):** [`CLAUDE.md`](CLAUDE.md).

## A note on content

The source PDFs and the compiled pack are **copyrighted** and are **not in this repository** (they're
gitignored). A fresh clone builds and runs; the app simply shows an "import a pack" screen until you supply
one. Nothing in git contains verbatim exam content.

## Build the app (quick start)

```bash
# JDK 17+ required (see app/README.md for the JAVA_HOME note on Windows)
./gradlew :app:assembleDebug            # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:verifyOfflineGuarantees  # the offline/no-background guard
./gradlew :app:testDebugUnitTest        # JVM tests, no device needed
```

Full details, the signing/keystore warning (§11d), and the offline-guard internals are in
[`app/README.md`](app/README.md).
