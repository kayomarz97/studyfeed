# StudyFeed — Kickoff / C0 plan (2026-07-15)

**Authority:** `BUILD_PROMPT.md` (repo root) is the spec. This file is my working plan for C0. The
durable progress tracker will be `compiler/.compiler_state.json`; the human log is `JOURNAL.md`.

## What the reconnaissance found (facts, not assumptions)

- **Fresh start** — no `BUILD_PROMPT.md` (now written), no state file, no git repo.
- **All three archives present at repo root**, on the same filesystem as the project:
  - `Papers.zip` (2.1 GB) → 20 loose PDFs + nested `…pastest for part 1 till Jan 2025 -34 files.rar`
  - `Qbank.rar` (609 MB, **RAR5**) → 16 subject-wise PDFs (Passmedicine 2025 Part 1)
  - `Textbook.zip` (777 MB) → 19 loose PDFs + nested `Passmedicine_High_Yield_Textbook_for_part_1_and_part_2.rar`
  - Real corpus ≈ **90+ PDFs, two nesting levels**. "Unbounded" is correct.
- **RAR toolchain:** was entirely missing → installed `unar` 1.10.1; verified it lists `Qbank.rar`
  (format "RAR 5") non-destructively. Tier-2 corpus is readable. ✅
- **`CLAUDE_CODE_SUBAGENT_MODEL` unset** → §4 haiku/sonnet/opus frontmatter routing will take effect.
- **`ANTHROPIC_API_KEY` set** in env → Batch API BYOK path is available (read from env, never committed).
- **Python 3.10.12** system / **uv 0.10.7** → will provision Python 3.11+ for the compiler venv via uv.
- **OCR/PDF present:** tesseract (eng+osd), pdftotext, pdfinfo, qpdf, pdfimages. Docling/PyMuPDF/embeddings
  not installed yet → pinned at scaffold.
- **Disk:** 37 GB free (disk 75% full). Archives ~3.5 GB; expansion likely ~4–8 GB + work/ + figures.
  Fits, but tight → lower `max_expanded_gb` 60 → 25 and watch it. `java` missing → needed only at A0 (app).

## Reconciliation with global rules (BUILD_PROMPT START-HERE step 3)

- **Agent name collisions:** NONE. Global agents = deep-reasoning, docs-researcher, mistake-tracker,
  production-fix-reviewer, secret-scanner, syntax-compatibility-checker, verifier. The 26 spec agents
  use disjoint names.
- **Model routing conflict:** NONE. Global routing table does not pin models; env override unset.
- **Rule contradictions:** NONE hard. Branch policy (dev, never main), secrets/.env, git-add-by-name all
  agree. One *philosophy* note: global prefers keeping work on the main session (agent spawns cost fresh
  context). Reconciled by the spec's own §10c/§10d — bulk work goes through the Batch API in Python, not
  subagents; agent *definitions* are role specs, spawned only at their triggers.

## Open questions for the gate (batched)

1. **Git remote** — default local-only; ask before any remote (§11a).
2. **Drop→tier mapping** — profile globs (`textbooks*.zip`/`*mcq*.rar`/`papers*.zip`) don't match actual
   names (`Textbook.zip`/`Qbank.rar`/`Papers.zip`). Proposed mapping is obvious; confirm (§1: never guess
   which PDF is what).
3. **Part 1 vs Part 2** — the Passmedicine High-Yield textbook covers Part 1 **and** Part 2; decide scope.

## C0 execution order (after approval)

1. `git init`; write §11b `.gitignore` **before first commit**; `git status --ignored` must show all
   three archives ignored; if any tracked → STOP. Fix profile drop globs to real filenames.
2. `mv` the three archives → `compiler/sources/inbox/`; SHA-256 each → `.compiler_state.json`.
3. **Durability layer first** (§10a/§10b): atomic state writer, `JOURNAL.md` appender, four hooks
   (SessionStart/PreCompact/SessionEnd/SubagentStop) + `.claude/settings.json`. Prove SessionStart
   injects state.
4. Spec files: `spec/SPEC.md`, `spec/schema.sql`, `spec/manifest.schema.json`, `spec/SCORING.md`.
5. `profiles/mrcp_part1.yaml` (globs fixed, `max_expanded_gb` lowered), compiler scaffold (uv, py3.11+),
   26 agent files in `.claude/agents/`.
6. Fill `CLAUDE.md` two sections; seed `.claude/INDEX.md`; `docs-researcher` → FSRS-Kotlin, Docling,
   Batch API into `PLAYBOOK.md`. Fetch MRCP Part 1 blueprint weights (else fallback + flag).
7. Print stage plan + routing table + thresholds + token-budget estimate. ⛔ → then C1.

## Hard gates ahead (no crossing without approval)
C1 classification · C2a zoning/back-matter · C4 grounding · C5 clustering · C6 PYQ/staleness ·
C9 importance · **C11 mass generation (token budget)** · keystore (A0) · any git remote / push.
