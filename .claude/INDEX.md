# INDEX.md — map of StudyFeed

> The codebase map Claude scans FIRST — instead of grepping around and burning tokens.
> One line per entry: what it is and when to touch it. **Update this file in the SAME
> commit whenever files are added, moved, or repurposed** — a stale map is worse than none.
> Authoritative spec: `BUILD_PROMPT.md`. Resume truth: `compiler/.compiler_state.json` + `JOURNAL.md`.

## How the system works (keep to ~10 lines)

Three decoupled parts joined only by the pack format. **(1) Profile + sources in:** an exam
`profiles/<exam>.yaml` plus three archives in `compiler/sources/inbox/`. **(2) Compiler:**
`studyfeed_compiler/run.py` drives a resumable 14-stage DAG (C0–C13) — unpack → file-dedupe →
zone/usage → extract+OCR+script-filter → highlights → atomize+ground → cluster/corroborate →
PYQ/trend/staleness → merge → alias+graph → **deterministic 1–5 importance** (`importance.py`) →
dossiers → generate assets (Batch API) → validate → package. Each stage checkpoints to
`.compiler_state.json` (via `state.py`) + `work/<stage>.done`; heavy LLM work goes through the
Anthropic **Batch API**, never Claude Code context (§10c/§10d). **(3) Output:** `dist/pack_<EXAM>.db`
(conforms to `spec/schema.sql`) + `figures/` + `manifest.json`, which the generic `app/` (Phase 2)
imports and renders offline. Everything exam-specific lives in the profile → pack → manifest.

## File map

| Path | What it does / when to touch it |
|---|---|
| `BUILD_PROMPT.md` | **The authoritative spec. Read before acting.** Verbatim, do not re-save. |
| `JOURNAL.md` | Human milestone log (prose; counts/decisions only, never book content). |
| `CLAUDE.md` | Project working agreement (2 sections filled; the rest is global). |
| `spec/schema.sql` | **Canonical content-pack DDL (source of truth).** `PRAGMA user_version` = schema version. |
| `spec/SPEC.md` | Human contract: 2-DB model, manifest, versioning, runtime invariants, progress-DB schema. |
| `spec/manifest.schema.json` | JSON Schema for `manifest.json` shipped beside each pack. |
| `spec/SCORING.md` | The deterministic 1–5 importance model + worked examples. |
| `profiles/mrcp_part1.yaml` | First exam profile: tiers, weights, floors, scripts, dedupe, zoning, aliases, tags. |
| `compiler/pyproject.toml` | uv project (py≥3.11); deps unpinned until `uv lock` + verify (pre-C2). |
| `compiler/studyfeed_compiler/run.py` | Resumable stage DAG + ⛔ gates. `--list` / `--from` / `--only`. |
| `compiler/studyfeed_compiler/state.py` | **Sole writer** of `.compiler_state.json` (atomic) + JOURNAL appender. |
| `compiler/studyfeed_compiler/config.py` | Loads a profile → typed access + paths + Batch-API model routing. |
| `compiler/studyfeed_compiler/importance.py` | The scoring engine (deterministic; per SCORING.md). *stub → C9.* |
| `compiler/studyfeed_compiler/{unpack,filededupe,classify}.py` | **C1 ingest — implemented.** Recursive unpack (prefer `unrar`), file-dedupe (scanned = hash-only), classify (kind/year/subject). |
| `compiler/studyfeed_compiler/scriptfilter.py` | §5g Unicode-block allowlist (drop Arabic/Bengali, keep Greek). Implemented. |
| `compiler/studyfeed_compiler/ingest/extract.py` | **C2 extract — implemented.** PyMuPDF text + fixed-width parallel OCR (tesseract) + script filter; per-work JSONL in `work/extracted/`. |
| `compiler/studyfeed_compiler/*.py` | Other stage modules (cluster, pyq, graph, importance, dossier, …). *stubs.* |
| `compiler/studyfeed_compiler/{ingest,highlights,generators}/` | Extractors / highlight harvest / asset generators. *stubs.* |
| `compiler/.compiler_state.json` | Machine resume truth: stage, next_action, decisions, archive hashes, batch IDs. Committed. |
| `.claude/agents/*.md` | 27 subagent defs + model routing (haiku mechanics / sonnet compiles / opus judges). |
| `.claude/hooks/*.py` + `settings.json` | Durability: SessionStart resume injection + PreCompact/SessionEnd/SubagentStop. |
| `.claude/PLAYBOOK.md` | Verified API notes (Batch API, Docling, FSRS-6) + mistakes ledger. Read on demand. |
| `.claude/plans/` | Dated plan docs. |

## Don't touch / generated

- `compiler/sources/inbox/**` — **copyrighted archives.** Gitignored; never commit; never hand-unpack.
- `compiler/work/**`, `compiler/dist/**` — generated + gitignored (extracted text, pack DB, figures, reports).
  Fix the profile/compiler and re-run; never hand-edit outputs. Reports quoting stems stay in `dist/` (ignored).
- Stub modules — safe to flesh out at their stage; keep the docstring's spec reference accurate.
