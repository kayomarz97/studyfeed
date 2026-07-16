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
| `compiler/studyfeed_compiler/bookusage.py` | **C3 emphasis harvest — implemented.** Digital annots = 0 (probed all PDFs) → `is_highlighted`=0; `book_flagged` from whole-source salience (pearls §profile) + per-topic markers in extracted text (SKELETON 🎤/▶️, key-point, pearl; noise-ceiling §5i) → `work/emphasis.jsonl` → feeds S_emphasis in C9. |
| `compiler/studyfeed_compiler/llm.py` | **C4 LLM ingest — implemented.** Batch API: atomize prose (Haiku) + parse MCQs (Sonnet; kind-aware, PassMedicine pages stitched); `submit`/`status`/`harvest` → `work/atoms.jsonl` + `work/mcqs.jsonl`. Plus `test-textbook`/`submit-textbook`/`harvest-textbook`: `_mcq_units_textbook` extracts MCQs from textbook-embedded qbanks (SsAcademy SKELETON) — detects textbook works with ≥20 `Question N` headers, splits Questions/Answers sections at number resets, PAIRS Q↔A, idempotent-appends to `mcqs.jsonl` (kind stays `textbook`; atoms kept). |
| `compiler/studyfeed_compiler/cluster.py` | **C5 clustering — implemented.** fastembed (ONNX) stem embeddings → exact block-wise cosine → union-find; distinct-**work** counts (§5b, via `load_mcqs_canonical` → `workgroups.canonical_map`) + boilerplate net (§5i). Exposes `finalize_clusters`/`write_cluster_outputs`/`embed`/`_UF` (reused by C7 dedupe). |
| `compiler/studyfeed_compiler/adjudicate.py` | **C5 corroboration Phase 2 — implemented.** Opus judges the ambiguous cosine band (≥0.89), verdicts cached by pair-hash, YES pairs merged into clusters → rewrites `mcq_clusters.jsonl`/`mcq_corrob.jsonl`. |
| `compiler/studyfeed_compiler/workgroups.py` | **Work-identity grouping (§5b).** Per-subject slices of one book share a canonical work_id (`canonical_map()`); redundant duplicate copies dropped. C5/C7 corroboration MUST apply it so one book's slices don't fake cross-corroboration. |
| `compiler/studyfeed_compiler/pyq.py` | **C6 PYQ analysis + trend — implemented.** Per-MCQ `is_pyq`/`pyq_years`/`pyq_last_year`/`S_pyq`/`pyq_trend` from the C5 clusters → `mcq_pyq.jsonl`. Staleness deferred to post-C8. |
| `compiler/studyfeed_compiler/dedupe.py` | **C7 atom dedupe/merge — implemented.** fastembed + hnswlib ANN (never all-pairs at 133k) → union-find near-dup merge (SIM 0.95, tuned to avoid transitive over-merge/laundering §5b); keeps best-sourced text, UNIONs citations (never drops a source), counts distinct **works** (canonicalised) → `atoms_merged.jsonl`. |
| `compiler/studyfeed_compiler/alias.py` | **C8a alias normalization (§5e) — implemented.** `canonical_key` (orthographic match key, never rewrites UK display) + `Aliases` synonym resolver from `profile.nomenclature.alias_hints`. `key(name)` = merge key: one entity = one node. |
| `compiler/studyfeed_compiler/graph.py` | **C8 concept graph — implemented.** Haiku Batch API extracts `(s,r,o)` triples (terse `N\|s\|r\|o`) → fold by alias key → typed grounded edges + networkx PageRank centrality → `concepts.jsonl`/`concept_edges.jsonl`; novel-alias proposals → `dist/alias_proposals.yaml` (⛔). Deterministic `investigated_by` disease→test direction fix (Sonnet-audit rec). `test`/`submit`/`harvest`/`build`. |
| `compiler/studyfeed_compiler/importance.py` | **C9 deterministic 1–5 importance — implemented.** 8 weighted signals (SCORING.md) → raw → bucket .80/.62/.42/.22 → hard floors. Scores MCQs (full signals), concepts (centrality + derived corrob + approx PYQ from MCQ-stem match), atoms (own corrob/freq/tier + inherited centrality/PYQ). `emphasis`=0 until C3. Outputs `{mcq,concept,atom}_importance.jsonl`. |
| `compiler/studyfeed_compiler/dossier.py` | **C10 topic dossiers — implemented.** Per canonical concept, gather ALL constituent atoms (via `graph_edges_raw.rationale`=atom.statement[:240]; `atom_importance` line-aligned with `atoms_merged`) into one long grounded `body_md` sectioned by subject (past-paper/qbank atoms are subject-less → trailing "Question-bank & exam notes" section) + concept-map connections + related MCQs (name/alias phrase-match). Scope = `profile.dossier` (≥min_atoms OR ≥min_subjects; excluded count logged). importance=MAX of constituent atoms (§5d); `stable_key`=sha256("topic\|"+canonical_key)[:16]. Deterministic (byte-identical across PYTHONHASHSEED), zero-cost, 100% grounded. figure/flashcard links deferred to C11/C13 → `work/dossiers.jsonl` (page + nested sections). |
| `compiler/studyfeed_compiler/*.py` | Remaining stage modules (validate, pack, …). *stubs.* |
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
