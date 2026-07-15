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
`compiler/`, `app/` — joined only by the pack format; first profile is MRCP(UK) Part 1. Runs on a
headless Hetzner box; tokens are spent at build time only — the shipped app has zero network.

> **`BUILD_PROMPT.md` (repo root) is the AUTHORITATIVE spec — read it before acting.** If you are a
> fresh session after a crash or compaction, do **not** start over: read
> `compiler/.compiler_state.json` (machine progress + `next_action` + in-flight batch IDs) and the
> tail of `JOURNAL.md` (human history), re-poll any batches, and resume. **State on disk is the
> truth, never the transcript.**

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

```bash
cd compiler && uv sync                                   # install compiler deps (provisions Python 3.11+)
uv run python -m studyfeed_compiler.config mrcp_part1     # validate the active profile
uv run python -m studyfeed_compiler.run --list           # THE quick check (offline/free): stages + resume state
uv run python -m studyfeed_compiler.run                  # resume the build from .compiler_state.json
```

⚠️ The compiler spends money only at **mass-LLM stages via the Batch API** (BYOK `ANTHROPIC_API_KEY`);
every such stage stops at a ⛔ budget gate first (§10e). Extraction, clustering, scoring and packaging
are pure Python and cost **zero tokens** — and a PDF must never enter a context window (§10c).
`uv sync` pulls heavy extraction deps (Docling/PyMuPDF); it's run before C2, not needed for `--list`.

**Branches:** work on and push `dev`; **never push `main`** — the user merges it.

---

## Everything else is global

- Golden loop (plan → read docs → check ledgers → build → verify → log stumbles),
  the non-negotiable rules, and the agent routing table live in `~/.claude/CLAUDE.md`.
- Project gotchas, verified commands, and API notes live in `.claude/PLAYBOOK.md`
  (read on demand — deliberately not auto-loaded, to save context).
- Stumbles in THIS repo → `mistake-tracker` logs them to the playbook's Mistakes ledger.
