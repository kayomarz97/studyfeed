---
type: project
tags: [language/kotlin, language/python, status/active, domain/medical-education]
github: studyfeed-public
related: ["[[MRCP Gamify]]", "[[mrcp-textbook-v2]]", "[[RAGnosis]]"]
---
# StudyFeed

## What it is
A fully-offline, exam-agnostic MCQ study app for medical exams. A Python "compiler" turns copyrighted source PDFs (past papers, qbanks, textbooks) into a versioned SQLite "content pack", and a generic Kotlin/Jetpack-Compose Android app plays any pack, learning its subjects, tags, and exam name at runtime from the pack's manifest. All AI/token spend happens once at build time; the shipped app has zero network access.

Technically it is three decoupled parts joined only by a pack-format contract: `spec/` (the DDL + manifest schema + scoring model), `compiler/` (a resumable 14-stage Python DAG using the Anthropic Batch API, BYOK), and `app/` (Kotlin, Room + FSRS-6 spaced repetition, AMOLED-dark, two-DB model). The first and only shipped profile is MRCP(UK) Part 1.

## What it entails
- `spec/` — the contract: `schema.sql` (canonical pack DDL, `PRAGMA user_version`), `manifest.schema.json`, `SPEC.md` (2-DB model, versioning, runtime invariants), `SCORING.md` (deterministic 1–5 importance).
- `compiler/` — uv-managed Python ≥3.11 project. `studyfeed_compiler/run.py` drives stages C0–C13 (unpack → dedupe → extract/OCR → atomize → cluster/corroborate → PYQ/trend → merge → alias/graph → importance → dossiers → asset gen → validate → package). Deps: pymupdf, docling, anthropic (Batch API), fastembed, hnswlib, networkx. Resume truth in `.compiler_state.json`.
- `app/` — Android app, `applicationId dev.kayomarz.studyfeed`, compileSdk 36 / minSdk 26. Compose UI (feed, dossiers/library, concept map, stats, settings), Room-owned progress DB, cross-DB ATTACH read path, FSRS scheduler, in-house safe markdown renderers, figures via BitmapFactory.
- `build-logic/` — an included Gradle build with an "offline guarantees" plugin: any APK that adds network/background permissions or denylisted deps **fails the build**.
- Docs: `BUILD_PROMPT.md` (authoritative spec, ~139KB), `JOURNAL.md`, `.claude/INDEX.md`, `profiles/mrcp_part1.yaml`.
- Stack: Kotlin 2.4.10, AGP 9.3.0 / Gradle 9.5.0, Compose BOM 2026.06.01, java-fsrs 1.0.0; Python 3.11 + Anthropic Batch API for the compiler.

## How it works
Build time (Phase 1 complete): a profile YAML + source archives feed the Python compiler, which runs a checkpointed DAG, sending heavy LLM work through the Anthropic Batch API, and emits `dist/pack_<EXAM>.db` + `figures/` + `manifest.json`. The MRCP Part 1 pack is built (32,957 playable feed items across mcq, flashcard, reel, blob, interlink). Runtime (Phase 2, live): the app imports a pack directory via SAF, copies it into app storage, gates it on schema version, opens the pack read-only, keeps a separate read-write `progress_<EXAM>.db` (Room) and ATTACHes the pack beside it — so re-importing content never wipes spaced-repetition progress. A pure `FeedScheduler` composes the default feed. Everything exam-specific comes from the pack manifest; no runtime generation.

## Change impact / blast radius
- **Upstream (this depends on):** copyrighted source archives in `compiler/sources/inbox/` (SsAcademy/MRCP material); the Anthropic Batch API (build time only, BYOK); pinned Android/Gradle/Kotlin toolchain; java-fsrs.
- **Downstream (depends on this):** [[MRCP Gamify]] (its brain-map vault is built from this compiled pack); [[RAGnosis]] (benchmark built from the pack); the shipped Android APK; the `studyfeed-public` release repo.
- **Parallel / shared (can break if touched):** `spec/schema.sql` and `manifest.schema.json` are the contract between compiler and app — a change there breaks both sides and requires a `schema_version` bump; stable ids (`item_type,item_id`) are a durability contract that must survive rebuilds. **This folder and [[MRCP Gamify]] are closely related copies of the same product line — reconcile before editing either.**
- **⚠️ Danger zones (touch with care):** the in-house markdown renderers (`PackMarkdown.kt`, `DossierMarkdown.kt`) — swapping in a real markdown library would silently corrupt lab values (`5*10^9`, `>2.5`, `DRB1*04:01`); `ProgressDatabase` — never add `fallbackToDestructiveMigration` (drops user progress); the `build-logic` offline guard (any accidental network dep fails CI-by-design); the release keystore lives **outside** the repo — losing it means no in-place upgrade and wiped progress. No hardcoded secrets found.

## Related projects
- [[MRCP Gamify]] — same/adjacent StudyFeed product line, and the Obsidian brain-map vault built from this project's compiled MRCP pack.
- [[mrcp-textbook-v2]] — separate VERBATIM vault built from the same SsAcademy MRCP PDF source family.
- [[RAGnosis]] — RAG benchmark built read-only from the compiled pack.

Note: working origin is the private `kayomarz97/studyfeed`; public release repo is `studyfeed-public`. The APK and pack DB are gitignored and never committed.
