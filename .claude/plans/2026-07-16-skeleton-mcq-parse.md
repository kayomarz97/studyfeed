# Plan — Parse the SKELETON qbank MCQs (before C7)

**Date:** 2026-07-16 · **Branch:** dev · **Author:** Claude (with user)
**Trigger:** free MCQ-signature scan found a ~2,985-question qbank misfiled as `textbook`.

---

## Plain-language summary

Inside `Textbook.zip` there are 14 files (Cardiology (C), Neurology (C), … Oph+Psy (Online))
that look like textbooks but are really one **question bank** — *"SKELETON of MRCP Part 1"*
(SsAcademy, 2023). Every item is a numbered question with five options, a **printed answer key**
("Correct Answer: B- Flecainide") and an explanation. Because they were tagged "textbook", the
earlier MCQ step skipped them, so ~2,985 ready-made, answer-keyed questions were never turned into
playable MCQs. They *were* already broken into ~40k knowledge-facts (atoms), and those stay.

**The user's instruction:** keep these as textbooks-that-also-have-MCQs — parse the questions
**and** keep the textbook/knowledge content; don't relabel the whole file to "qbank" and lose prose.

## What the evidence shows (so the plan is honest)

- Contiguous mid-file runs are wall-to-wall Q&A (Question 68, 72, 74…), each with A–E options.
- The non-Q pages are: front matter (editor/copyright), a per-subject "Topic Overview" **table of
  contents**, and explanation spillover — **not** standalone textbook chapters.
- Existing atoms from these files are clean facts (~4% are vignette fragments): the knowledge layer
  is already captured well. So "textbook data is parsed" is already substantially TRUE.
- Net gap = the **structured MCQ layer only** (~2,985 items). Size ≈ ⅓ of the current 9,576 corpus.

## Design decisions (technical)

1. **No reclassification.** Keep `kind=textbook` for the 14 works (wid 80,81,83–92,95,96).
   - Preserves atom routing (`build_atomize` = {pearls, textbook}) → knowledge layer intact.
   - Directly honors "they are mainly textbooks but with MCQs".
   - §5a `key_trust` is **per-item**, not per-source — printed keys → qbank-level trust regardless.
2. **New MCQ segmentation for textbook-qbanks** in `llm.py::_mcq_units_textbook` (separate from the
   existing `_mcq_units`, which is left byte-for-byte unchanged → zero regression):
   - For `textbook` works only, count `^Question \d+` headers; treat as MCQ-bearing **iff ≥ 20**
     (includes the 14 SKELETON files; excludes prose textbooks like Step-to-MRCP which have ~0).
   - **REFINED after probing (the layout is not self-contained):** each file = a 6-item demo (dropped)
     + a QUESTIONS section (Q1..N: stem+options) + an ANSWERS section (Q1..N: `Correct Answer` +
     explanation), N matching. Split the header stream into sections at each question-number RESET,
     classify each as answers (when `Correct Answer` dominates) vs questions, then **PAIR question K
     with answer K** by number → every unit is complete (stem+options+key+explanation).
   - page_ref = `<stem-page>-q<N>` (unique — several Qs share a page; `cluster._page_int` recovers the
     numeric page). Collision-safe suffix guards the rare OCR/section duplicate.
3. **Targeted, append-only run.** New `build_mcq_textbook()` + a `submit-textbook`/`harvest`
   path submits ONLY the new units. Existing 9,576 MCQs untouched; harvest appends to `mcqs.jsonl`.
4. **MCQ_SYS prompt — additive tweaks only:** recognise explicit "Correct Answer: <letter>- <text>"
   as the key; strip "SKELETON of MRCP PART-1"/"System: <subject>" running headers and 🎤▶️ emoji.
5. **Validate before spend:** strided ~20-unit sample batch (~$0.20) → confirm one-Q-per-unit
   segmentation, answer-letter correctness, no merges; re-extrapolate full cost.
6. **⛔ Budget gate:** report exact unit count + extrapolated $ (user pre-authorized ~$25–30) →
   submit full batch → poll to completion → harvest.
7. **Re-run C5 then C6** (`cluster.py`, `pyq.py`) to fold new MCQs into corroboration + PYQ.
   `cluster.py` applies `work_groups.json` canonical_map → the 14 slices count as ONE work (80),
   never faking 14-way corroboration.
8. **Optional polish (propose, not block):** drop the ~4% vignette-fragment atoms from the SKELETON
   works for atom quality.
9. **Checkpoint state + commit (dev).** Then continue to **C7** (atom dedupe/merge).

## Cost (grounded in C4 actuals)

- C4 mcq: $0.0090/unit (17.67M in + 8.11M out over 9,697 units, Sonnet Batch 0.5×).
- SKELETON ≈ 2,985 units → **~$27** Sonnet Batch. No atomize cost (already atomized).
- C5 re-cluster adjudication of new borderline pairs: **~$2–4**. Total added spend ≈ **~$30**.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Over-spend | validate + extrapolate on a 20-unit sample before full submit; hard ⛔ gate |
| Bad segmentation (multi-Q pages, OCR "Question" typos) | within-page split; ≥20-header guard; null-parse drops fragments downstream |
| Fake corroboration from 14 slices | `cluster.py` applies canonical_map → one work (80) |
| Corrupting existing MCQs | separate build path; append-only harvest; no re-submit of past_paper/qbank |
| key_trust | assigned downstream (all currently None); printed keys → high trust at §5a step |

## Sequencing

parse SKELETON MCQs → **C5 re-cluster** → **C6 re-PYQ** → checkpoint/commit → **C7**.
(C7 operates on atoms, which already include SKELETON, so it is independent of this MCQ work.)

## Files touched

- `compiler/studyfeed_compiler/llm.py` — NEW `_mcq_units_textbook` + `_textbook_qbank_wids` +
  `build_mcq_textbook`; MCQ_SYS tweaks; CLI `test-textbook`/`submit-textbook`/`harvest-textbook`.
  Existing `_mcq_units`/`build_mcq` untouched.
- `compiler/studyfeed_compiler/cluster.py` — wired `workgroups.canonical_map()` into `cluster()` so
  the 14 SKELETON slices collapse to work 80 (the C5 decision required this; it was never actually
  implemented — no MCQ-bearing work had needed it until now). `pyq.py` already applied the map.
- Re-run (no edits): `pyq.py`. State/journal via `state.py`. INDEX.md unchanged (no file add/move).

## VALIDATED — 2026-07-16 (offline + $0.20 sample, before full spend)

- Detector picks **exactly** the 14 SKELETON works (216–728 headers each; prose textbooks excluded).
- Existing `build_mcq` unchanged at **9,697** units → no regression to the 9,576 live MCQs.
- **2,913 paired units**, 0 duplicate custom_ids, **100% complete** (options + printed key present).
- 20-unit paid sample: **20/20 parsed, 0 errors**, correct answer letters (e.g. Paget's→Risedronate),
  all 5 options extracted, running headers stripped.
- **Cost re-extrapolated from real tokens: ~$12.72** (1,057 in / 371 out per unit) — HALF the estimate,
  well under the ~$25–30 authorised. Full batch `msgbatch_01Tt89xGACc9YsDqB7QfTrnx` submitted.
- Harvest is idempotent (drops any prior SKELETON `source_id` rows, then appends) → safe to re-run.
