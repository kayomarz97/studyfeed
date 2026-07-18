# CLAUDE.md — Working Agreement for StudyFeed

> The rules Claude Code follows **every session** in this repo. Kept short on purpose: a long
> file gets half-ignored. This complements the global `~/.claude/CLAUDE.md` (plan-first,
> long-term fixes, secrets, git discipline, agents, ledgers) — it does NOT repeat it.
> Only the two sections below are project-specific; everything else is inherited.

---

## Project at a glance (the WHAT)

**StudyFeed** is a fully-offline, **exam-agnostic** MCQ study app. A Python 3.11 *compiler*
(uv-managed) turns copyrighted source PDFs into a versioned SQLite *content pack*; a generic
Kotlin/Jetpack-Compose Android app (Room + FSRS-6, AMOLED-dark) plays any pack, configuring its
subjects/tags/exam label at runtime from the pack manifest. Three decoupled parts — `spec/`,
`compiler/`, `app/` — joined only by the pack format; first profile is MRCP(UK) Part 1. Tokens are
spent at build time only — the shipped app has zero network.

**Status (2026-07-17): Phase 1 COMPLETE** — the compiler is done (C0–C13) and dormant;
`compiler/dist/pack_MRCP_PART1.db` is built and **imported to this laptop** (32,957 playable feed
items; checksum-verified against `manifest.json`). **Phase 2 (the app) is the live front: A0–A6 done;
`§8 A7` (the design pass) IN PROGRESS — cycle 1 built (dashboard home, loud-PYQ cards, study modes,
Settings).** Do not drive the compiler; do not invent pack data — a real pack exists, on disk.

**Two machines** (§0 `build_env`, amended by PATCH 02): the **compiler** ran on the headless Hetzner
box; the **app is built HERE, on the laptop** (Android Studio + a physical phone). The app must still
build from the CLI with no IDE-only config.

> **`BUILD_PROMPT.md` (repo root) is the AUTHORITATIVE spec — read it from disk before acting, never
> from memory.** **PATCH 02 (2026-07-17) rewrote it in place:** §7 and §13 and START HERE steps 1–4 are
> now *history — do not execute them* (they would re-run a finished kickoff). A fresh session doing app
> work goes to **§8 A1**.
>
> **Resume truth — ONE journal (merged 2026-07-17; `JOURNAL_APP.md` is gone):**
> - **`JOURNAL.md`** — the single human log. Its **⛔ standing orders sit at the top: read them
>   first.** Then its **Phase 2 — app** section, which is the resume truth for app work.
> - Machine resume truth for the compiler (dormant) stays `compiler/.compiler_state.json`.
>
> **State on disk is the truth, never the transcript.**

> ⚠ **The app must never contain an exam-specific string.** Every exam-facing label resolves from
> `pack_manifest` at runtime — that is what lets a future pack (MRCP Part 2, USMLE, …) play with **no
> code change** (§0 `multi_pack`). One hardcoded "MRCP" silently makes it a single-exam app.
>
> ⚠ **The feed has FIVE item types, not six (PATCH 04).** `mcq` · `flashcard` · `reel` · `interlink` ·
> `blob`. **There is no `image` type and never has been** — figures are **attachments** (`atoms.figure_id`
> 3,630 · `mcqs.figure_id` 1 · `topic_pages.figure_ids_json` 1,461) and render **inline on the card that
> owns them**. Any doc saying "six item types" or naming an Image card is stale. **Do not synthesise image
> feed rows** — that is the app inventing items the pack never authored (§runtime forbids generation).
>
> ⚠ **Nor may it hardcode which asset types exist — same contract, quieter failure (PATCH 03).**
> **Detect capability per pack, at runtime:** actual rows are authoritative; `counts_json` +
> `feature_flags_json` are the pack's own declaration (hint + cross-check). **The C11 trim was UNDONE —
> the shipped pack HAS blobs 2,997 · interlink 2,998 · figures 1,121, so use them** (any doc still
> saying these "ship empty" is stale — that text was corrected on 2026-07-17). But a *future* pack may
> ship without them: an app that assumes blobs exist because Part 1 had them **cannot swap its
> database**. Test both directions — full pack AND a synthetic trimmed pack, same binary.

> ⚠ **NEVER add a markdown library. The pack's `*_md` fields are medical text, and a renderer would
> corrupt lab values (PATCH 04).** Measured over 68,893 real fields: every `*italic*` is **multiplication**
> (of the form `<cell count> * 10^9/L`) and every `>` blockquote is a **threshold** (`> <N>:
> <interpretation>`). A markdown engine eats the asterisks → `<count> 10^9/L` — **a silently wrong lab value on a
> physician's revision card**. Exposure: `>` in **8.9%** of MCQ explanations, `<` in 6.1%, `*` in 3.6% of
> stems. The real vocabulary is only newlines + `- ` bullets + `1. ` lists (headings/links/code/fences:
> **zero**). Use the in-house renderer; treat `*`, `<`, `>` as **literal**. This looks like a tidy-up
> waiting to happen — **it is not**. It is about the corpus, not about effort.
>
> ⚠ **TWO corpora, TWO renderers — do not conflate them (PATCH 05, A4).** The rule above is for the
> **card `*_md` fields** (MCQ/atom → `PackMarkdown`). The **dossier `topic_pages`/`topic_sections`
> body_md** were compiler-**assembled** and use a richer set: `#`/`##` headings, `**bold**` (163k pairs),
> backtick `` `citations` `` — so they get a **separate in-house `DossierMarkdown`** (still NO library).
> **The hazard persists there** (measured 13 `N * 10^9` + 20 `DRB1*04:01` alleles + `<`/`>` thresholds):
> `DossierMarkdown` matches **`**bold**` double-star pairs ONLY** and keeps every lone `*`/`<`/`>`
> literal. Also: `topic_pages.body_md` **is** the full page = preamble + the same `## ` sections
> `topic_sections` holds **verbatim** — render the preamble + `topic_sections`, **never both** (or every
> fact doubles).
>
> ⚠ **No image/network loader (Coil, Glide) — figures are LOCAL files.** `BitmapFactory.decodeFile` + an
> LRU. Coil's standard artifact pulls OkHttp; the guard's denylist **would fail the build, correctly**.
>
> ⚠ **Never substring-match an AndroidManifest — PARSE it.** (Promoted from the PLAYBOOK ledger after
> the same mistake landed **twice**: A0, then A1.) This applies to *any* tool or language — `grep`, `rg`,
> Python `in`/`.count()`, `Select-String` — and to throwaway checks exactly as much as to committed code.
> The manifest's **comments are part of the file**, and ours deliberately quotes the very strings you'd
> search for (`android.permission.INTERNET`, `<service>`) inside a "DO NOT ADD, EVER" block. A substring
> hit **cannot tell a live element from a comment forbidding it**. Corollary: if a hand-rolled check
> contradicts `verifyOfflineGuarantees`, **suspect your check first** — the guard parses.

**Codebase map:** scan `.claude/INDEX.md` FIRST — it says what every file does and how the
system works. Navigate by the map, not by exploratory greps; keep it true in the same commit
as any file add/move/repurpose.

**Directories that matter** (point, don't describe):
- `spec/` — the pack **contract**: `schema.sql` (source of truth) + `SPEC.md` + `manifest.schema.json` + `SCORING.md`.
- `profiles/` — per-exam config (`mrcp_part1.yaml`). Everything exam-specific lives here, never in code.
- `compiler/studyfeed_compiler/` — the compiler; `run.py` is the resumable stage DAG, `state.py` owns `.compiler_state.json`.
- `compiler/sources/inbox/` — the dropped archives (gitignored); `compiler/work/` scratch + `compiler/dist/` outputs (both gitignored).
- `app/` — the generic Kotlin/Compose player (Phase 2).
- `.claude/` — `agents/` (27 subagents + model routing), `hooks/` (durability), `PLAYBOOK.md` (verified API notes + mistakes ledger).

## Commands (exact — don't guess)

**App (this laptop — the live front).** ⚠ `JAVA_HOME` is set at Windows **user** level → Android
Studio's JBR 21; there is **no `java` on PATH**, and a shell may not inherit it (Git Bash typically
does not). If Gradle says "JAVA_HOME is not set", set it for the invocation rather than hardcoding it
into the committed `gradle.properties` — that would break every other machine:

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME','User')   # only if the shell lacks it
.\gradlew.bat verifyOfflineGuarantees            # THE quick check — the offline/no-background guard
.\gradlew.bat assembleDebug                      # dev loop; APK → app/build/outputs/apk/debug/
.\gradlew.bat testDebugUnitTest                  # FSRS-6 tests (add --rerun-tasks; UP-TO-DATE proves nothing)
adb install -r app/build/outputs/apk/debug/app-debug.apk   # sideload over USB (§11c)
```

`verifyOfflineGuarantees` runs automatically on every `assemble<Variant>`: a violating APK (INTERNET
permission, any background component, a networking dep) **cannot be built**. Never disable it.

**Compiler (the Hetzner box — DONE, dormant; do not run these here).** §11b: this laptop must never
`uv sync` — ~9GB of tooling it has no use for.

```bash
cd compiler && uv sync                                   # install compiler deps (provisions Python 3.11+)
uv run python -m studyfeed_compiler.run --list           # THE quick check (offline/free): stages + resume state
uv run python -m studyfeed_compiler.run                  # resume the build from .compiler_state.json
```

⚠️ The compiler spends money only at **mass-LLM stages via the Batch API** (BYOK `ANTHROPIC_API_KEY`);
every such stage stops at a ⛔ budget gate first (§10e). Extraction, clustering, scoring and packaging
are pure Python and cost **zero tokens** — and a PDF must never enter a context window (§10c).

**Branches:** work on and push `dev`; **never push `main`** — the user merges it.

---

## Everything else is global

- Golden loop (plan → read docs → check ledgers → build → verify → log stumbles),
  the non-negotiable rules, and the agent routing table live in `~/.claude/CLAUDE.md`.
- Project gotchas, verified commands, and API notes live in `.claude/PLAYBOOK.md`
  (read on demand — deliberately not auto-loaded, to save context).
- Stumbles in THIS repo → `mistake-tracker` logs them to the playbook's Mistakes ledger.
