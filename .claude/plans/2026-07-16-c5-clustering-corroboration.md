# Plan — C5: Clustering + Corroboration

**Status:** proposed, awaiting approval on D1 (embedding) + D3 (scope). D2 (adjudication $) will come back as a real number before any spend.
**Stage:** C5 (`run.py` DAG), `gate=True`. Prereq C4 ✅ (`work/mcqs.jsonl` = 9,576 MCQs, `work/atoms.jsonl` = 159,689 atoms).

## Why this stage exists (plain → technical)
**Plain:** This computes "how many *different books* independently ask this question / assert this fact." A question asked in 5 different sources matters far more than one repeated 20× in a single qbank. That "asked at multiple sites" number is the single strongest signal in your importance ranking.
**Technical:** Populates the `S_corrob` signal (§5b, SCORING.md): `log1p(distinct_work_count)/log1p(N_works)` scaled by `distinct_publisher_count`. Counts **distinct works, not files** (two editions = one voice). Also flags publisher **boilerplate** (§5i) so a shared front-matter example can't fake max corroboration.

## Inputs → Outputs
- **In:** `work/mcqs.jsonl` (each row: `source_id`=work_id, `page_ref`, `stem`, options, `answer`, `explanation`), `work/classification.jsonl` (work_id→kind/publisher/year).
- **Out:** `work/mcq_clusters.jsonl` (one row per cluster: member indices, `member_count`, `distinct_work_count`, `distinct_source_count`, `distinct_publisher_count`, `pyq_years`, `is_boilerplate`) + a per-MCQ `distinct_work_count` sidecar for C9 scoring. Atomic writes; C5 marked done in state.

## Algorithm (`cluster.py` — per BUILD_PROMPT lines 216/275)
1. Normalize each stem (lowercase, collapse whitespace, strip residual UI).
2. **Embed stems locally** (no API) → one vector each.
3. ANN index → candidate near-dup pairs by cosine.
4. Pairs **≥0.92** = same question → union-find → clusters. Pairs in the **ambiguous band 0.82–0.92** → `cluster-adjudicator` (Opus) yes/no, **cached by pair-hash** (re-runs free/reproducible).
5. Per cluster, count distinct works / sources / publishers / members / pyq_years.
6. **Boilerplate net (§5i):** flag `is_boilerplate` only when members are near-**verbatim** across works **and** share the same early page position. Do **not** flag on "appears in many books" alone — the same fact in *different words* in *body* content is **real corroboration and must count**. Exclude boilerplate from all counts.
7. Emit clusters + per-MCQ `distinct_work_count`.

## Decisions
### D1 — how to embed (a dependency choice) — NEEDS YOU
- **(A) Light semantic (recommended):** ONNX embedder (e.g. `fastembed`, BAAI bge-small ~130 MB, CPU, seconds for 9.5k stems). Semantic → catches "same fact, different words," which the spec explicitly requires. Much lighter than torch.
- **(B) Standard semantic:** `sentence-transformers` (all-MiniLM). Same quality, but pulls in torch (~2 GB) on the Hetzner box.
- **(C) Lexical only:** MinHash/TF-IDF. Lightest, no model — but misses paraphrases, so it under-counts real corroboration. Spec wants semantic.

### D2 — adjudication token cost (the ⛔ C5 gate) — I bring the number, no spend yet
Only the 0.82–0.92 band goes to Opus. I'll embed first, **count that band + estimate $ (Batch API, cached)**, and show you before spending — a real budget gate. Likely small; corpus-dependent.

### D3 — scope now — NEEDS YOU (I recommend MCQs-only)
- **(Recommended) MCQs only** (9,576): the primary §5b signal. Atom near-dup dedup/merge is a *separate* stage (C7, `dedupe-merger`), so atom `distinct_work_count` is computed there.
- (Alt) Also cluster atoms now (159,689 vectors) — bigger compute, overlaps C7.

## Durability
`cluster.py` writes outputs atomically; adjudicator verdicts cached by pair-hash; C5 checkpointed in `.compiler_state.json`. Idempotent re-run. INDEX.md updated in the same commit (add `llm.py`=C4, `cluster.py`=C5).

## Not in scope / follow-ups
- Optional MCQ null-retry (121 unparsed at C4) — cheap, can fold in later.
- INDEX.md currently omits `llm.py` — will fix in the C5 commit.
