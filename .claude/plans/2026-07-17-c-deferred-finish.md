# Plan — finish ALL remaining deferred items

**Date:** 2026-07-17 · **Branch:** dev · **Scope:** the four deferred items after C11b.
Note: PATCH 02 marked Phase 1 "complete" — this is completion polish on the pack, not re-opening a stage.

## The four items, scoped (measured)

### 1. 3★ flashcard tail — **20,760 atoms** (importance exactly 3)
Completes the original C11 "Standard" flashcard scope (we shipped ≥4★ = 9,510; this is the 3★ tier).
Mechanical, same gen-flashcards pipeline, grounding-gated. **~12M CC tokens, ~5–6h** (≈104 chunks, 2-wide).
Roughly **triples** the flashcard deck (9,510 → ~30,270). Value: more study cards, but 3★ = mid-yield;
the app already filters by star, so users opt into them. → separate asset `flashcards3`, folded into the pack.

### 2. Staleness (post-C8) — **~1,725 PYQ MCQs** touching a guideline-sensitive concept (of 1,642 gs concepts)
The safety check: an old past-paper key that contradicts current guidance on a management/target/threshold
topic ⇒ set `mcqs.key_conflict=1` + note (never overwrite — the app shows "key disputed"). Needs judgment →
an Opus/CC subagent pass over the bounded candidate set. **Bounded, ~1–2h, clear value.** → re-pack.

### 3. Figures — **~1,000–1,500 real figures** across 35 digital textbooks (sampled: ~897 ≥200×200 in 8 files)
A NEW pipeline: extract embedded images (free, PyMuPDF) → dedup + size/aspect filter → **caption each with a
vision subagent** → link to atoms/pages by proximity → blobs to `dist/figures/` + populate `figures` table +
`figure_id` links. Heaviest + fuzziest (much of what's ≥200px is decorative/headers). **Copyright note:** unlike
the derived text atoms, figures are *verbatim copyrighted images* — consistent with the project's personal-use,
gitignored, never-distributed posture, but a real escalation worth an explicit OK.

### 4. Importance-scorer recalibration — the concept (71% 1★) / atom (45% 2★) tails
Run the Opus `importance-scorer` to AUDIT the histograms. **The playable feed is NOT degenerate** (generated
assets are 4–5★ by design; MCQs non-degenerate) and the long tails plausibly reflect reality (most of 37,795
concepts ARE minor). So the honest default is **audit-only** → document it's defensible; recalibrate + re-score
ONLY if the auditor finds true degeneracy (re-scoring cascades through every item + the whole pack).

## Proposed execution (pending the scope answers)
- **Do now (clear):** staleness review; the Opus importance audit.
- **Gated on your call:** the 20,760 3★ tail (big spend); figures (heavy + copyright); recalibrate-vs-audit-only.
- Order: importance audit first (could change stars → what's 3★); then 3★ tail + staleness in parallel; figures
  last (own extract→caption→link pipeline). Each re-packs + re-verifies reproducibility; one commit per item.

## Files
- `generate.py` (+`prep_flashcards3`, parameterized validate/collect), `pack.py` (load `gen_flashcards*.jsonl`;
  populate `figures` + `figure_id`; staleness `key_conflict`), new `figures.py` (extract/caption/link) if approved.
- Deferred-of-the-deferred if skipped stays flagged in state/JOURNAL. Reproducibility re-verified each re-pack.
