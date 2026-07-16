# Plan — C12: validate + QA

**Date:** 2026-07-17 · **Branch:** dev · **Stage:** C12 (`validate.py`, stub → implement)
**Spec:** BUILD_PROMPT §C12 + §7 Acceptance + `qa-reviewer` (opus) role.

## Plain-language
Before we package the pack, audit everything we've built: does every card/question trace to a real
source, does every item have a 1–5 star, are past-paper questions all ≥4★, is anything a dead link, is
the star distribution sane, and does a re-run give identical scores? Produce one `build_report.md` a human
can skim, and have an Opus reviewer eyeball a random sample for grounding the arithmetic can't judge.

## The two layers
1. **Deterministic audit (`validate.py`, free, reproducible)** — the hard acceptance gates over the `work/`
   artifacts that feed packaging (the pack DB doesn't exist until C13):
   - **Grounding / dead links:** every generated flashcard/reel/MCQ has a valid atom `ref` + citations;
     dossier `related_mcq_idxs` resolve to real MCQs; `concept_edges` endpoints resolve; dossier
     constituent atoms resolve. Count danglers (must be 0).
   - **Importance ∈ 1..5 + populated signals:** every row of mcq/concept/atom importance + every generated
     asset (inherits importance) has `importance ∈ 1..5` and non-empty `signals`.
   - **PYQ floor:** every curated MCQ with `is_pyq` has importance ≥ 4 (§floors). Report violations (must be 0).
   - **Curated keys preserved:** every curated MCQ still carries its `answer`/key; none blanked/overwritten.
   - **Importance distribution:** histogram per item type; flag degeneracy (any bucket >45% or 5★ empty).
   - **PYQ coverage:** #PYQ MCQs, per-year, #concepts/atoms PYQ-linked.
   - **Dossier / blueprint coverage:** #concepts with a dossier, cross-subject count; subject distribution
     where known (only textbook/pearls carry a subject) vs profile blueprint weights.
   - **Reproducibility:** re-run `importance all` to temp + `dossier`/`generate validate` and diff → scores
     byte-identical (the §7 hard gate). Uses the PYTHONHASHSEED-robust determinism already in place.
   Output: `dist/build_report.md` (human-skimmable, pass/fail per check + counts) + a stats dict → JOURNAL.
2. **Opus QA spot-check (`qa-reviewer` subagent, CC usage — no API):** a stratified random sample (~30 items
   across atoms / dossiers / flashcards / generated MCQs) hand-checked for **semantic** grounding (does the
   card/answer actually follow from its cited atom — the thing token-overlap can't judge) + a review of the
   5★ band for misfires. Its findings append to `build_report.md`.

## Files
- `compiler/studyfeed_compiler/validate.py` (stub → implement): `audit()` + CLI; writes `dist/build_report.md`.
- Output: `dist/build_report.md` (dist/ is gitignored — the report quotes stems, stays local per §11).
- INDEX/JOURNAL/state on completion. Commit the code (report itself stays gitignored).

## Verify
- Run `audit()`; confirm 0 dead links, 0 PYQ-floor violations, importance all in-range, reproducibility
  byte-identical. Any real failure is reported honestly (not smoothed over).
- Opus spot-check returns and its grounding verdict is folded in.
