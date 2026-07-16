
## 2026-07-15T16:57:15Z  [C0]

**C0 start.** Fresh repo. Corpus: Papers.zip 2.1GB (20 loose PDFs + nested Pastest .rar ~34 files, T1); Qbank.rar 609MB RAR5 (16 subject-wise Passmedicine 2025 PDFs, T2); Textbook.zip 777MB (19 loose PDFs + nested Passmedicine High-Yield .rar, T3-4). unar 1.10.1 reads RAR5. CLAUDE_CODE_SUBAGENT_MODEL unset. Decisions: remote=kayomarz/private; drop-mapping confirmed; Part1/2 scope deferred to C2. Archives moved root->inbox and hashed.

## 2026-07-15T17:05:29Z  [C0]

**Durability layer built + proven.** state.py (atomic temp+os.replace) + JOURNAL appender; four hooks (_hooklib, session_start/pre_compact/session_end/subagent_stop) + settings.json. Verified: SessionStart(source=resume) reconstructs stage/decisions/archives/journal-tail from disk alone (1286 chars, <10k cap); SubagentStop routes full result to gitignored work/ jsonl, JOURNAL gets metadata only (§11b). Hooks engage at next session start.
- subagent `docs-researcher` (a4cd89e96a92b1983) finished — 5127 chars -> work/subagent_results.jsonl

## 2026-07-15T17:20:23Z  [C0]

**C0 scaffold complete.** spec/ contract (schema.sql+SPEC+manifest.schema+SCORING), profiles/mrcp_part1.yaml (validated Σ|w|=1.0), compiler scaffold (config+run DAG+28 module stubs), 27 agents (haiku6/sonnet18/opus3), durability proven, PLAYBOOK API notes recorded. FSRS-Kotlin found broken -> recommend java-fsrs 1.0.0. Blueprint weights on uniform fallback. Next: C0 gate (confirm push) -> C1.
- subagent `secret-scanner` (afde4cad6defbca19) finished — 6934 chars -> work/subagent_results.jsonl

## 2026-07-15T17:30:29Z  [C0]

**C0 gate approved.** Private remote kayomarz97/studyfeed created; dev pushed (main absent, user-merged). Secret-scan CLEAN (0 secrets, 0 copyrighted artifacts in tree/history). Decisions: FSRS=java-fsrs 1.0.0 (FSRS-Kotlin broken, BUILD_PROMPT line corrected); blueprint=fetch official weights now.
- subagent `` (a0ab00ad3d08b5b61) finished — 28 chars -> work/subagent_results.jsonl
- subagent `` (a8c1ffda5c78909ca) finished — 33 chars -> work/subagent_results.jsonl
- subagent `` (a1686b1640fc22459) finished — 33 chars -> work/subagent_results.jsonl

## 2026-07-15T17:35:11Z  [C0]

Blueprint weights set from MRCP(UK) Part 1 2026 (Cardiology 14, Clinical sciences 25, Clinical pharm 15, Neuro+ophth 18, Haem+onc 15, Geriatric+pall 12, ...; sum=200), cross-checked vs Federation anchors. BUILD_PROMPT FSRS line corrected to java-fsrs 1.0.0.

## 2026-07-15T17:37:14Z  [C0->C1]

**C0 COMPLETE + pushed** (kayomarz97/studyfeed, dev). Foundation: durability (proven), spec contract, validated profile w/ 2026 blueprint weights, compiler scaffold, 27 agents, PLAYBOOK API notes. 9 commits. Ready for C1 (the ranking-critical unpack->dedupe->classify stage).
- subagent `` (a33e2614a9d25be27) finished — 2 chars -> work/subagent_results.jsonl
- subagent `secret-scanner` (aec5c8a194eec42c0) finished — 1754 chars -> work/subagent_results.jsonl
- subagent `` (a23c55b13b02fd4c6) finished — 19 chars -> work/subagent_results.jsonl
- subagent `` (afecca0c364c9aea0) finished — 255 chars -> work/subagent_results.jsonl

## 2026-07-15T17:55:30Z  [C1a]

**C1a unpack done.** 107 PDFs (Papers 55, Qbank 16, Textbook 36), 2 nested RARs expanded, 5.03GB, 0 issues. TRAP: unar 1.10.1 silently truncated 3 PDFs (incl. an Aug-2022 paper) — installed unrar (RARLAB) and made unpack.py prefer it; all 3 recovered full (461/183/201pp). Logged to PLAYBOOK ledger.
- subagent `` (add29dceeccd2240e) finished — 277 chars -> work/subagent_results.jsonl

_[session ended: other at 2026-07-15T18:03:38Z]_

_[session ended: other at 2026-07-15T18:03:38Z]_

_[session ended: prompt_input_exit at 2026-07-15T18:03:56Z]_
- subagent `` (a1f864cbabc0ad2d4) finished — 246 chars -> work/subagent_results.jsonl

## 2026-07-15T18:06:58Z  [C1b]

**C1b file-dedupe done.** 107 files -> 99 canonical works. 8 exact-SHA collapses (papers 27-34 loose+nested identical). 0 fingerprint/fuzzy/cross-drop merges. TRAP: past papers are SCANNED (no text) -> pdftotext empty -> all 34 dated papers falsely merged into 2 by empty fingerprint (would erase PYQ signal). Fixed: scanned files (text<30 words) dedup by exact hash only; content dedup deferred to C5. 38 scanned (37 Papers, 1 Textbook). Logged to ledger.

## 2026-07-15T18:11:34Z  [C1c]

**C1c classify done.** 99 works: 47 past_paper (years 2015-2026, 2 undated), 16 qbank (subjects mapped; Palliative->Geriatric, Ophthalmology->Neurology folds flagged), 36 textbook (32 textbook + 4 pearls). Flags: 1-page Arabic intro (مقدمة) to exclude; 'Sample Questions' undated; Jan-2026 recall (recent, has key). At C1 gate.

## 2026-07-15T18:34:28Z  [C1]

**C1 COMPLETE (gate approved).** Decisions: exclude Arabic intro; Palliative+Ophthalmology kept SEPARATE (16-subject taxonomy, sum=200); textbook kinds accepted (4 pearls/32 textbook); reliability=recall. Final: 98 active works (46 past_paper 2015-2026, 16 qbank, 36 textbook/pearls) + 1 excluded. Next: C2 extract/OCR.

## 2026-07-15T19:05:39Z  [C2]

**C2 smoke PASSED + full extraction launched.** PyMuPDF text + tesseract OCR both clean. Toolchain: pymupdf/pillow/pyyaml in uv venv (py3.14). OCR tuned 16.8->0.77s/pg (fixed-width render + fork ctx + OMP=1). Full run: 98 works, 8961 OCR pages (~2h), 34204 text pages, checkpointed per work. Findings: most content is Passmedicine/Pastest MCQ-format (Q+A+peer-stats) across all drops; flagged tier notes for C9. Extraction running in background.
- subagent `` (a6a704cffb25cff20) finished — 68 chars -> work/subagent_results.jsonl

## 2026-07-15T19:37:32Z  [C1-fix]

**C1 classification corrected at user's confirm.** (1) VERIFIED: Papers=Pastest MCQs w/ keys, Tier 1 recall stays. (2) I was WRONG re qbank: Textbook 'N-Subject' files are the Passmedicine High-Yield TEXTBOOK (prose: 'Heart sounds: S1...'), 0 question-markers vs Qbank copy's 38. Caught by drop-qualified pdftotext before changing. Reclassified 16 High-Yield files textbook->pearls (consistent w/ combined). Logged to ledger.

## 2026-07-15T19:42:43Z  [CHECKPOINT]

**CHECKPOINT — context near full, pre-compaction. C2 extraction in progress.**

STATUS: Full text extraction RUNNING in background (~1.5h total; checkpointed per-work to compiler/work/extracted/; 15/98 done).
Toolchain: uv venv (py3.14) + pymupdf/pillow/pyyaml; OCR = tesseract on fixed-width 2500px PyMuPDF render, fork context, OMP_THREAD_LIMIT=1 (~0.77s/pg x 4 cores); text = PyMuPDF; §5g script-filter applied per page. Output: work/extracted/<wid>__<name>.jsonl (per-page {page,method,chars,text}). Extraction is content-only, UNAFFECTED by classification edits.

CLASSIFICATION (final, 98 works): 46 past_paper (scanned Pastest recalls, years 2015-2026, reliability=recall; KEY-BEARING curated MCQs — each ~2 pages/question with answer+explanation+peer-stats; §5a gives their printed keys qbank-level trust); 16 qbank (Passmedicine 2025 subject files, real text MCQs); 20 pearls (16 Passmedicine High-Yield Textbook per-subject PROSE + the combined High-Yield file + '100 commonly tested facts' + 'Essential Revision Notes' + 'Faraz pearls'); 16 textbook (the '(C)'/SKELETON prose series + 'Step to MRCP' scanned + 'The Only MRCP Notes' + 'Oph+Psy' + 'Geriatric Medi Online'); 1 excluded (Arabic 1-page intro). Taxonomy = 16 subjects (Palliative + Ophthalmology standalone), blueprint weights sum 200.

MISTAKE (documented in full, per user request; also in PLAYBOOK ledger, seen 1x):
- WHAT: During the C2 smoke I reported the Textbook drop's 'N-Subject.pdf' files (e.g. 16-Clinical Science.pdf) as 'Passmedicine QBANK exports' and asked the user to reclassify them textbook->qbank. The user, trusting my description, confirmed 'correct, reclassify'.
- ROOT CAUSE: 16-Clinical Science.pdf exists in BOTH drops. My smoke's name-keyed resolver (_resolve, first-match-by-name) picked the QBANK drop's copy (which is questions: 'Question 114 of 575... correct answer C5') and I misattributed that content to the TEXTBOOK drop.
- CATCH: Before making the change I verified drop-qualified via pdftotext on the exact dedupe_canonicals path. Textbook-drop 16-Clinical Science = 0 'Question N of' markers, pure prose ('Textbook index... Heart sounds: S1 is caused by closure of mitral and tricuspid valves...'). Qbank-drop copy = 38 markers. So the Textbook files are the Passmedicine High-Yield TEXTBOOK (prose), NOT qbank.
- CORRECTION: Did NOT reclassify to qbank (would have corrupted tiers + wrongly routed prose to MCQ-parsing). Instead reclassified the 17 High-Yield files (16 per-subject + combined) textbook->PEARLS (accurate + consistent). Papers (obs 1) verified correct: Tier 1 recall, key-bearing — kept.
- LESSON: same filename != same file across drops. Verify the exact path (not the name); the pipeline is path-qualified, spot-checks must be too.

RESUME PATH: see .compiler_state.json next_action. In short: wait for/confirm extraction 98/98 (re-run idempotently if the bg process died), then C2a zoning of prose sources -> route MCQ sources to MCQ-parse and prose to atomize -> C3..C13 per BUILD_PROMPT §7. All ⛔ gates still apply.

## 2026-07-15T20:44:56Z  [C2-done]

**C2 extraction COMPLETE.** 98/98 works, 43,165 pages (9034 OCR + 34131 text), ~70M chars, 0 empty/failed, 91 script-drop spans. Next is the first token-spending stage (atomize prose + MCQ-parse) -> BUDGET GATE per §10e; awaiting approval.
- subagent `` (a255570c9914a16cb) finished — 33 chars -> work/subagent_results.jsonl
- subagent `` (adf400ce422863dfb) finished — 23 chars -> work/subagent_results.jsonl
- subagent `` (a021abc7725725fa0) finished — 18 chars -> work/subagent_results.jsonl

## 2026-07-15T21:06:37Z  [C4]

**Budget gate APPROVED -> LLM ingest started.** User chose Batch API balanced (Haiku atomize / Sonnet MCQ-parse), declined Claude Code tokens. Key in compiler/.env (gitignored, loaded with override). Built llm.py (Batch transport: submit records id BEFORE poll, reconcile by custom_id, cached system prompt, per-request partial-failure). Full eligible: 13802 atomize pages + 6637 answer-bearing mcq pages. Running ~$1 test batch (40 reqs) to verify + measure real cost before full submit.
- subagent `` (a55a881534def4de8) finished — 216 chars -> work/subagent_results.jsonl
- subagent `` (a296889a62dbb3bc4) finished — 26 chars -> work/subagent_results.jsonl

## 2026-07-15T21:21:53Z  [C4-CHECKPOINT]

**[COMPACTION CHECKPOINT — 2026-07-16] Where I am, what's done, what's running.**

WHERE: stage C4 (bulk LLM ingest). A ~$1 TEST batch (40 reqs) is IN FLIGHT on the Anthropic Batch API; a background poller (task banyuljgg = `python -m studyfeed_compiler.llm test`) is waiting on it and will write PASS/FAIL + real cost to compiler/work/llm_test.log. Nothing is blocked; nothing lost if this compacts.

DONE SO FAR (all committed + pushed to github kayomarz97/studyfeed, branch dev):
- C0: git, durability (state.py + 4 hooks, proven), spec/ (schema.sql/SPEC/manifest.schema/SCORING), profiles/mrcp_part1.yaml (16 subjects, blueprint sum 200), compiler scaffold, 27 agents, PLAYBOOK (Batch/Docling/FSRS + 5 mistake-ledger entries).
- C1: unpack 107 PDFs -> 99 canonical works (unrar for RAR5; scanned files hash-dedup only). Classify: 46 past_paper (Pastest recalls, years 2015-2026, Tier1 recall, KEY-BEARING MCQs), 16 qbank (Passmedicine 2025), 20 pearls (incl. 16 Passmedicine High-Yield TEXTBOOK per-subject PROSE), 16 textbook, 1 excluded (Arabic intro).
- C2: extraction 98/98 works, 43,165 pages (9034 OCR via tesseract@2500px/4cores/OMP=1, 34131 text via PyMuPDF), ~70M chars, 0 failures -> compiler/work/extracted/<wid>__*.jsonl (per-page {page,method,chars,text}).
- C4 (now): compiler/.venv (py3.14) has pymupdf/pillow/pyyaml/anthropic. llm.py = Batch transport (submit records id BEFORE poll; reconcile by custom_id [must match ^[A-Za-z0-9_-]+$ -> use '_' sep, NOT ':'); cached system prompt) + build_atomize (pearls+textbook prose -> Haiku) + build_mcq (past_paper+qbank answer-bearing pages -> Sonnet) + CLI test/submit/status/harvest. Key verified working.

PATCH 01 (user spec change, applied today): Part A stable_key done in place (see decisions.patch01). C13 PACKAGING IS BLOCKED until the reproducibility test passes (decisions.C13_blocked_on_patch01). Part B (app notes) is Phase 2.

DOCUMENTED MISTAKE (do not repeat): I earlier mis-reported the Textbook 'N-Subject' files as qbank (my smoke's name-keyed resolver grabbed the Qbank drop's same-named file). Verified drop-qualified: Textbook copies are High-Yield TEXTBOOK PROSE (0 'Question N of' markers) -> reclassified to pearls, NOT qbank. Rule in ledger: same filename != same file across drops; verify the exact path.

RESUME: follow .compiler_state.json next_action. Spec = BUILD_PROMPT.md (on disk, authoritative). Do NOT restart; do NOT re-extract; do NOT relaunch the test if banyuljgg still alive (pgrep -f 'llm test').
- subagent `` (a8281a03b8550cfb5) finished — 8 chars -> work/subagent_results.jsonl

**[compacted at 2026-07-15T21:23:00Z]** — context compacted; disk state is the truth (§10a).
- subagent `` (a69f41cb4a94c5c48) finished — 14121 chars -> work/subagent_results.jsonl

## 2026-07-15T21:32:10Z  [C4-AUTH]

**[C4 - user authorized auto-proceed on TEST PASS]** User (going to sleep, 2026-07-16) instructed: if the C4 TEST batch passes, skip the budget gate and continue autonomously through the FULL LLM ingest (submit -> poll -> harvest) without waking them; on FAIL, fix and re-test with no full spend. Recorded as decisions.auto_submit_on_test_pass; next_action updated. A background bash watcher is armed to wake the session when compiler/work/llm_test.log shows TEST PASS/FAIL.

## 2026-07-15T21:43:29Z  [C4-COVERAGE-FIX]

**[C4-COVERAGE-FIX]** A zero-cost pre-submit coverage audit caught a real bug BEFORE any spend: the mcq page filter `correct answer|explanation` was kind-blind and under-covered the PassMedicine QBANK by ~63% (only ~2358 of ~5396 questions). PassMedicine answer pages rarely contain the literal 'explanation' (106 pp) and state the key in prose (e.g. 'X is the correct first-line treatment') or as the peak peer-response %. Verified 3 such 'no-marker' pages (wid=47 p6/p9/p12) are fully self-contained questions. Pastest (past_paper) was already correct: its self-contained 'Explanation' pages (4279) match and the duplicate bare-question halves are rightly skipped. FIX: build_mcq is now kind-aware (new _mcq_units) - past_paper keeps the per-page answer-marker filter; qbank yields one UNIT per question = the 'Question N of M' page stitched with its continuation pages. New mcq total ~9697 (was 6637). Re-testing the NEW selection (strided sample across both kinds) before any full spend, per the user's auto-proceed authorization.

## 2026-07-15T21:55:48Z  [C4-RETEST]

**[C4-RETEST]** Corrected re-test launched (batch msgbatch_01KcJY6fQW8bubezPuo9K81z, bg task bvp1w9bai, log work/llm_retest.log) after the original test batch sat ~50 min at 0 succeeded. It validates the kind-aware mcq selection (PassMedicine prose-answer parsing) + atomize, and re-extrapolates cost for 13802 atomize + 9697 mcq units. The original test (work/llm_test.log) is superseded and disregarded (left running, not killed, per the no-kill rule). On re-test PASS the session auto-submits the full run per decisions.auto_submit_on_test_pass.
- subagent `` (a0a8a01ee3cb88f51) finished — 33 chars -> work/subagent_results.jsonl

## 2026-07-16T00:53:36Z  [C4-FULL-SUBMIT]

**[C4-FULL-SUBMIT]** Submitted the full C4 LLM ingest per the user's authorization to skip the budget gate. Locked cost ~$105 (mcq ~$88 Sonnet + atomize ~$16 Haiku, batch 0.5x, estimated rates). Validated end-to-end first: the coverage fix (9697 mcq units including 5418 stitched PassMedicine questions) plus three bugs fixed and confirmed - ThinkingBlock result-parsing, mcq JSON truncation (max_tokens 1200->3000; sync test 14/15 parse, qbank 8/8, no truncation at MAX_out=1478), and a JSON-only prompt. Batches run for hours; will poll to completion then harvest to atoms.jsonl + mcqs.jsonl. A residual ~7% of mcqs return unparseable JSON edge cases - hardening _parse_mcq during the batch wait (harvest-time only, no extra spend).
- subagent `` (a8cb386235bd3f875) finished — 12 chars -> work/subagent_results.jsonl
- subagent `` (a2bf7245b7e6c1081) finished — 36 chars -> work/subagent_results.jsonl
- subagent `` (a8afc8da75d9cb2ac) finished — 32 chars -> work/subagent_results.jsonl
- subagent `secret-scanner` (a669007b4aeef7e20) finished — 4151 chars -> work/subagent_results.jsonl
- subagent `docs-researcher` (a35ce6e3132724302) finished — 5393 chars -> work/subagent_results.jsonl
- subagent `` (a7b3bcc26465aaa0f) finished — 28 chars -> work/subagent_results.jsonl
- subagent `` (a028db2b3feae85a1) finished — 319 chars -> work/subagent_results.jsonl

## 2026-07-16T03:22:11Z  [C5-COMPLETE]

**[C5-COMPLETE]** MCQ corroboration clustering done. Embedded 9,576 stems locally (fastembed/ONNX bge-small; batch32+threads2 after a batch=256 OOM on the 7.6GB box), exact block-wise cosine, union-find at cosine>=0.92, then Opus adjudicated the 826 borderline pairs in [0.89,0.92): 317 confirmed reworded-duplicates merged. Result: 8518 clusters, 638 multi-work corroboration clusters (up from 526 exact-match, +21%), 77 cross-publisher (Pastest+PassMedicine), 9 boilerplate (§5i, excluded from counts). Adjudication ~$7.42 (build spend ~$115). Outputs: mcq_clusters.jsonl, mcq_corrob.jsonl (per-MCQ distinct_work_count for C9), mcq_adjudication_verdicts.jsonl (cached, reproducible). hnswlib ANN deferred to C7 (159k atoms). Next: C6 PYQ/trend.
- subagent `` (a8bc38ebeef85c801) finished — 16 chars -> work/subagent_results.jsonl

## 2026-07-16T03:56:10Z  [C5b-WORKGROUP]

**[C5b-WORKGROUP]** User caught a work-identity problem: the PassMedicine High-Yield textbook was in the pack twice (16 per-subject files wid 63-78 + a combined Part1+2 copy wid 79) and the SKELETON '(C)' book was 14 per-subject slices — each file counted as a separate 'work', which fakes cross-corroboration (§5b's core trap). Fix (approved): regrouped slices into true works via work/work_groups.json (99 files -> 70 works); kept the 16 PassMedicine subject files, dropped the combined wid 79 -> removed 26,662 duplicate/Part-2 atoms (159,689 -> 133,027). Corroboration now counts real independent sources. Next: parse the textbook MCQs (gated) + re-cluster.

## 2026-07-16T04:06:05Z  [C6-COMPLETE]

**[C6-COMPLETE]** PYQ analysis + trend done (free, on the C5 clusters). Per question: is_pyq, pyq_hit_count (distinct past_paper canonical works), pyq_years, pyq_last_year, S_pyq=sum exp(-0.15*(2026-year)), and pyq_trend. 4,266/9,576 MCQs are PYQs (qbank/textbook Qs inherit PYQ credit by clustering with a past_paper question); trend rising 1,658 / steady 1,489 / fading 281 clusters; 125 multi-year clusters (force importance 5); corpus mean paper year 2020.6. Output: work/mcq_pyq.jsonl (feeds C9 scoring). Staleness (key_conflict) deferred to post-C8. Next: C7 atom dedup.
- subagent `` (a1ce884baba62775b) finished — 16 chars -> work/subagent_results.jsonl

_[session ended: clear at 2026-07-16T04:58:27Z]_

_[session ended: resume at 2026-07-16T04:58:48Z]_

_[session ended: resume at 2026-07-16T04:59:22Z]_
- subagent `` (a8275145fbdd2c681) finished — 47 chars -> work/subagent_results.jsonl
- subagent `` (a65c5efada37f32f7) finished — 8 chars -> work/subagent_results.jsonl
- subagent `` (adac2f5f6fedd18f2) finished — 41 chars -> work/subagent_results.jsonl

## 2026-07-16T06:34:44Z  [SKELETON-COMPLETE]

SKELETON qbank MCQs parsed + C5/C6 re-run. 14 textbook-kind subject files (SsAcademy "SKELETON of MRCP Part 1", canonical work 80) were misfiled as textbook, so C4 never parsed their MCQs. Added llm._mcq_units_textbook: detect textbook works with >=20 Question headers, split into Questions/Answers sections at number resets, PAIR question K with answer K (each unit=stem+options+printed key+explanation). 2913 units, validated offline (100% complete, 0 dup ids, existing 9697-unit path unchanged) + $0.20 sample (20/20). Full batch 2913/2913 parsed, 0 err, ~$11.53. Harvest idempotent-append -> mcqs.jsonl 9576->12489. Wired workgroups.canonical_map into cluster.py+adjudicate.py (14 slices collapse to work 80; 0 non-canonical slices in output). C5 re-cluster: 9135 clusters, multi_work 638->2695, cross_publisher 77->2119; adjudication 74 new pairs $0.61. C6: pyq_mcqs 4266->4339. Kept kind=textbook so the ~40k knowledge-atoms stay. NEXT: build C7 (dedupe.py stub).
- subagent `` (a94c2629ed0fe8f65) finished — 41 chars -> work/subagent_results.jsonl
- subagent `` (a3a9ea3a38d248190) finished — 106 chars -> work/subagent_results.jsonl
- subagent `` (a2feb6cf2e6fe1828) finished — 44 chars -> work/subagent_results.jsonl
- subagent `` (a42f7428d1f3227fe) finished — 41 chars -> work/subagent_results.jsonl

## 2026-07-16T07:47:08Z  [C7-COMPLETE]

C7 atom dedupe/merge COMPLETE (dedupe.py, was a stub). 133027 atoms -> 86702 (collapsed 46325, 35%). fastembed bge-small embeddings (cached to atom_vecs.npy) + hnswlib ANN. CRITICAL FIX: first pass used cosine-only single-linkage union-find -> catastrophic false merges (74-member cluster fused "digoxin/hypokalaemia/ischaemic... causes prolonged PR interval" = distinct facts sharing a template -> laundered corroboration, 5b/364). Fix = dual gate (cosine>=0.95 AND idf-weighted token overlap>=0.5, the latter keying on rare ENTITY words template-siblings do not share) + LEADER clustering (an atom joins only if it clears both gates vs the cluster LEADER, never a transitive intermediate -> a stray edge cannot chain a template family). Result: largest cluster 74->30, all genuine same-fact; verified members of Huntington-CAG(9m/4w), PCOS-hirsutism(5m/4w), genital-herpes-aciclovir(6m/3w) etc are true corroboration. Conservation OK (all 133027 preserved). distinct_work_count canonicalised (14 SKELETON slices->work 80), max 6, citations UNIONed (never drop a source, 5b). 20149 atoms corroborated by >=2 works. Tuned on 8k sample + specific WPW/PR cases. cluster.py+adjudicate.py also gained load_mcqs_canonical for the same 5b reason.
- subagent `` (a51638a9b535cbb85) finished — 18 chars -> work/subagent_results.jsonl

## 2026-07-16T07:59:12Z  [FINDINGS-SKELETON-C7]

Consolidated findings from the SKELETON-parse + C7 work (analysis + reusable gotchas, complementing the [SKELETON-COMPLETE]/[C7-COMPLETE] milestones).

(1) TEXTBOOK TRIAGE (free option/answer-signature scan, zero tokens): 14 of 36 textbook/pearls works are MCQ-bearing -- ALL of them the SsAcademy "SKELETON of MRCP Part 1" subject slices (canonical work 80). The other 22 are genuine PROSE (16 PassMedicine High-Yield pearls + Step-to-MRCP, The-Only-MRCP-Notes, Essential-Revision-Notes, Faraz, 100-facts), correctly atomized only. So textbook-MCQ extraction was NOT the near-no-op first hypothesised: ~2,985 printed-key MCQs were recoverable.

(2) SKELETON LAYOUT: each subject file = a 6-item demo (pages 16-19, dropped) + a QUESTIONS section (Q1..N: stem+options) + an ANSWERS section (Q1..N: printed key + explanation), N matching. Question numbers RESET across sections (Q1 appears 3x), so units are paired by (section,number) -- question K with answer K -- to rebuild complete MCQs. A naive per-Question split would have yielded ~3k keyless questions + ~3k wasted orphan answers and cost ~2x.

(3) CORROBORATION EXPLOSION: multi-work clusters 638->2695 (4.2x), cross-publisher 77->2119 (27x). SsAcademy SKELETON is largely the SAME MRCP question pool as Pastest+PassMedicine, so ~1,960 of its 2,913 questions auto-merged (cosine>=0.92) into existing clusters -- genuine 3rd-publisher corroboration (the strongest importance signal), not noise.

(4) C5 CANONICAL-MAP GAP: the [C5b-WORKGROUP] decision stated corroboration MUST apply the canonical map, but it was only ever wired into pyq.py -- cluster.py + adjudicate.py counted RAW source_id. Harmless until now (every pre-SKELETON MCQ-bearing work was already 1:1 canonical), but adding SKELETON forced the fix (new cluster.load_mcqs_canonical, used by both). Verified 0 non-canonical slices in output.

(5) GOTCHA (reusable) -- strided sampling UNDER-COUNTS near-dup merge rate. C7 8k strided sample collapsed 4.9%; the full 133k collapsed 35-39% -- striding scatters duplicate pairs so few co-occur in-sample. Validate merge PRECISION on a sample; never estimate merge RATE/recall from a strided sample. Logged to PLAYBOOK.

(6) GOTCHA (reusable) -- embedding cosine ALONE over-merges short templated facts. "X causes Y" siblings score ~0.95 even when X differs; single-linkage union-find then chains a whole family into one giant FALSE cluster that LAUNDERS corroboration (5b/364; observed a 74-member "prolonged PR interval" cluster fusing digoxin/hypokalaemia/ischaemic/Lyme etc). Fix pattern = dual gate (cosine AND IDF-weighted entity-token overlap) + LEADER clustering (join vs the cluster LEADER, never a transitive intermediate). Threshold tuning has an irreducible inversion (heavily-reworded TRUE pairs e.g. flucloxacillin 0.454 can score BELOW template-sibling FALSE pairs e.g. ischaemic/Lyme 0.463) -> choose precision-first. Logged to PLAYBOOK.
- subagent `` (aacc1284b7750f6ca) finished — 18 chars -> work/subagent_results.jsonl
- subagent `secret-scanner` (a8590a468b7af7151) finished — 3654 chars -> work/subagent_results.jsonl
- subagent `` (af573d85862e07008) finished — 38 chars -> work/subagent_results.jsonl
- subagent `` (aae65c5fcff407581) finished — 17 chars -> work/subagent_results.jsonl
- subagent `` (ae89fc121fb090662) finished — 33 chars -> work/subagent_results.jsonl
- subagent `` (a67c341849db397db) finished — 33 chars -> work/subagent_results.jsonl
- subagent `` (a523bcf468589fc26) finished — 31 chars -> work/subagent_results.jsonl
- subagent `` (aac6507059283aec8) finished — 300 chars -> work/subagent_results.jsonl
- subagent `` (ad2c702371d0dcd03) finished — 44 chars -> work/subagent_results.jsonl
- subagent `` (a95e8ea05042a6230) finished — 36 chars -> work/subagent_results.jsonl
- subagent `general-purpose` (a447accbf39921a50) finished — 7504 chars -> work/subagent_results.jsonl

## 2026-07-16T09:27:36Z  [C8-COMPLETE]

C8 alias normalization + concept graph COMPLETE. NEW alias.py: deterministic canonical_key (ae/oe->e, -ise->-ize, -our->-or, -re->-er; MATCH KEY ONLY, never rewrites UK display) + synonym resolver from profile.nomenclature.alias_hints (2003 BAN->rINN, adrenaline/epinephrine exception, UK/US) -> 16/16 5e checks. NEW graph.py: Haiku Batch API extracts (s,r,o) triples per atom (terse pipe format after JSON measured 2x tokens -> full Sonnet ~$69 > approved; user chose Haiku ~$6 + Sonnet audit). 86,702 atoms -> 75,716 triples -> fold by alias key -> 38,013 concepts + 68,091 deduped typed grounded edges (causes 17917, associated_with 19119, treats 7012, ...); networkx PageRank -> centrality (top: rheumatoid arthritis 1.0, AKI, asthma, SLE, hypertension, COPD - plausible MRCP hubs). guideline_sensitive=1651 (keyword). VERIFY: Sonnet subagent on Claude Code usage (NOT billable API, per user) audited a 114-edge stratified sample -> 75% fully correct, 97% SUPPORTED (3 hallucinated); errors mostly DIRECTION flips concentrated in investigated_by (6/8) + complication_of (4/8), NOT associated_with-by-agreement. Applied the audit top fix: deterministic investigated_by disease->test swap (1414 edges) -> MRI/infective endocarditis now correct hubs. Ship-ready for C9 centrality. RESIDUAL (pre-C11 interlink): complication_of direction, drug-direction on treats/first_line_for, associated_with overuse. GATE: 726 alias proposals -> dist/alias_proposals.yaml for user review.
- subagent `` (ad1288ea49b608e62) finished — 33 chars -> work/subagent_results.jsonl
- subagent `` (abcd4f603ba4786ca) finished — 25 chars -> work/subagent_results.jsonl
- subagent `secret-scanner` (a8590a468b7af7151) finished — 2566 chars -> work/subagent_results.jsonl

## 2026-07-16T10:01:38Z  [C9-v1-MCQ]

C9 v1 importance scoring (MCQs) — importance.py stub implemented. Deterministic per SCORING.md: 8 weighted signals -> raw -> bucket .80/.62/.42/.22 -> hard floors. 12489 MCQs scored: 5★389 4★3668 3★282 2★2828 1★5322. Floors: multi_year_pyq->5 (389), pyq_min->4 (3582), fading_cap->3 (282). BIMODAL (thin 3★): expected v1 fingerprint — emphasis (needs C3 highlights, skipped) + centrality (needs MCQ<->concept link) are 0, so 17% of weight is absent; PYQ floor lifts PYQ items to 4-5. NOT degenerate by the auto-check (1★=42.6%<45%, 5★ nonempty). Atoms+concepts scoring DEFERRED (v2): needs atom<->concept<->pyq linkage from graph_edges_raw. C9 NOT marked complete. Output work/mcq_importance.jsonl.

## 2026-07-16T10:12:46Z  [C9-COMPLETE]

C9 importance scoring COMPLETE (importance.py, was stub). Deterministic 1-5 per SCORING.md: 8 weighted signals -> raw -> bucket .80/.62/.42/.22 -> hard floors. All 3 item types scored. MCQs 12489: full signals (pyq/corrob/freq/trend/tier/blueprint). Concepts 38013: centrality direct + corrob (distinct canonical works from graph_edges_raw) + APPROX PYQ (multi-word concept names matched in PYQ MCQ stems, 3719 matched). Atoms 86702: own corrob/freq/tier/blueprint + centrality & PYQ INHERITED from linked concepts (atom<->concept via rationale, 54788 linked). Histograms: MCQ 5:389/4:3668/3:282/2:2828/1:5322; concept 5:2626/4:1017/3:102/2:7222/1:27046; atom 5:16/4:6527/3:22685/2:21464/1:36010. Top 5star concepts = rheumatoid arthritis, coeliac, SLE, diabetes, AKI, Addisons, ankylosing spondylitis, infective endocarditis (multi_year_pyq floor - correct high-yield). BUG found+fixed: multi_year_pyq->5 floor is CONCEPT-only per SCORING but I initially applied it to atoms (via union of linked-concept years) -> 33pct atoms force-promoted to 5star (degenerate); removed -> atoms get PYQ as an inherited SIGNAL only, healthy 41/25/26/8/0 spread. CAVEATS: emphasis(11pct weight)=0 (C3 highlights were skipped C0->C4); concept 71pct 1star reflects noisy 38k extracted-concept tail (candidate for pruning + opus importance-scorer recalibration); atom<->concept + concept<->PYQ links are approximate (user-accepted). NEXT: C10 dossiers / staleness (post-C8) / opus recalibration if wanted.
- subagent `` (a0689e4fe66634283) finished — 34 chars -> work/subagent_results.jsonl

## 2026-07-16T10:37:34Z  [C3-COMPLETE]

C3 emphasis harvest COMPLETE (backfilled - build had skipped C0->C4). bookusage.py implemented. FINDING: digital annotations = 0 across all 107 source PDFs (PyMuPDF probe) - these are distributed study materials, not the user annotated copies, so is_highlighted (USER highlights) = 0 everywhere; colour-mask on Pastest/PassMedicine screenshots would only catch UI (buttons, peer-response bars), skipped. The REAL emphasis = the BOOK own high-yield markers (book_flagged, 5h): (a) whole-source salience - pearls high-yield by construction (profile source_tiers.pearls.source_salience=0.75, exempt noise ceiling); (b) per-topic markers mined from the already-extracted text - SsAcademy SKELETON 🎤/▶️ high-yield emoji (0.85), key point (0.7), pearl (0.6); high-yield + important EXCLUDED (book-title header / common word = noise). 9965 flagged pages: pearls 8256 (whole-source) + textbook 1572 (SKELETON markers) + 137 qbank/paper. No book tripped the 60pct noise ceiling. Output work/emphasis.jsonl {source_id,page,book_flagged,book_salience}. Wired S_emphasis into importance.py (emphasis_mix my_highlight 0.6 * is_highlighted(0) + book_flag 0.4 * book_salience => emphasis maxes ~0.34) and rescored C9: book-flagged content correctly lifted (atoms 1star 41.5->20.1pct). SIDE EFFECT: atoms 2star=45.2pct (just degenerate) - whole-book-high-yield sources boost uniformly -> pile-up at the 1->2star threshold; the deterministic base is correct, threshold smoothing is the opus importance-scorer recalibration step (documented), not a hand-tune. All 12 build inputs now feed importance. NEXT: C10 dossiers; optional opus recalibration.
- subagent `` (a9a19eb6ab6b4d934) finished — 36 chars -> work/subagent_results.jsonl
- subagent `` (a9fd93d1b229c4f9a) finished — 15 chars -> work/subagent_results.jsonl
- subagent `secret-scanner` (a8590a468b7af7151) finished — 2742 chars -> work/subagent_results.jsonl
- subagent `` (a2450f503792ba3c4) finished — 17 chars -> work/subagent_results.jsonl
- subagent `` (a655e069894b99be5) finished — 14 chars -> work/subagent_results.jsonl
