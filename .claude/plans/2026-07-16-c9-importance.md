# Plan — C9: deterministic 1–5 importance scoring

**Date:** 2026-07-16 · **Branch:** dev · **Stage:** C9 (importance.py, stub → implement)
**Spec:** spec/SCORING.md + BUILD_PROMPT §5/§C9 + profile.importance + schema `*.importance`.

## Plain-language

Give every question / fact / concept a 1–5 star rating so the app can lead with what matters. The
rating is **computed, never guessed** — same inputs → identical stars. Eight signals, weighted (PYQ is
heaviest at 33%), summed to 0–1, bucketed to 1–5, then hard floors override (a multi-year past-paper
topic is forced to 5; an uncorroborated single-source claim is capped at 3).

## The constraint (why scope matters)

Two of the eight signals depend on data that isn't materialised yet:
- **emphasis (11%)** — needs C3 highlights, which the build SKIPPED (completed = C0,C4,C5,C6,C7,C8).
- **pyq/centrality/trend for atoms & concepts** — needs an atom↔concept↔MCQ linkage that no current
  file carries. MCQs have PYQ (mcq_pyq); atoms/concepts don't inherit it without that link.

So MCQs are ~83% signal-covered and scorable now; atoms/concepts are ~30–43% covered and would score
**artificially low** (no atom could reach 5★) — a misleading ranking, not a neutral one.

## Signal formulas (SCORING.md)

- `S_pyq` = normalised `s_pyq` (Σ exp(-0.15·(2026-yr))) — MCQ only.
- `S_corrob` = log1p(distinct_work_count)/log1p(N_works), scaled by distinct_publisher_count.
- `S_freq` = log1p(member_count), damped (÷log1p(p95)).
- `S_emphasis` = 0 until C3.  `S_blueprint` = subject weight/max.  `S_centrality` = PageRank (concepts).
- `S_tier` = max tier_weight.  `S_trend` = rising 1 / steady .5 / fading 0.
- raw = Σ wᵢ·Sᵢ → bucket on .80/.62/.42/.22 → floors.

## Floors (profile.importance.floors)

pyq MCQ ⇒ ≥4 · multi-year PYQ (≥2 yrs, ≥1 in 10y) ⇒ 5 · ≥3 works + highlighted ⇒ ≥4 ·
zero corrob & zero PYQ ⇒ ≤3 · fading ⇒ ≤3.

## Proposed phasing (needs a scope decision)

- **v1 (now, free):** score **MCQs** fully (all playable content ranked) + histogram + floors. This is
  the highest-value output and the one with clean signals.
- **v2 (follow-up):** build the atom↔concept linkage (from graph_edges_raw: atom statement → its
  concepts → inherit centrality; concept PYQ via MCQ-stem concept match) → score atoms + concepts.
- **v3 (optional):** run C3 highlights to light up the 11% emphasis signal, then rescore.

## Files

- `compiler/studyfeed_compiler/importance.py` (stub → implement). Reuses config (weights/thresholds/
  floors/subjects/tiers), mcq_pyq.jsonl, mcq_corrob.jsonl, mcqs.jsonl, atoms_merged.jsonl, concepts.jsonl.
- Output: `work/mcq_importance.jsonl` (+ atoms/concepts in v2); histogram → stats + pack_manifest later.
