
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
