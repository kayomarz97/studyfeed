# Plan — C11b: finish the deferred assets (blobs + interlink)

**Date:** 2026-07-17 · **Branch:** dev · **Stage:** C11 (deferred tail) → then re-run C13
**Transport:** Claude Code `gen-blobs` / `gen-interlink` subagents (CC-plan usage, NOT API — standing directive).
**Spec:** BUILD_PROMPT §4 + §C11 + §5d propagation + schema `text_blobs`/`blob_quizzes`/`interlink_quizzes`.

## Plain-language
Two feed item-types we skipped: **blobs** (a short 60–150-word "read this" passage on a high-yield topic,
each with a quick quiz-back) and **interlink quizzes** (a question that tests a *connection* in the concept
map — "which drug is contraindicated in pregnancy?"). Generate both with Claude Code helpers, grounded, then
re-package the pack so they ship in the feed.

## Scope (matches the C11 "Standard" plan; importance-descending so partial runs keep the best)
- **Blobs — ~3,000**, from the top dossiers by importance (dossiers = concepts with real cross-subject
  material). Input per blob: concept title + subjects + the top ~12 grounded fact-bullets pulled from the
  dossier body. Output: `{ref=concept_id, title, body_md (60–150w), est_read_seconds, quiz{question_md,
  answer_md, opt_a..d, correct_idx}}`.
- **Interlink — ~3,000**, from `concept_edges`, EXCLUDING `associated_with` (too vague to quiz). Ranked by
  (both endpoints have a dossier, distinct_source_count, endpoint importance). Output: `{ref=edge_idx,
  question_md, answer_md}`.

## Importance (§5d) — with a documented deviation
§5d: blob = mean of covered concepts; interlink = mean of the two endpoints. But **concept importance has a
known-noisy tail** (71% 1★, flagged at C12 for recalibration), which would bury good blobs/interlinks. So I
use **effective concept importance = the concept's dossier importance where it has one (max-of-atoms),
else concept_importance** — the better signal for exactly the substantial concepts these assets cover.
- blob importance = eff_imp[concept] (its dossier's star), +1 if 5★-covered (cap 5).
- interlink importance = round(mean(eff_imp[src], eff_imp[dst])), clamped 1..5.
This is a judgment call (like the C12 fading-PYQ one) — documented so it can be overridden.

## Grounding gates (deterministic, like flashcards/reels/mcqs)
- **blob:** body shares ≥1 salient token with its source-facts; quiz has 4 distinct non-empty options +
  correct_idx∈0..3 + non-empty answer. Reject + log otherwise.
- **interlink:** question+answer together mention BOTH endpoint concept names (the edge it must test).

## Pipeline (reuse C11 machinery)
1. `generate.py`: add `prep_blobs` / `prep_interlink` (chunk importance-desc + manifest), `validate_blobs` /
   `validate_interlink` (grounding gate), `collect`. → `work/gen_blobs.jsonl`, `work/gen_interlink.jsonl`.
2. Two background Workflows (gen-blobs, gen-interlink subagents), concurrency-capped, resumable.
3. `pack.py`: populate `text_blobs` + `blob_quizzes` (blob_id FK) + `interlink_quizzes` (edge_id FK); add
   `blob` + `interlink` rows to `feed_items`. Update feature_flags has_blobs/has_interlink=true.
4. Re-run C13 `package` + `verify` (reproducibility must still hold) + integrity re-check.

## Files
- `compiler/studyfeed_compiler/generate.py` (+prep/validate/collect for blobs+interlink).
- `compiler/studyfeed_compiler/pack.py` (+3 tables + 2 feed types).
- Output `work/gen_{blobs,interlink}.jsonl` (gitignored). INDEX/JOURNAL/state on completion.

## Verify
- Pilot each prompt (small chunk) before the full fan-out (grounding + format).
- After collect + re-pack: reproducibility still `true`; 0 dangling blob_quizzes/interlink FKs; every new
  feed_item resolves; importance 1..5; counts reconcile. `verifier` after code changes.
