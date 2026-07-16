# StudyFeed — build specification (offline, exam-agnostic MCQ study app)

## ▶ START HERE — do these four things, in this order, before anything else

**1. Persist this spec to disk, verbatim.** Save this entire message — from `# StudyFeed` to the last
line — as **`BUILD_PROMPT.md`** in the repo root. Do not summarize, reformat, or abridge it. It is the
authoritative specification and it must live on disk, because per §10a anything held only in this
conversation is eroded by compaction.

> **If `BUILD_PROMPT.md` already exists at the repo root, you are re-reading it after a restart or
> compaction.** **Do not start over, and do not re-save this file.**
>
> **⚠ AS OF 2026-07-17 (PATCH 02), STEPS 1–4 BELOW ARE HISTORY — PHASE 1 IS COMPLETE.**
> The compiler is **DONE (C0–C13)** and `dist/pack_MRCP_PART1.db` is built (26,965 playable items,
> reproducibility gate cleared). §13's kickoff, the archive drops, and the C0 environment checks all
> **already happened** — do not re-run them, and do not re-do §13.
>
> **Where a fresh session actually resumes now:**
> - **App work (the live front — this is almost certainly you):** you are on the **laptop**. Read
>   `JOURNAL_APP.md` (the app's human log — laptop-owned) and go to **§8 A1**. A0 is done.
> - **Compiler work (the box, now dormant):** read `compiler/.compiler_state.json`, re-poll any batch
>   IDs under `batches`, read the tail of `JOURNAL.md`, resume at `next_action`.
>
> **State on disk is the truth, never the transcript** — but note the two machines keep **two
> journals** (`JOURNAL.md` = compiler/box, written by `state.py`; `JOURNAL_APP.md` = app/laptop).
> Never cross-write them.

**2. Wire it into my working agreement.** I use a `CLAUDE.md` (auto-loaded every session, deliberately
short), `.claude/INDEX.md` (codebase map, scanned first), and `.claude/PLAYBOOK.md` (on-demand
cookbook + mistakes ledger). Fill in **only** `CLAUDE.md`'s two project-specific sections — *Project at
a glance* and *Commands* — from §0–§3 and §11 below. **The "Project at a glance" section must state
that `BUILD_PROMPT.md` is the authoritative spec and must be read before acting**, and must name
`compiler/.compiler_state.json` (progress) and `JOURNAL.md` (history) as the resume path. Keep it
short — a long CLAUDE.md gets half-ignored. Seed `.claude/INDEX.md` as the repo takes shape and keep
it true in the same commit as any file add/move. Leave `PLAYBOOK.md` for `docs-researcher` and
`mistake-tracker` to populate as we go.

**3. Reconcile with my global rules before you write any config.** My `~/.claude/CLAUDE.md` holds the
golden loop, non-negotiables, and an **agent routing table**; I already have `mistake-tracker` and
`docs-researcher` agents. This spec adds ~26 agents in `.claude/agents/` with explicit per-agent
`model:` frontmatter (§4). **Read my global CLAUDE.md first and report: (a) any agent name collisions,
(b) whether my global routing table pins models in a way that fights §4's haiku/sonnet/opus split,
(c) any rule here that contradicts a global rule.** My global and project rules win over this file —
**surface the conflict and ask; never silently resolve it.**

**4. Then begin at §13 (Kickoff).** Git and `.gitignore` come first — my three archives are already
sitting at the repo root and must be ignored before the first commit. **Ask before assuming (§1).**

> **⚠ Step 4 is COMPLETE (2026-07-17).** Kickoff happened, the archives were ingested, and the
> compiler ran to C13. **A fresh session must NOT begin at §13** — see the resume box above and go to
> **§8 A1**. Steps 1–3 remain true (the spec lives on disk; the working agreement and global-rule
> reconciliation still apply). **Ask before assuming (§1)** still binds.

---

> **What this is:** one long-lived Claude Code session that spawns subagents and stops at ⛔ approval
> gates. First exam profile: MRCP(UK) Part 1.

---

## 0. Locked parameters

```yaml
first_exam:          MRCP(UK) Part 1          # first profile; the app must NOT be hardcoded to it
include_figures:     true                      # extract images/diagrams, tie to atoms + MCQs + dossiers
highlight_sources:   both                      # digital PDF annotations AND marker-on-scans (colour mask)
build_env:           split_compiler_box_app_laptop  # ⚠ AMENDED 2026-07-17 (PATCH 02) — was
                                                #   `hetzner_headless_claude_code`. TWO machines now:
                                                #   COMPILER → Hetzner box (headless Linux, no GUI).
                                                #   APP      → 16GB laptop: Android Studio + real phone.
                                                #   The box cannot build the app (OOM: ~2.7GB free, swap
                                                #   96% spent) and is headless — §8 A2's AMOLED feed
                                                #   cannot be judged without a real panel.
                                                #   ⚠ INTENT UNCHANGED: the app MUST still build from the
                                                #   CLI (`./gradlew assembleDebug`) with NO IDE-only
                                                #   config. Only "over SSH" is moot. Sideload: `adb`.
volume:              unbounded                  # 15+ PDFs AND GROWING — never hardcode a count;
                                                #   glob sources/**, batch, checkpoint, resume

# messy inputs — see §7 C1 and §12
source_inputs:       three_archives_at_repo_root  # I drop ALL THREE at the PROJECT ROOT, once, up front,
                                                #   and never touch them again. C0 moves them to inbox.
                                                #   ⚠ .gitignore MUST cover *.zip/*.rar BEFORE I drop.
drops:                                          # archive provenance is a STRONG tier hint (§7 C1c)
  - {file: "textbooks*.zip", holds: "textbooks + pearls + references, maybe a nested .rar",
     tier_hint: "3/4 mixed — MUST classify per-file, textbook vs pearls vs reference"}
  - {file: "*mcq*.rar",      holds: "all MCQs, organised subject-wise",
     tier_hint: "2 (qbank) — subject folders are the subject_id signal"}
  - {file: "papers*.zip",    holds: "a nested .rar + random loose pdfs",
     tier_hint: "1 (past_paper) — extract YEAR per file; never guess (§7 C1c)"}
cross_archive_dupes: expected                   # the same pdf will appear in more than one drop.
                                                #   ⚠ if two copies imply DIFFERENT tiers ⇒ ASK (§7 C1b)
archive_formats:     [zip, rar, 7z, tar]        # RAR needs a non-default binary — see §7 C0/C1a
tier_from_structure: best_effort                # folders inside a drop map to tier if present; else infer + ASK
file_dupes:          expected                   # ⚠ this CORRUPTS importance if unhandled — see §5b
book_usage_sections: true                       # books carry "How to use this book" front matter —
                                                #   read it, learn each book's own high-yield markers (§5h)
books_are_subject_wise: true                    # one book per subject ⇒ sources.subject_id is the default
front_matter_boilerplate: true                  # ⚠ the SAME worked example + MCQs appear in EVERY book
                                                #   of the series. Left alone it scores 5★. See §5i.
language_filter:     latin_only                 # drop Arabic/Farsi/Bengali spans. NOT "strip non-ASCII"
                                                #   ⚠ Greek + medical notation MUST survive — see §5g
timeline:            under_6_weeks              # tight — sequence so a working feed comes early
app_framework:       kotlin_compose             # Jetpack Compose + Room + FSRS-Kotlin (Material3)

# durability + token budget  ⇦ READ §10 BEFORE WRITING ANY COMPILER CODE
state_authority:     disk_not_context           # context is disposable; .compiler_state.json is truth
resume_policy:       fresh_session_resumes      # a brand-new session must resume from disk ALONE
bulk_llm_transport:  batch_api                  # bulk atomize/generate via Batch API (50% off), NOT subagents
claude_code_budget:  orchestration_only         # CC writes/debugs/judges; it NEVER reads 6000 pages

# hard runtime constraints (shipped app)
runtime:             fully_offline_no_tokens    # no network, no AI, no API keys, no INTERNET permission
realtime_generation: none                       # EVERYTHING (incl. dossiers + scores) is pre-compiled
background_exec:     none                        # no services, no WorkManager/JobScheduler, no boot receiver
theme_default:       amoled_dark                 # true black (#000000) surfaces to save battery; light optional
product_stance:      full_ship_no_mvp            # build the whole thing; phases are approval gates

# multi-exam / decoupling
architecture:        decoupled_spec_compiler_app # 3 parts joined only by a versioned pack format
multi_pack:          true                        # app holds multiple packs, switch exam, progress per-pack

# importance / ranking
importance_scale:    1_to_5                      # every item gets an integer 1..5, sortable in the app
pyq_priority:        highest                     # past-paper questions dominate the ranking
pyq_years:           known                       # CONFIRMED: papers carry identifiable years
corroboration:       distinct_sources_over_repeats  # asked at MANY sites > repeated at ONE site
trend_analysis:      enabled                     # rising/steady/fading — possible because years are known
staleness_check:     enabled                     # old keys on guideline-sensitive topics get cross-checked

# nomenclature
drug_nomenclature:   uk_generic_rinn             # BNF/rINN style. NOT alias-free — see §5e

# model routing (verified against Claude Code subagent frontmatter)
model_routing:       opus_judges_sonnet_compiles
```

---

## 1. Mission — three decoupled deliverables

> ### ⚠️ Standing rule: ask, don't assume.
> **When in doubt, stop and ask me. Never fill a gap with a guess.** This build has a lot of surface
> area and a wrong assumption compounds silently for hours before I notice. Specifically — never
> invent a fact, citation, page reference, paper year, or answer key; never guess which PDF is what;
> never silently merge two entities; never pick a library version without checking what actually
> resolves; never arbitrate a clinical conflict between sources on your own. If a source is ambiguous,
> quarantine it and tell me. If this prompt is ambiguous, ask rather than picking.
>
> **How to ask efficiently:** I'm often reading this on my phone between ward rounds, so don't block on
> every micro-decision. **Accumulate non-blocking questions and present them as a numbered batch at the
> next ⛔ gate.** But block *immediately* — before acting — on anything irreversible, expensive, or
> clinically wrong: destructive writes, mass generation, a contradicted answer key, a grounding failure.
> "I assumed X" is never an acceptable thing for me to discover later. "I stopped and asked" always is.

Do **not** build one monolith. Build three parts joined only by a stable file format, so the data can
change per exam while the app stays identical:

1. **`spec/` — Content Pack Specification.** A documented, versioned contract: the SQLite schema plus a
   `pack_manifest` carrying the *exam profile* (exam name/code, subject taxonomy + weights, tag
   definitions, importance weight vector, feature flags, schema/pack versions, checksum). This is the
   seam. The app knows only the spec; it learns everything exam-specific by reading a pack.

2. **`compiler/` — exam-agnostic content compiler (Python).** Takes an `exam_profile.yaml` plus my
   source PDFs and emits a spec-conforming pack: grounded atoms, a concept graph, **a 1–5 importance
   score on every item**, pre-compiled topic dossiers, and a mixed deck of flashcards / MCQs / reels /
   text blobs / images / interlink quizzes. New exam = new profile + new sources; **compiler code does
   not change.** Runs on my Hetzner box under Claude Code; burns tokens **only at build time**. Must be
   **incremental + idempotent** (I feed 15 PDFs in batches and re-run).

3. **`app/` — generic offline player (Kotlin + Compose).** Reads a pack's manifest and configures its
   subjects, filters, tags, and exam label **at runtime from the pack**. Hero is one **infinite
   scrolling study feed** mixing every item type, sortable/filterable by importance. On-device FSRS-6,
   persistent local mistake memory with one-tap star/tags, mastery, concept map, dossier reader.
   **Zero network, zero tokens, zero background execution.**

Non-negotiables (priority order):
- **Offline + no background + no realtime generation.** No network calls, no AI SDK, no keys, no
  INTERNET permission; no services or periodic work; every screen renders pre-built pack content only.
  **Importance scores are computed at build time and stored** — never derived on the phone.
- **Grounding / no hallucination.** Every atom, dossier section, and generated asset traces to a real
  source page, or it is quarantined and logged — never fabricated. (Reuse the Iatronix pattern:
  grounding gate + citation validator + evidence floor.)
- **Reproducible scoring.** Re-running the compiler on unchanged inputs must produce **identical**
  importance scores. This is why scoring is deterministic arithmetic, not per-item LLM rating.
- **Exam-agnostic app.** If it reads as MRCP-specific in app code, it's wrong.
- **Human checkpoints at irreversible/expensive steps.** Propose → I approve → execute.

---

## 2. Architecture

```
  spec/SPEC.md ──────────────────── the contract (schema + manifest + versioning) ──────────────┐
                                                                                                 │
┌───────── PART B · COMPILER (build time, tokens OK, exam-agnostic) ─────────┐                   │
│ exam_profile.yaml + sources/inbox/<one big zip: pdfs + rars + nested zips> │                   │
│   ─▶ [ingest+OCR] ─▶ [highlight harvest] ─▶ [atomize+ground] ─▶ [dedupe]   │                   │
│        ┌──────────────┬───────────────┬──────────────┬──────────────┐      │  emits pack       │
│        ▼              ▼               ▼              ▼              ▼      │  conforming to    │
│  [concept-graph] [PYQ analysis] [corroboration] [IMPORTANCE]  [asset gens] │  ── SPEC ─────────┘
│   nodes+edges    year+recency   distinct-source   1..5 + why   cards/mcqs/
│   centrality     hard floors    clustering        (Opus cal.)  reels/blobs/
│                                                                dossiers/figs
│        └──────────────┴───────────────┴──────────────┴──────────────┘      │
│                    ▼  [validate] ─▶ [package] ─▶ pack_<exam>.db (+ /figures)│
└─────────────────────────────────────────────────────────────────────────────┘
                                    │ copy pack + figures to phone
                                    ▼
┌───────── PART C · APP (runtime: offline / no tokens / no background) ──────────────────────────┐
│ Import pack(s) via SAF ─▶ read manifest ⇒ configure subjects/tags/exam from the pack           │
│ attach read-only content DB + separate read-write progress DB (per exam, never wiped)          │
│ HERO Study Feed ── reel · blob · image · flashcard · MCQ(answer inline) ── infinite scroll     │
│   filters: All · Subject · Mistakes · Read only · MCQs only · Flashcards only · PYQ only · ★n  │
│   sort:    smart (FSRS+importance) · importance ↓ · importance ↑ · priority (imp × weakness)   │
│ Topic Dossier reader · Mistakes (1-tap star + 5 tags) · Concept map · Mastery · Switch exam    │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Repo layout to create

```
studyfeed/
├── spec/
│   ├── SPEC.md                 # human-readable pack contract + versioning policy
│   ├── schema.sql              # canonical content-pack DDL (source of truth)
│   ├── manifest.schema.json    # JSON Schema for pack_manifest / exam profile block
│   └── SCORING.md              # the importance model, documented + worked examples
├── profiles/
│   └── mrcp_part1.yaml         # first exam profile (taxonomy, tiers, weights, colours, tags)
├── compiler/
│   ├── pyproject.toml          # uv-managed; python 3.11+
│   ├── studyfeed_compiler/
│   │   ├── config.py           # loads a profile; model routing; thresholds
│   │   ├── unpack.py           # recursive zip expansion → flat inventory
│   │   ├── filededupe.py       # sha256 → fingerprint → fuzzy; work_id assignment (§5b)
│   │   ├── scriptfilter.py     # Unicode-block allowlist (§5g) — zero tokens
│   │   ├── ingest/             # per-source-type extractors
│   │   ├── highlights/         # digital-annotation + colour-mask harvesters
│   │   ├── atomize.py  ground.py  dedupe.py  graph.py
│   │   ├── cluster.py          # embed + ANN + union-find near-dup clustering
│   │   ├── pyq.py              # past-paper detection, year extraction, topic mapping
│   │   ├── importance.py       # THE scoring engine (deterministic; weights from profile)
│   │   ├── dossier.py          # cross-subject entity aggregation → long pre-built pages
│   │   ├── generators/         # flashcards, mcqs, reels, blobs, interlink, figures
│   │   ├── bookusage.py        # front/body zoning + convention mining (§5h/§5i)
│   │   ├── validate.py  pack.py  run.py
│   ├── sources/
│   │   ├── inbox/              # ⇦ I DROP ONE ZIP HERE. compiler unpacks; I never unpack by hand
│   │   └── usage_notes/        # optional: my own per-book notes — authoritative over §5h
│   ├── .compiler_state.json    # ⇦ SOURCE OF TRUTH for resumption (§10a). Atomic writes.
│   ├── work/
│   │   ├── unpacked/           # expanded zips/rars (gitignored)
│   │   └── script_drops.jsonl  # every span the §5g filter removed — auditable
│   └── dist/                   # pack_<exam>.db + figures/ + manifest.json + reports
├── app/                        # Android (Kotlin + Compose) — generic player
├── .claude/
│   ├── agents/                 # subagent definitions (model: routing lives here)
│   ├── hooks/                  # session_start.py, pre_compact.py, session_end.py, subagent_stop.py
│   └── settings.json           # hook registration
├── CLAUDE.md                   # static project conventions (loaded every session)
├── JOURNAL.md                  # human-readable milestone log (§10a)
└── README.md
```

---

## 4. Subagents + model routing

Define each in `.claude/agents/<name>.md`. Frontmatter fields (verified against current Claude Code
docs): `name`, `description`, `tools`, `disallowedTools`, `model`, `effort`, `memory`, `maxTurns`,
`skills`, `permissionMode`, `background`, `color`. `model` accepts `opus`, `sonnet`, `haiku`, `fable`,
a full ID (e.g. `claude-opus-4-8`), or `inherit`.

> **Gotcha — check this first.** Model resolution order is: (1) the `CLAUDE_CODE_SUBAGENT_MODEL`
> environment variable, (2) the per-invocation `model` parameter, (3) the frontmatter `model`,
> (4) the main conversation's model. **If `CLAUDE_CODE_SUBAGENT_MODEL` is set in my env, it silently
> overrides every frontmatter value below.** Check it at C0 (`echo $CLAUDE_CODE_SUBAGENT_MODEL`) and
> tell me if it's set — unset it if so, or the routing is a no-op.

**Routing principle:** *Haiku does mechanics. Sonnet compiles and generates. Opus judges.*
Opus never rates items one-by-one — it calibrates, adjudicates the ambiguous band, and audits.

| Agent | `model` | Role | Key rules |
|---|---|---|---|
| `archive-unpacker` | `haiku` | **One zip → flat PDF inventory** | I drop a **single zip** into `sources/inbox/` holding PDFs, **`.rar`**, and nested zips at mixed depth. Expand recursively into `work/unpacked/`, **depth limit 8**, and abort with a report if total expansion exceeds `max_expanded_gb` (archive-bomb guard). **RAR needs a binary that isn't installed by default — see §7 C1a; verify it at C0, don't discover it mid-run.** Ignore `__MACOSX/`, `.DS_Store`, thumbs. Password-protected or corrupt archive ⇒ **stop and ask me**, never skip silently. Non-PDF contents ⇒ list and ask. Path-qualify everything (two archives both holding `chapter1.pdf` are different files). |
| `book-usage-analyst` | `sonnet` | **Read "How to use this book" first, then fence it off** (§5h, §5i) | Two jobs. **(a) Learn:** find the front matter — *How to use this book / Key to symbols / Legend / Icons / Preface* — and extract that book's **own** emphasis conventions (starred topics, boxed high-yield, grey pearl panels, ★★★ ratings, margin flags) into a per-source `usage_profile`. Hand it to the extractors so those markers become `book_flagged` atoms. **(b) Fence:** set `body_start_page` — roman→arabic pagination flip, first "Chapter 1", end of TOC — and **⛔ show me the page numbers to confirm**. Everything before it is `front_matter`: read for conventions, **never atomized**. ⚠ **The series' shared worked example lives there and scores 5★ if it leaks (§5i).** **(c) Check the back:** before fencing `back_matter`, **scan it for MCQ structure** — an appendix question bank is real content and must be parsed, not thrown away (§5i). Report what back matter holds per book. No usage section ⇒ say so; **do not invent conventions**. Convention ambiguous ⇒ **ask me**; I know these books. |
| `file-deduper` | `sonnet` | **The same PDF loose AND in a zip** (§5b warning) | Three tiers, at C1, **before any source row exists**: (1) exact SHA-256 → auto-collapse, record alias paths; (2) content fingerprint (page count + normalized text hash) → probable dup, report; (3) fuzzy MinHash over page shingles ≥ threshold → **possible dup, ASK ME — never auto-merge**. A false positive silently deletes a real source; a false negative silently doubles a score. Emit `dist/dedupe_report.md`. |
| `script-filter` | `haiku` | **Drop Arabic/Farsi/Bengali, keep Greek** (§5g) | Pure Unicode-block arithmetic, **zero tokens** — no LLM language detection needed. Span-level, threshold-based. **NEVER "strip non-ASCII"** — α/β/γ/µ/±/≥ are load-bearing in medicine. Log every drop to `work/script_drops.jsonl`. Whole-document non-Latin ⇒ flag and ask. |
| `extractor-textpdf` | `haiku` | Text PDFs → text + layout + page refs | PyMuPDF (text/annots) + Docling (layout/tables/order). Keep page numbers. Runs **after** `file-deduper`. |
| `extractor-scanned` | `haiku` | Scanned/photo pages → OCR | Docling OCR. Flag low-confidence pages; don't guess. |
| `extractor-screenshots` | `haiku` | App screenshots → OCR | Docling image pipeline; one screenshot = one page-unit. |
| `highlight-harvester` | `haiku` | Emphasized content | Digital: PyMuPDF highlight annots → text. Scanned: mask profile colours, OCR masked regions. Tag `is_highlighted`. |
| `parser-mcqbank` | `sonnet` | Question banks **and textbook MCQs** → structured MCQs | Runs over `body` **and** `back_matter` — **never** `front_matter` (§5i demo questions live there). **My textbooks carry MCQs too**: end-of-chapter sets and appendix question banks. A printed answer key is a **curated key at qbank-level trust regardless of the source's tier** (§5a printed-key rule) — set `mcqs.key_trust` from the item, not the source row. **Never re-derive a printed answer.** Can't locate an answer ⇒ quarantine; never invent. |
| `pyq-analyst` | `sonnet` | **Past-paper handling** | Detect PYQ items, extract **paper year** (filename → header → content, in that order), map each to subject/topic/concept. Unknown year ⇒ `year=null`, flag it, no recency credit (never guess a year). |
| `atomizer` | `sonnet` | Text → atomic grounded units | One fact/atom; every atom has `source_id`+`page_ref`; split, don't summarize away. |
| `alias-normalizer` | `sonnet` | **One entity = one node** (see §5e) | Two-stage. (1) Deterministic orthographic canonicalization in code (æ/œ→e, -ise/-ize, -our/-or) — no LLM. (2) Explicit synonym map from `profile.alias_hints` (historical BANs + UK/US divergences). Only genuinely novel unmapped candidates go to the LLM, and each proposal is written back to the profile for my review — **never silently merged**. |
| `staleness-checker` | `sonnet` | **Old key ≠ current answer** | For PYQs on `guideline_sensitive` concepts, compare the paper's key against the newest tier-3/4 source. Contradiction ⇒ set `key_conflict=1` + `key_conflict_note` with both positions and years. Never auto-overwrite; never silently serve a stale key. |
| `concept-grapher` | `sonnet` | **Brain-map agent** | Typed edges: causes/treats/contraindicates/associated_with/differential_of/complication_of/investigated_by/mechanism_of/risk_factor_for/first_line_for. Each grounded. Consumes `alias-normalizer` output: one entity = one node. Set `guideline_sensitive` on concepts whose correct answer is a management/target/threshold (vs stable basic science). |
| `corroboration-miner` | `sonnet` | **"Asked at multiple sites"** | Runs `cluster.py`: embed stems locally → ANN index → union-find. Emits per-cluster `distinct_source_count`, `distinct_work_count`, `distinct_publisher_count`, `pyq_years`, `member_count`. **Sends only the ambiguous cosine band to `cluster-adjudicator`.** ⚠ **Boilerplate net (§5i):** flag `is_boilerplate` on any cluster whose members are near-**verbatim identical** across works **and** all sit in the same early position — that's publisher front matter, not evidence. Exclude it from all counts **before** scoring. But do **not** filter on "appears in many books" alone: the same fact in *different words* in *body* content across the Cardiology and Nephrology books is **real corroboration and must count**. Position + verbatim identity are the discriminators. |
| `cluster-adjudicator` | `opus` | Borderline dup judgment | Only sees pairs in the ambiguous band (default cosine 0.82–0.92). "Same underlying question?" yes/no + one-line reason. Verdicts cached by pair-hash so re-runs are free and reproducible. |
| `importance-scorer` | `opus` (`effort: high`, `memory: project`) | **The ranking brain** | Does NOT score items individually. It: (1) calibrates the weight vector in the profile; (2) audits the 1–5 histogram for degeneracy; (3) reviews a sample of the proposed 5★ band and flags misfires; (4) resolves conflicting-signal cases. Writes calibration rationale to its project memory + `dist/importance_report.md`. All actual scores come from `importance.py`. |
| `grounding-validator` | `sonnet` | **Validity agent** | Semantic-overlap + verify vs cited page. Reject/flag ungrounded. Enforce evidence floor. |
| `dossier-compiler` | `sonnet` | **Topic-page builder** | Per canonical entity, gather ALL atoms across ALL subjects (alias-resolved), compose ONE long `body_md` sectioned by subject, embed figures, link MCQs/flashcards, list graph connections. Grounded; **pre-built**. |
| `gen-flashcards` | `sonnet` | Flashcards | Front prompt / back answer + one-line why; cloze supported; grounded. |
| `gen-mcqs` | `sonnet` | (a) pass-through curated; (b) generate best-of-five from atoms | New: 1 correct + 4 plausible distractors each w/ "why wrong"; no negative marking; generated answers grounded. Never alter a curated stem/key. |
| `gen-reels` | `sonnet` | Swipeable one-fact cards (no audio) | One hook/reel, grounded; highest-importance first. |
| `gen-blobs` | `sonnet` | Short readable passages + quiz-back each | 60–150 words, one theme, `est_read_seconds`; ships ≥1 `blob_quiz`. |
| `gen-interlink` | `sonnet` | Questions testing graph edges | From `concept_edges`, grounded. |
| `figure-handler` | `sonnet` | Extract/caption/link images | Blobs in `dist/figures/`; captions grounded; set `figure_id` on owner. |
| `dedupe-merger` | `sonnet` | Cross-source near-dup merge | Merge equivalent atoms/cards; keep best-sourced; **union the citations (never drop a source — source count IS the signal)**. |
| `qa-reviewer` | `opus` | Final audit | Grounding spot-check, blueprint balance, dead links, importance distribution, PYQ coverage, dossier coverage → `build_report.md`; summary → `JOURNAL.md`. |

`run.py` stages the pipeline with a **resumable checkpoint after every stage** (`work/<stage>.done` +
JSONL); re-runs skip completed stages unless source hashes changed. Parallelize extractors; rest is a DAG.

---

## 5. The importance model (document this in `spec/SCORING.md`)

Every playable item gets `importance ∈ {1,2,3,4,5}` plus the continuous `importance_score ∈ [0,1]` it
came from, plus `importance_signals_json` explaining **why** (so the app can show "2019 + 2023 papers ·
4 sources · you highlighted it"). **All of it is deterministic arithmetic in `importance.py`** — same
inputs, same scores, every run.

### 5a. Source tiers (from the profile, per folder)

| Tier | Kind | `tier_weight` | Answer-key trust |
|---|---|---|---|
| 1 | `past_paper` (official/recall) | 1.00 | Highest — but see reliability note |
| 2 | `qbank` | 0.75 | High |
| 3 | `textbook` | 0.60 | Reference for *prose* — **but see the printed-key rule below** |
| 4 | `reference` | 0.45 | Reference for *prose* — **but see the printed-key rule below** |

> ### ⚠️ The printed-key rule — tier is per-source, key trust is per-item
> **My textbooks contain MCQs too** — end-of-chapter questions and sometimes a whole appendix question
> bank. Read naively, the table above says a tier-3 textbook is "reference, not a key", and a textbook
> MCQ's **printed, authored answer would be distrusted or re-derived. That is wrong.**
>
> Tier describes how much I trust a source's **prose as evidence**. It says nothing about an MCQ that
> **ships with its own answer key printed next to it.** A question the author wrote, with the answer the
> author gave, is a curated key — *regardless of which folder the file came from*.
>
> **Rule:** any MCQ parsed with an accompanying answer key is `origin: curated` and its key is ground
> truth at **qbank-level trust**, whatever the source's tier. Set `mcqs.key_trust` from the *item*, not
> the source row. `S_tier` still uses the source's tier (5%, trivial); only key trust is decoupled.
> **Never re-derive a printed answer.** If a textbook MCQ's key contradicts other sources, that's
> `key_conflict` for me to arbitrate (below) — not something to quietly "fix".

**Reliability note (implement, don't skip):** past-paper material that is a *recall* rather than an
officially published paper can carry a wrong key. So `sources.reliability ∈ {official, recall,
unknown}`. Curated keys are ground truth, **but** if a `recall`-sourced key contradicts ≥2 independent
tier-3/4 sources, do not silently overwrite either — set `mcqs.key_conflict = 1`, record both, and list
it in `build_report.md` for me to arbitrate. The app shows a small "key disputed" note on such items.

### 5b. Signals (all computed, none LLM-rated)

| Signal | Definition | Notes |
|---|---|---|
| `S_pyq` | past-paper presence | `Σ over hits: exp(-λ · (current_year − paper_year))`, λ from profile (default 0.15/yr). `year=null` ⇒ counts as a hit with **no** recency bonus. **Heaviest weight.** |
| `S_corrob` | **distinct works** asserting it | `log1p(distinct_work_count) / log1p(N_works)`, scaled by `distinct_publisher_count` (different publishers > one publisher's two books). **This is "asked at multiple sites".** Counts **works, not files** — two editions of one textbook are one voice, and the same PDF found loose *and* in a zip is not evidence of anything. ⚠ **Exclude `is_boilerplate` and non-body zones BEFORE counting (§5i)** — the series' shared front-matter example otherwise maxes this signal. ⚠ **See the duplicate-file warning below — this is the signal that breaks.** |
| `S_freq` | raw cluster size | `log1p(member_count)`, **damped** — deliberately weaker than `S_corrob`. One qbank repeating a Q 20× must NOT beat 3 qbanks asking it once. ⚠ Boilerplate excluded from `member_count` first (§5i). |
| `S_emphasis` | **someone marked this as important** | Two sub-signals, mixed per `profile.importance.emphasis_mix`: (a) **my** highlights — `is_highlighted` × salience (density of highlight over the span); (b) **the book's own** markers — `book_flagged` × `book_salience`, learned from its "How to use this book" section (§5h). Both mean "a human flagged this", from different authorities: mine says *I* found it hard; the book's says *the author* says it's high-yield. |
| `S_blueprint` | subject weight | From the profile's blueprint weights. |
| `S_centrality` | graph centrality | PageRank over `concept_edges` — hub concepts (a drug touching 4 specialties) rank higher. |
| `S_tier` | best source tier | `max(tier_weight)` across asserting sources. |
| `S_trend` | **is this topic rising or dying?** | Compare mean PYQ year of a concept's hits against the corpus mean. `rising` (recent-skewed) / `steady` / `fading` (all hits old, nothing recent). Only computable because the papers are dated. A topic last asked 14 years ago is probably off the current blueprint — `fading` applies a **penalty**, not a bonus. |

```
raw   = w_pyq·S_pyq + w_corrob·S_corrob + w_freq·S_freq + w_emphasis·S_emphasis
      + w_blueprint·S_blueprint + w_centrality·S_centrality + w_tier·S_tier + w_trend·S_trend
score = clamp(raw / Σ|w|, 0, 1)        # → importance_score   (w_trend may be negative)
```

Weight names here map **1:1 onto `profile.importance.weights` keys** — no translation, no guessing.
Defaults (the `importance-scorer` may recalibrate and must justify any change):
`pyq 0.33 · corrob 0.24 · freq 0.07 · emphasis 0.11 · blueprint 0.07 · centrality 0.06 · tier 0.05 ·
trend 0.07`

> ### ⚠️ Duplicate files silently corrupt this score — fix it at ingest, not downstream
>
> My sources arrive as **ZIP archives and loose PDFs, and the same PDF will appear in both.** If one
> textbook gets registered as two `sources` rows, then **every fact in it gets
> `distinct_source_count = 2` instead of 1** — and `S_corrob` is 24% of the score. A single duplicated
> book fakes corroboration across thousands of atoms. The 5★ band fills with things that look
> cross-corroborated and are just one book counted twice. Nothing downstream catches this: the whole
> ranking is quietly wrong and looks fine.
>
> **`dedupe-merger` does not save you — it makes it worse.** It merges equivalent atoms and *unions
> their citations*, so two registrations of one book produce a merged atom carrying two "independent"
> citations. Downstream dedupe actively launders the duplicate into stronger evidence.
>
> Therefore: **deduplicate at the FILE level, at C1, before a single source row is written.** By the
> time text exists, it's already too late. See §7 C1.
>
> Related: **the same book in two editions is not two independent sources either.** Harrison's 20th and
> 21st corroborating each other is one voice, not two. Give `sources` a `work_id`; count corroboration
> over **distinct works**, and let `distinct_publisher_count` do the rest. If you find two editions of
> one work, **ask me** — don't decide alone whether to keep both.

### 5c. Bucketing to 1–5 + hard floors

Bucket `importance_score` on **absolute thresholds** (default `.80/.62/.42/.22` → 5/4/3/2, else 1),
then apply **hard floors that override**:

- MCQ verified as a past-paper question ⇒ `importance ≥ 4`
- Any item whose concept is hit in **≥2 distinct past-paper years, at least one within the last
  `recent_window_years` (default 10)** ⇒ `importance = 5`
- Atom corroborated by **≥3 distinct sources** AND highlighted ⇒ `importance ≥ 4`
- An item with **zero** corroboration and **zero** PYQ link ⇒ `importance ≤ 3` (single-source claims
  don't get to be top-tier, however confident the text sounds)
- **`fading` concepts** (all PYQ hits older than `recent_window_years`, none since) ⇒ capped at
  `importance ≤ 3` regardless of historical hit count, unless corroborated by ≥3 current sources.

> **Why the window exists (this was a real bug in the first draft).** Without it, a topic asked in 2009
> and 2011 and *never since* satisfies "≥2 distinct years" and gets force-promoted to 5★ — so a
> question retired from the blueprint 15 years ago would outrank one asked last year. The window plus
> the `fading` cap makes repetition count **only when it's still live**. This is exactly the failure
> mode dated papers let us detect, and undated ones would have hidden.

Then **audit the histogram**. If any bucket holds >45% or the 5★ band is empty, the distribution is
degenerate → `importance-scorer` recalibrates thresholds/weights, explains why, and re-runs. Final
histogram + weight vector go in `importance_report.md` and into `pack_manifest.importance_weights_json`.

### 5d. Propagation (score once, inherit everywhere)

Score **concepts and atoms first**, then propagate — do not score each asset type independently:

- `flashcard`, `reel` → inherit their source atom's score
- `blob` → weighted mean of its covered concepts, +bonus if any is 5★
- `mcq` → **direct signals win** (PYQ status is a property of the MCQ itself), fall back to its topic
- `interlink_quiz` → mean of the edge's two endpoint concepts
- `topic_page` → max of its constituent atoms (a dossier is as important as its most important fact)

### 5e. Nomenclature — why "generic names" does NOT mean "no alias problem"

My material uses UK generic (BNF/rINN) names. That removes brand↔generic splits, but **three real
splits remain**, and each one silently halves a dossier and doubles a concept node:

1. **Historical BANs in older papers.** The BAN→rINN harmonisation ran from 2003 and was largely
   complete by 2004. My past papers are *dated and span that boundary*, so pre-2004 material will read
   `frusemide`, `lignocaine`, `amoxycillin`, `methicillin` where current material reads `furosemide`,
   `lidocaine`, `amoxicillin`, `meticillin`. Same drug, different decade.
2. **The adrenaline/noradrenaline exception, which just changed again.** These two were deliberately
   *excluded* from the 2003 harmonisation (MHRA kept dual labelling, because `epinephrine` risked
   confusion with `ephedrine`). So 2004–2025 material reads `adrenaline/epinephrine`. Then BNF 92 /
   BNFC 2026-27 dropped the dual title to plain `adrenaline`, with `epinephrine` demoted to a synonym.
   **Three spellings of one drug across my corpus' lifespan.** Any US-sourced reference book says
   `epinephrine` outright.
3. **Orthography.** `haemoglobin`/`hemoglobin`, `oedema`/`edema`, `ischaemia`/`ischemia`,
   `anaemia`/`anemia`, `coeliac`/`celiac`, `oesophagus`/`esophagus`, `diarrhoea`/`diarrhea`,
   `tumour`/`tumor`, `leucocyte`/`leukocyte`, `foetus`/`fetus`, `aetiology`/`etiology`.

**Two mechanisms, in this order:**

- **(a) Deterministic orthographic canonicalizer** — pure code, no LLM. Compute a `canonical_key` per
  entity by normalising `ae|æ→e`, `oe|œ→e`, `-ise→-ize`, `-our→-or`, `-re→-er`, casefold, strip
  punctuation. Catches category 3 wholesale, free and reproducibly. **This is a match key only — never
  rewrite my displayed text.** UK spelling is what I sit the exam in; the app must still show
  `haemoglobin`.
- **(b) Explicit synonym map** — `profile.alias_hints`, seeded below. Catches 1 and 2, which no rule
  can derive.

Anything still unmatched that looks like a synonym gets **proposed to me**, appended to
`dist/alias_proposals.yaml` — never silently merged. A wrong merge corrupts the graph invisibly.

### 5f. What deliberately stays OUT of the pack

`importance` is a property of **the content** → it ships in the pack and is exam-generic.
**My personal weakness is not** → it lives only in `progress.db` on the phone. The app composes:

```
priority = importance_normalized × weakness      # weakness from FSRS retrievability + lapse rate
```

That keeps packs shareable/rebuildable and personalization local, with no runtime generation.

### 5g. Script filtering — drop Arabic/Farsi/Bengali, KEEP Greek and medical notation

Some of my PDFs carry text in **Arabic, Farsi, or Bengali** — watermarks, seller stamps, a previous
owner's annotations, sometimes whole sections. I want that ignored. But the obvious implementation is
a disaster:

> ### ⚠️ Do NOT implement this as "strip non-ASCII" or "keep only Latin"
> Medicine is full of non-ASCII characters that are **load-bearing**. A naive filter silently destroys:
> **α-blockers, β-blockers, γ-GT, ΔF508, µmol/L, °C, ±, ≥, ≤, →, α₁-antitrypsin, TNF-α, IFN-γ, β-hCG,
> 17α-hydroxylase, α-fetoprotein**. Strip the Greek block and you have quietly corrupted large parts of
> endocrinology, immunology, biochemistry and genetics — and it will look like clean text, so nobody
> notices until a flashcard says "-blocker".

**Filter by specific Unicode blocks, allowlist-style — never by "is it ASCII".**

Drop text dominated by:
- **Arabic script** — `U+0600–U+06FF`, `U+0750–U+077F`, `U+08A0–U+08FF`, `U+FB50–U+FDFF`,
  `U+FE70–U+FEFF`. *(This one range covers Arabic, Farsi/Persian and Urdu — Farsi is the Arabic script
  plus a few extra letters like پ چ ژ گ ی, all inside these blocks. There is no separate "Farsi block"
  to add.)*
- **Bengali** — `U+0980–U+09FF`.

**Explicitly preserve** (non-exhaustive — the rule is *allowlist what medicine uses*, not blocklist):
- Basic Latin + Latin-1 Supplement + Latin Extended-A (accented drug/eponym names: Sjögren, Behçet)
- **Greek and Coptic `U+0370–U+03FF`** — non-negotiable, see the box above
- General Punctuation, Superscripts/Subscripts, Arrows, Mathematical Operators, Letterlike Symbols
- Latin Extended Additional, Combining Diacritical Marks

**How to apply it:**
- **Span-level, threshold-based** — not document-level, not character-level. A page can be 95% English
  with an Arabic watermark; drop the watermark span, keep the page. Drop a span when the *proportion*
  of Arabic/Bengali characters exceeds `script_drop_threshold` (default 0.30 of letter characters), so
  one stray glyph never nukes a real sentence.
- **Configure OCR for English only** (`-l eng`). Pointing Tesseract at a non-Latin page without the
  language pack produces confident garbage that looks like text and will pollute atoms. Detect the
  script *first*, then decide whether to OCR at all.
- **Whole-document check at C1**: if a PDF is predominantly Arabic/Bengali, do **not** atomize it —
  flag it and **ask me** whether to skip it entirely. It may be a book I can't use, or a mislabelled
  file.
- **Same filter on figure captions and highlights** — an Arabic caption or a highlight over Bengali
  text follows the same rule.
- **Log every drop** to `work/script_drops.jsonl` with source + page + the dropped span, and report
  counts per document at C2. If a document is losing far more than expected, I want to know — that
  means the filter is eating something it shouldn't.

`profiles/*.yaml` carries `script_allow` / `script_drop` / `script_drop_threshold`, so a future exam in
another language just changes the profile, not the code.

### 5h. "How to use this book" — mine each book's own emphasis system

Most of my textbooks open with a section explaining their conventions: *How to use this book*, *Key to
symbols*, *Legend*, *Icons used in this book*. It tells you the author's own salience system — starred
topics, boxed high-yield facts, grey clinical-pearl panels, ★★★ frequency ratings, margin flags,
"exam favourite" callouts. **That is the author telling me what matters, and it's free signal sitting
in the front matter of every book I own.**

So, per source, **before extracting the body**:

1. **Locate and read the usage section** (`book-usage-analyst`, §4). Typically in the first ~20 pages.
2. **Extract the convention map** into a per-source `usage_profile_json` — e.g.
   `{"boxed_grey": "high_yield", "star_3": "most_asked", "margin_flag": "exam_favourite",
     "italic_panel": "clinical_pearl"}`.
3. **Hand it to the extractors**, so content matching those conventions is tagged `book_flagged=1`
   with a `book_salience` weight.
4. **Feed `S_emphasis`** (§5b) alongside my own highlights.

Rules:
- **No usage section? Say so and move on.** Do not invent conventions, and do not infer that bold text
  means high-yield just because it might. A guessed convention mis-weights an entire book.
- **Convention ambiguous?** ⛔ **Ask me.** I know these books; one sentence from me beats an hour of
  inference.
- Different books use **contradictory** systems (a grey box is high-yield in one, a side-note in
  another). `usage_profile` is **per source** — never global.
- A book's own emphasis is a *claim*, not proof. It still gets grounded like everything else, and it
  never overrides PYQ or corroboration evidence — it's 11% of the score shared with my highlights, not
  a trump card.
- Report at C2: per book, which conventions were found and how many atoms got `book_flagged`. If a
  book flags 60% of itself as high-yield, its markers are noise — tell me and I'll zero its weight.

**If I hand you a separate zip of usage notes** (I may extract these pages myself), look for
`sources/usage_notes/<source_stem>.{md,pdf}` and treat it as **authoritative over** anything inferred
from the book's own front matter — it means I've told you directly.

### 5i. ⚠️ The front-matter example trap — this one scores 5★ if you miss it

**My books are subject-wise (one per specialty), and every book in the series opens with the SAME
"how to use this book" pages — containing the same worked example text and the same demo MCQs, drawn
from one single subject.** Left alone, three separate features conspire to make this the
highest-ranked content in the entire pack:

| Feature | What it sees | Result |
|---|---|---|
| `corroboration-miner` | the same MCQ in ~14 different books | `distinct_work_count = 14` ⇒ **max `S_corrob`** — the strongest evidence signal in the model |
| `S_freq` | a 14-member cluster | inflated frequency on top |
| `book-usage-analyst` | the example sits inside the emphasis markers | `book_flagged = 1` — **by design**, since the example exists to *demonstrate* those markers |
| floor rule | "≥3 distinct sources + flagged ⇒ ≥4★" | promoted to **5★** |
| subject tagging | a cardiology example inside the Nephrology book | filed under **Nephrology** — wrong |

So a worked example whose only purpose is teaching me to read the book would top every feed, and would
look completely legitimate from the outside. **This is not a hypothetical; it is the default behaviour
of everything specified above.**

**Fix it in layers — belt and braces, because one net will leak:**

1. **Zone every page before atomizing.** Give each source a `body_start_page` and tag atoms with
   `content_zone ∈ {front_matter, body, back_matter}`. **Only `body` is ever atomized.** Front matter
   is read by `book-usage-analyst` for conventions and then **discarded as content**. This alone kills
   the bug — everything below is insurance.

> ### ⚠️ Zoning is for PROSE. It must not eat the question banks.
> **My textbooks contain MCQs — often end-of-chapter, sometimes an entire appendix question bank.**
> "Only body is atomized" is correct for *prose facts*. Applied bluntly to *questions*, it silently
> destroys hundreds of real MCQs, because an appendix sits in `back_matter` and the fence would throw
> it away without a word.
>
> **Atomization and MCQ parsing are different operations with different zone rules:**
>
> | Operation | Zones | Why |
> |---|---|---|
> | Prose atomization | `body` only | front matter is publisher boilerplate; back matter is index/refs |
> | **MCQ parsing** | **`body` AND `back_matter`** | **a question bank in an appendix is real content** |
> | Anything | never `front_matter` | that's where the §5i demo MCQs live — those are the fake ones |
>
> So: **before fencing off back matter, scan it for MCQ structure** (numbered stems + lettered options
> + an answer key or answer table). Found ⇒ parse as MCQs, `content_zone='back_matter'`, keep. Not
> found (just an index, glossary, references) ⇒ fence it as intended.
>
> **The asymmetry is deliberate and worth stating plainly:** a demo MCQ in *front* matter is fake and
> must be excluded (it's the same one in all 14 books). A question bank in *back* matter is real and
> must be kept. **Position tells you which — so get the zoning right rather than pattern-matching on
> "it's an MCQ outside the body".**
>
> **Report at C2a:** for every book, what back matter contains and whether an MCQ scan found questions
> there. ⛔ **If you're about to exclude back matter from a book, show me the page range and what you
> think is in it first.** Silently dropping 300 questions is exactly the kind of thing I won't notice
> until the exam.
2. **Find the boundary deterministically first.** The classic typographic tell: **front matter is
   paginated in roman numerals (i, ii, iii…) and the body restarts at arabic 1.** Also: the first
   "Chapter 1" heading, the end of the TOC. Propose the boundary per book and **⛔ show me the page
   numbers to confirm** — one glance from me is cheaper than any inference.
3. **Cross-book boilerplate net.** Independently of zoning: any near-duplicate cluster whose members
   are (a) near-**verbatim identical** across works *and* (b) all sitting in the **same early position**
   in each ⇒ `is_boilerplate = 1`. Publisher boilerplate, not evidence.
4. **`is_boilerplate` and `content_zone != 'body'` are EXCLUDED from the pack entirely** — not scored
   1★, *excluded*. A demo MCQ has zero study value; I never want to see it in the feed.
5. **They are also excluded from `distinct_work_count` and `member_count`** before `S_corrob` and
   `S_freq` are computed — otherwise the ghost still inflates the denominator.

**Distinguish boilerplate from real cross-subject corroboration — this is the subtle part.** A fact
about hypertension legitimately appearing in *both* the Cardiology and Nephrology books **is genuine
corroboration and must still count.** The difference is not "appears in many books" — it's:

- **Boilerplate** = near-**verbatim identical wording**, in **front matter**, same position each time.
- **Corroboration** = the **same claim in different words**, in **body** content, at varying positions.

**Position and verbatim-identity are the discriminators, not similarity alone.** If you filter purely
on "appears in many books", you will delete the real signal and keep the noise.

**Subject attribution:** since books are subject-wise, `sources.subject_id` is the **default subject**
for that book's body atoms. A body atom whose content clearly belongs elsewhere (the Cardiology book
discussing ACE-inhibitor renal effects) may be re-tagged by the classifier — **log every disagreement**,
don't silently reassign. The foreign-subject front-matter example never reaches this stage because
step 1 removed it.

**Report at C2**, per book: proposed `body_start_page`, pages excluded as front/back matter, and every
boilerplate cluster found with its member count. ⛔ I confirm before you atomize anything.

### 5j. Stable keys — anything I own must NOT be anchored to a row id  (PATCH 01)

`pack.db` is rebuilt on every recompile, so row IDs are not stable. Every row a human can attach
something to therefore carries a `stable_key`, derived from content and provenance:

```
stable_key = sha256(normalized_text + '|' + work_id + '|' + page_ref)[:16]
```

- Derive from **content + provenance only** — never from row order, insert sequence, or a counter.
- **Normalize before hashing** (casefold, collapse whitespace, strip punctuation) so trivial
  re-extraction differences don't mint a new key.
- Use **`work_id`, not `source_id`** — the same book re-supplied as a different file must produce the
  *same* key (§5b).
- Everything user-owned in `progress.db` — notes, stars, tags, mistakes, FSRS state — anchors to
  `stable_key`, **never to `id`**.
- On import, resolve `stable_key` → current `id` once and cache the mapping. A key that no longer
  resolves means that content left the pack: **keep the row, mark it orphaned, surface it. Never
  delete my data because the pack changed.**
- Report at C13: how many stable keys changed vs the previous pack. Large churn means the
  normalization is too brittle — tell me.

---

## 6. Data contracts

Two DBs, separated so re-importing content **never wipes progress**. Content DB is opened read-only and
**ATTACH**-ed beside the read-write progress DB.

### 6a. `spec/schema.sql` — content pack (READ-ONLY at runtime)

```sql
-- ── exam identity + config the APP reads (this is what makes the app generic) ──
CREATE TABLE pack_manifest (
  schema_version TEXT, pack_version TEXT,
  exam_code TEXT, exam_name TEXT,               -- e.g. 'MRCP_PART1', 'MRCP(UK) Part 1'
  built_at TEXT, checksum TEXT,
  feature_flags_json TEXT, counts_json TEXT,
  importance_weights_json TEXT,                 -- calibrated weights + thresholds (audit trail)
  importance_histogram_json TEXT
);
CREATE TABLE subjects ( id INTEGER PRIMARY KEY, name TEXT, blueprint_weight REAL, ord INTEGER );
CREATE TABLE topics   ( id INTEGER PRIMARY KEY, subject_id INTEGER, name TEXT );
CREATE TABLE tag_defs ( id INTEGER PRIMARY KEY, key TEXT, label TEXT, color TEXT,
  description TEXT, ord INTEGER );

CREATE TABLE sources (
  id INTEGER PRIMARY KEY, title TEXT,
  kind TEXT,                                     -- past_paper | qbank | textbook | reference
  tier INTEGER, tier_weight REAL,
  reliability TEXT,                              -- official | recall | unknown
  publisher TEXT, year INTEGER,                  -- paper year for past_paper; NULL if unknown
  edition TEXT, file_hash TEXT,                  -- sha256 of the canonical file
  work_id INTEGER,                               -- ⇦ corroboration counts DISTINCT WORKS, not files
                                                 --   (two editions of one book = one voice; §5b)
  origin_paths_json TEXT,                        -- every path this file was found at (zip + loose)
  dup_of_source_id INTEGER NULL,                 -- set only if kept for audit; excluded from counts
  primary_script TEXT,                           -- latin | arabic | bengali | mixed
  script_dropped_spans INTEGER DEFAULT 0,        -- how much §5g removed; audit signal
  subject_id INTEGER,                            -- books are subject-wise ⇒ default subject for body atoms
  body_start_page INTEGER,                       -- ⇦ everything before this is front matter (§5i)
  back_matter_start_page INTEGER,
  usage_profile_json TEXT                        -- the book's own emphasis conventions (§5h)
);
CREATE TABLE works (                             -- canonical bibliographic work
  id INTEGER PRIMARY KEY, title TEXT, publisher TEXT
);

-- ── grounded knowledge ──
CREATE TABLE atoms (
  id INTEGER PRIMARY KEY, subject_id INTEGER, topic_id INTEGER,
  stable_key TEXT UNIQUE,                         -- content-derived (§5j); survives rebuild. NEVER anchor user data to id.
  statement TEXT, detail_md TEXT,
  source_id INTEGER, page_ref TEXT,              -- grounding (required)
  is_highlighted INTEGER DEFAULT 0, salience REAL DEFAULT 0,
  book_flagged INTEGER DEFAULT 0,                -- the BOOK's own high-yield marker (§5h)
  book_flag_kind TEXT,                           -- which convention fired: star_3 | boxed_grey | ...
  book_salience REAL DEFAULT 0,
  content_zone TEXT DEFAULT 'body',              -- front_matter | body | back_matter (§5i)
  is_boilerplate INTEGER DEFAULT 0,              -- ⇦ series' shared demo content. EXCLUDED from pack
  subject_reassigned INTEGER DEFAULT 0,          -- content subject != book subject; logged, not silent
  distinct_source_count INTEGER DEFAULT 1,
  distinct_work_count INTEGER DEFAULT 1,         -- ⇦ THIS drives S_corrob, not source_count (§5b)
  distinct_publisher_count INTEGER DEFAULT 1,
  pyq_hit_count INTEGER DEFAULT 0, pyq_years_json TEXT,
  pyq_trend TEXT,                                -- rising | steady | fading | none
  pyq_last_year INTEGER,                         -- most recent paper year touching this
  centrality REAL DEFAULT 0,
  importance INTEGER DEFAULT 1,                  -- 1..5  ⇦ sortable
  importance_score REAL DEFAULT 0,               -- 0..1  continuous
  importance_signals_json TEXT,                  -- the "why" breakdown
  figure_id INTEGER NULL
);
CREATE TABLE atom_citations (                    -- unioned on merge; source count IS the signal
  atom_id INTEGER, source_id INTEGER, page_ref TEXT
);
CREATE TABLE concepts (
  id INTEGER PRIMARY KEY, name TEXT,             -- DISPLAY name: UK spelling, as I'll sit the exam
  stable_key TEXT UNIQUE,                         -- content-derived (§5j); survives rebuild.
  canonical_key TEXT,                            -- orthographic match key (§5e) — never displayed
  subject_id INTEGER, summary_md TEXT, aliases_json TEXT,
  guideline_sensitive INTEGER DEFAULT 0,         -- 1 = answer may go stale (targets/thresholds/mgmt)
  pyq_trend TEXT, pyq_last_year INTEGER,
  centrality REAL DEFAULT 0, importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0
);
CREATE INDEX idx_concept_canonical ON concepts(canonical_key);
CREATE TABLE concept_edges (
  id INTEGER PRIMARY KEY, src_concept_id INTEGER, dst_concept_id INTEGER,
  relation TEXT, rationale TEXT, source_id INTEGER
);

-- ── near-dup clustering (the corroboration engine) ──
CREATE TABLE dup_clusters (
  id INTEGER PRIMARY KEY, canonical_mcq_id INTEGER,
  member_count INTEGER, distinct_source_count INTEGER, distinct_work_count INTEGER,
  distinct_publisher_count INTEGER,
  is_boilerplate INTEGER DEFAULT 0,              -- verbatim + same early position across works (§5i)
  pyq_years_json TEXT, topic_id INTEGER
);
CREATE TABLE cluster_members ( cluster_id INTEGER, mcq_id INTEGER, source_id INTEGER );

-- ── pre-compiled cross-subject topic dossiers ("read up on X") ──
CREATE TABLE topic_pages (
  id INTEGER PRIMARY KEY, concept_id INTEGER, title TEXT, aliases_json TEXT,
  stable_key TEXT UNIQUE,                         -- content-derived (§5j); survives rebuild.
  body_md TEXT,                                  -- full long page, already assembled
  subjects_covered_json TEXT, figure_ids_json TEXT,
  related_mcq_ids_json TEXT, related_flashcard_ids_json TEXT,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0, importance_signals_json TEXT
);
CREATE TABLE topic_sections (
  id INTEGER PRIMARY KEY, topic_page_id INTEGER, subject_id INTEGER,
  heading TEXT, body_md TEXT, figure_ids_json TEXT, ord INTEGER
);

-- ── playable assets (every one carries importance) ──
CREATE TABLE flashcards ( id INTEGER PRIMARY KEY, subject_id INTEGER, atom_id INTEGER,
  front_md TEXT, back_md TEXT, is_cloze INTEGER DEFAULT 0,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0, importance_signals_json TEXT );

CREATE TABLE mcqs ( id INTEGER PRIMARY KEY, subject_id INTEGER, source_id INTEGER,
  stable_key TEXT UNIQUE,                         -- content-derived (§5j); survives rebuild.
  origin TEXT,                                   -- curated | generated
  key_trust TEXT,                                -- ⇦ from the ITEM, not the source tier (§5a)
                                                 --   a printed key in a textbook = qbank-level trust
  content_zone TEXT DEFAULT 'body',              -- body | back_matter. NEVER front_matter (§5i)
  is_pyq INTEGER DEFAULT 0, pyq_years_json TEXT,
  cluster_id INTEGER NULL, distinct_source_count INTEGER DEFAULT 1,
  distinct_work_count INTEGER DEFAULT 1,
  key_conflict INTEGER DEFAULT 0, key_conflict_note TEXT,
  stem_md TEXT, opt_a TEXT, opt_b TEXT, opt_c TEXT, opt_d TEXT, opt_e TEXT,
  correct_idx INTEGER, explanation_md TEXT, cognitive_level TEXT,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0, importance_signals_json TEXT,
  figure_id INTEGER NULL );
CREATE TABLE mcq_distractors ( mcq_id INTEGER, option_idx INTEGER, why_wrong TEXT );

CREATE TABLE reels ( id INTEGER PRIMARY KEY, subject_id INTEGER, atom_id INTEGER,
  hook_md TEXT, detail_md TEXT,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0, importance_signals_json TEXT );

CREATE TABLE text_blobs ( id INTEGER PRIMARY KEY, subject_id INTEGER, title TEXT, body_md TEXT,
  est_read_seconds INTEGER, concept_ids_json TEXT,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0, importance_signals_json TEXT );
CREATE TABLE blob_quizzes ( id INTEGER PRIMARY KEY, blob_id INTEGER, question_md TEXT,
  answer_md TEXT, opt_a TEXT, opt_b TEXT, opt_c TEXT, opt_d TEXT, correct_idx INTEGER );

CREATE TABLE interlink_quizzes ( id INTEGER PRIMARY KEY, edge_id INTEGER, subject_id INTEGER,
  question_md TEXT, answer_md TEXT,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0 );

CREATE TABLE figures ( id INTEGER PRIMARY KEY, path TEXT, caption_md TEXT, source_id INTEGER,
  page_ref TEXT );

-- ── convenience: one row per playable card so the app pages + sorts the feed cheaply ──
CREATE TABLE feed_items ( id INTEGER PRIMARY KEY,
  item_type TEXT,   -- reel | blob | image | flashcard | mcq | interlink
  ref_id INTEGER, subject_id INTEGER,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0,
  is_pyq INTEGER DEFAULT 0 );
CREATE INDEX idx_feed_importance ON feed_items(importance DESC, importance_score DESC);
CREATE INDEX idx_feed_subject    ON feed_items(subject_id, importance DESC);
CREATE INDEX idx_feed_pyq        ON feed_items(is_pyq, importance DESC);
```

### 6b. Progress DB — per exam, e.g. `progress_MRCP_PART1.db` (READ-WRITE, survives re-import)

```sql
-- PATCH 01 §5j: user-owned rows anchor on stable_key, NEVER a pack row id (ids are not stable).
CREATE TABLE fsrs_cards ( item_type TEXT, stable_key TEXT,
  stability REAL, difficulty REAL, due TEXT, last_review TEXT, state INTEGER, reps INTEGER,
  lapses INTEGER, PRIMARY KEY (item_type, stable_key) );
CREATE TABLE reviews ( id INTEGER PRIMARY KEY AUTOINCREMENT, item_type TEXT, stable_key TEXT,
  rating INTEGER, ts TEXT, elapsed_ms INTEGER );
CREATE TABLE mistakes ( id INTEGER PRIMARY KEY AUTOINCREMENT, item_type TEXT, stable_key TEXT,
  subject_id INTEGER, chosen_idx INTEGER, ts TEXT, resolved INTEGER DEFAULT 0 );
CREATE TABLE stars ( item_type TEXT, stable_key TEXT, ts TEXT, PRIMARY KEY (item_type, stable_key) );
CREATE TABLE item_tags ( item_type TEXT, stable_key TEXT, tag_key TEXT, ts TEXT,
  PRIMARY KEY (item_type, stable_key, tag_key) );
CREATE TABLE subject_mastery ( subject_id INTEGER PRIMARY KEY, mastery REAL, updated TEXT );
CREATE TABLE sessions ( id INTEGER PRIMARY KEY AUTOINCREMENT, mode TEXT, started TEXT, ended TEXT,
  seen INTEGER, correct INTEGER );
CREATE TABLE blob_reads ( blob_id INTEGER PRIMARY KEY, read_at TEXT, quizzed_at TEXT, reread_due TEXT );
CREATE TABLE feed_state ( key TEXT PRIMARY KEY, value TEXT );
CREATE TABLE settings ( key TEXT PRIMARY KEY, value TEXT );

-- PATCH 01 §A8/B1: my own notes (text + images), local, anchored by stable_key never id (§5j)
CREATE TABLE user_notes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  anchor_kind TEXT,                 -- atom | concept | mcq | topic_page | standalone
  anchor_stable_key TEXT,           -- NOT a row id. null when standalone
  subject_id INTEGER,               -- resolved at write time so orphans stay filterable
  title TEXT, body_md TEXT,
  created TEXT, updated TEXT,
  is_orphaned INTEGER DEFAULT 0,    -- anchor vanished on recompile: KEEP IT, flag it, never delete
  pinned INTEGER DEFAULT 0
);
CREATE TABLE user_note_images (
  id INTEGER PRIMARY KEY AUTOINCREMENT, note_id INTEGER,
  rel_path TEXT,                    -- app-private storage; not a blob, not a content:// URI
  caption TEXT, ord INTEGER
);
CREATE INDEX idx_note_anchor ON user_notes(anchor_kind, anchor_stable_key);
```

The app keeps a registry of imported packs (file + `exam_code` + progress-DB name) so it can list and
switch exams. Progress is namespaced per `exam_code` and never wiped.

---

## 7. Phase 1 build order (compiler) — ⛔ = wait for my go

> ## ✅ PHASE 1 IS COMPLETE — C0 THROUGH C13 ARE DONE (2026-07-16/17).
>
> **Everything in §7 below is the historical record of work that has already happened. Do not re-run
> it, do not re-plan it, and do not treat any ⛔ gate here as pending.** It is kept verbatim because it
> documents *how* the shipped pack was built (§10a: the spec lives on disk).
>
> **What exists now:** `compiler/dist/pack_MRCP_PART1.db` (gitignored — derived from copyrighted
> sources) — 86,702 atoms · 37,795 concepts + 67,795 edges · 14,455 MCQs (12,455 curated + 2,000
> generated) · 9,510 flashcards · 3,000 reels · 8,194 topic dossiers · **26,965 playable feed_items**
> · 147,146 stable_keys. The **PATCH-01 reproducibility gate is CLEARED** (two builds → byte-identical
> stable_key set + content checksum), so a rebuild never orphans progress or notes (§5j).
>
> **Known gaps, flagged not silent:** `text_blobs` / `blob_quizzes` / `interlink_quizzes` / `figures` /
> `dup_clusters` ship **empty** — those assets were trimmed at C11. **§8 A2's blob and image card types
> therefore have no data yet**; A2 must degrade gracefully rather than assume they exist.
>
> **The live front is Phase 2 (§8), on the laptop.** Details: `JOURNAL.md` tail (compiler history) and
> `compiler/.compiler_state.json`.

- **C0 · Spec + scaffold.** **FIRST, before anything else: `git init` + commit `.gitignore` (§11b), then
  check the repo root for my three dropped archives** (`textbooks*.zip`, `*mcq*.rar`, `papers*.zip`).
  **`git status` must show them ignored.** If any archive is already tracked, **stop and tell me** — a
  copyrighted zip in git history is a stop-and-fix, not a note for later. Then `git mv`/`mv` them into
  `compiler/sources/inbox/` and record their SHA-256 + original filenames in `.compiler_state.json`.
  **I drop them once at the root and never touch them again — moving them is your job, not mine.**
  Then write `spec/SPEC.md`, `schema.sql`, `manifest.schema.json`, `SCORING.md`,
  `profiles/mrcp_part1.yaml`, and the compiler skeleton (uv). **Run `echo $CLAUDE_CODE_SUBAGENT_MODEL`
  and report it** (it silently overrides agent frontmatter). **Verify the environment before anything
  else and report each result:** (a) a working **RAR extractor** — try `unar`, then `7z`, then
  `bsdtar`; confirm it reads **RAR5**, and tell me if none can (**my MCQ bank is a `.rar` — if RAR is
  broken, the whole tier-2 corpus is unreadable and there is no point continuing**); (b) `df -h` free
  space — three archives expanded plus `work/` JSONL plus figures needs several times their size;
  (c) OCR binaries and the local embedding model. Print the stage plan + routing table.

  **Also at C0 — reconcile with my existing setup (START HERE step 3):** read `~/.claude/CLAUDE.md` and
  report (a) agent-name collisions with my existing `mistake-tracker` / `docs-researcher`, (b) whether
  my global routing table fights §4's model split, (c) any global rule contradicting this spec.
  **My global and project rules win over this file — surface conflicts and ask; never silently
  resolve.** Then point **`docs-researcher`** at FSRS-Kotlin, Docling and the Message Batches API to
  populate `.claude/PLAYBOOK.md`'s API notes (verified snippet + gotchas + source URL + date read).
  Division of labour: **`BUILD_PROMPT.md` = the spec; `.claude/INDEX.md` = the map; `PLAYBOOK.md` =
  verified how-to + mistakes; `JOURNAL.md` = milestone log + resume path.** Route stumbles to
  `mistake-tracker` as we go. ⛔
- **C1 · Unpack → file-dedupe → classify.** This stage decides whether the whole ranking is valid. Do
  not rush it.
  - **C1a · Unpack the three drops.** C0 moved them into `sources/inbox/`. Expand each recursively into
    `work/unpacked/<drop_name>/` — **preserving which drop each file came from, because that's the
    strongest tier signal I can give you** (§7 C1c). Nested archives are expected: `textbooks.zip` may
    hold another `.rar`, `papers.zip` holds a `.rar` **plus loose PDFs**. Depth ≤8, abort past
    `max_expanded_gb`. Password-protected, corrupt, or non-PDF contents ⇒ **ask me**.
    > **⚠ Verify the RAR toolchain at C0, not here.** Python's `rarfile` needs an external binary and
    > **`unrar` is non-free — it is NOT installed by default on Debian/Ubuntu.** Check, in order:
    > `unar` (free, handles RAR5 — `apt install unar`), `7z` (`p7zip-full`, may need `p7zip-rar`), or
    > `bsdtar` (libarchive). **RAR5 archives fail silently on older tools.** My entire MCQ bank is a
    > `.rar` — resolve this before the run, not at 3am mid-pipeline.
  - **C1b · File-level dedupe (§5b — read the warning first).** The same PDF **will** appear in more
    than one drop. Collapse on exact SHA-256 automatically; report content-fingerprint matches; **ask
    me before merging anything only fuzzily matched.** Assign `work_id` so two editions of one book
    count as one voice.
    > **⚠ Cross-archive tier conflict — this one needs me, not a rule.** If the same PDF appears in
    > `textbooks.zip` **and** `papers.zip`, the two copies imply **different tiers** (3 vs 1), and tier
    > gates answer-key trust *and* the PYQ signal — **33% of the score**. Dedupe keeps one canonical
    > file, but **⛔ never pick the tier yourself. Show me every cross-drop collision with both implied
    > tiers and let me decide.** Silently choosing would inject fake past-paper evidence or discard
    > real evidence, and neither is visible downstream.

    Show me `dist/dedupe_report.md`: kept, collapsed, alias paths, cross-drop collisions, and anything
    uncertain. ⛔ **A wrong call here corrupts every importance score and nothing later will catch it.**
  - **C1c · Classify — provenance first, then content.** Over the *deduped canonical* files only. **The
    drop a file came from is a strong prior; use it, then refine:**

    | Drop | Default | What still needs deciding |
    |---|---|---|
    | `papers*.zip` | `kind: past_paper`, tier 1 | **the YEAR of each paper — never guess, leave null and flag** |
    | `*mcq*.rar` | `kind: qbank`, tier 2 | `subject_id` from the subject-wise folder names |
    | `textbooks*.zip` | tier 3/4 **mixed** | **⚠ classify per-file: `textbook` vs `pearls` vs `reference`** — one drop, three kinds. Do not blanket-assign. |

    > **⚠ `pearls` is its own kind, and it breaks a rule elsewhere.** A pearls/high-yield-facts book is
    > **pre-distilled high-yield content by construction** — the whole book is the emphasis. Two
    > consequences: (a) give `kind: pearls` a **source-level salience**, don't hunt for per-atom
    > markers it doesn't have; (b) **exempt it from `book_flag_noise_ceiling`** — that rule exists to
    > catch a book whose markers are meaningless because it flags everything, but a pearls book
    > legitimately *is* ~100% high-yield. Firing the noise rule on it would zero out exactly the book
    > most worth reading. **If you can't tell pearls from textbook from the file, ask me.**

    Then per file: publisher, reliability, `primary_script`, `subject_id`, and (past papers only)
    **year** — filename first, then PDF header, then content; **never guess a year**. Hash everything
    into `sources_seen` so unchanged files skip on re-run. Flag any predominantly Arabic/Bengali
    document and **ask** whether to skip it (§5g). ⛔ Show me the full table; I correct it.
- **C2 · Zone → usage-profile → ingest + OCR + script filter.** On a **1-PDF smoke test** first (I'll
  point you at one).
  - **C2a · Zone + learn (§5h/§5i).** Run `book-usage-analyst`: propose `body_start_page` per book
    (roman→arabic pagination flip, first "Chapter 1", end of TOC) and extract that book's own emphasis
    conventions. **⛔ Show me the proposed page boundaries and the convention map per book before
    anything is atomized.** ⚠ The series' shared worked example lives in front matter and scores 5★ if
    it leaks — this gate is the primary defence. ⚠ **Also report, per book, what's in `back_matter` and
    whether an MCQ scan found questions there** — my textbooks have end-of-chapter sets and sometimes
    an appendix question bank. **Never fence off back matter without showing me the page range and what
    you think it contains first**; silently dropping 300 real questions is a failure I won't detect
    until the exam.
  - **C2b · Extract.** Body zone only. Show 20 sample spans + page refs + OCR quality. Apply
    `script-filter` (§5g) and report per-document drop counts — **if a document is losing far more than
    expected, stop and show me; the filter is eating something it shouldn't.** OCR English-only; never
    OCR a page whose script you haven't checked. Report per book: pages excluded as front/back matter,
    and how many atoms got `book_flagged` (if a book flags >60% of itself, its markers are noise —
    tell me and I'll zero its weight). ⛔ then run the full deduped set.
- **C3 · Highlights** — digital annots + colour-mask OCR (colours from profile). Sample the hits.
- **C4 · Atomize + ground** — grounding report (% valid source/page, quarantined). ⛔ if the grounding
  rate is poor, surface it; don't push through.
- **C5 · Clustering + corroboration.** `cluster.py`: local embeddings → ANN (hnswlib/FAISS) →
  union-find. **Never all-pairs** at 15k+ scale. Only the ambiguous cosine band goes to
  `cluster-adjudicator` (opus); cache verdicts by pair-hash. **Run the §5i boilerplate net here too:**
  report every cluster flagged `is_boilerplate` with its member count and the works it spans, and
  confirm they're excluded from `distinct_work_count` and `member_count` before scoring. Report cluster
  count, size histogram, and the top-20 most-corroborated stems. ⛔ (this is the heart of "asked at
  multiple sites" — I want to eyeball it, and I want to see the boilerplate list to confirm nothing
  real got swept up).
- **C6 · PYQ analysis + trend + staleness.** Detect past-paper items, attach years (filename → header →
  content; **never guess**), map to concepts. Compute `pyq_trend` and `pyq_last_year` per concept. Then
  run `staleness-checker` over `guideline_sensitive` concepts: any old paper key contradicting the
  newest tier-3/4 source ⇒ `key_conflict` + note (never auto-overwrite). Report: PYQ count, per-year
  histogram, top-30 **rising** topics, the **fading** list (asked historically, dead since — I want to
  see what I can safely skip), and every `key_conflict` for me to arbitrate. ⛔
- **C7 · Dedupe/merge** across sources — **union citations, never drop a source**. Report merge stats.
- **C8 · Alias normalization + concept graph.** Run `alias-normalizer` (canonicalizer → synonym map →
  proposals) BEFORE graph building, else nodes split. Show me `dist/alias_proposals.yaml` for any
  unmapped candidates. ⛔ Then build the graph + PageRank centrality; sample subgraph; verify that a
  known multi-spelling entity (e.g. adrenaline) resolved to **one** node.
- **C9 · Importance scoring.** Run `importance.py` over everything; then `importance-scorer` (opus)
  audits the histogram, samples the 5★ band, recalibrates if degenerate, and writes
  `dist/importance_report.md`. Show me: histogram, weight vector, top-30 5★ items with their `why`. ⛔
- **C10 · Topic dossiers** — sample one (a drug spanning pharmacology + cardiology + nephrology).
- **C11 · Generate assets** — flashcards, curated + generated MCQs, reels, blobs + quiz-backs,
  interlink, figures; each paired with `grounding-validator`; log reject rates. **Batch + checkpoint.**
  ⛔ before mass-generation from the 15k bank (confirm counts / token budget with me).
- **C12 · Validate + QA** (`qa-reviewer`, opus) → `dist/build_report.md`.
- **C13 · Package** → `pack_MRCP_PART1.db` + `figures/` + `manifest.json`. Incremental upsert. Counts.

**Acceptance:** pack opens; manifest carries the exam profile + weight vector + histogram; every asset
resolves to ≥1 grounded atom; **every playable row has `importance ∈ 1..5` and a populated
`importance_signals_json`**; every PYQ-linked MCQ is ≥4★; curated keys preserved verbatim (conflicts
flagged, not overwritten); `feed_items` populated + indexed; **re-running on unchanged inputs yields
byte-identical scores and no duplicate rows**.

---

## 8. Phase 2 build order (app) — generic offline player

Stack: Kotlin, Compose (**Material3**), Room (content DB attached read-only), **FSRS-Kotlin (FSRS-6)**,
Compose Canvas for the concept map, DataStore for settings. No network libs, no AI SDKs.

> **Where Phase 2 is built (PATCH 02, 2026-07-17):** on the **laptop** (Android Studio + a physical
> phone), not the Hetzner box — see §0 `build_env`. The **CLI-buildable intent binds unchanged**:
> `./gradlew assembleDebug` must work from a terminal with no IDE-only configuration. Only "over SSH"
> is moot. The app's human log is **`JOURNAL_APP.md`** (never `JOURNAL.md` — that one is the
> compiler's, written by `state.py` on the box).
>
> **Status: A0 ✅ DONE (2026-07-16/17). A1 is next.** Do not re-do A0; do not resequence §8.

- **A0 · Skeleton + offline/no-background guarantees.** ✅ **DONE 2026-07-16/17** — gate closed
  (keystore generated + backed up off-machine). Details in `JOURNAL_APP.md` + `app/README.md`.
  - Buildable via `./gradlew assembleDebug` from the laptop CLI; SDK/cmdline setup documented in
    `app/README.md`. ✅ Enforced by an included build (`build-logic/`): every `assemble<Variant>`
    depends on a merged-manifest parse + a runtime-classpath denylist, so a violating APK **cannot be
    produced** — proven by deliberate sabotage (INTERNET, `<service>`, okhttp, retrofit all caught).
  - Manifest: **no INTERNET permission**; **no services, no WorkManager/JobScheduler, no boot receiver,
    no alarms, no wake locks.** CI/lint check fails the build if a networking dep/permission or a
    background component appears.
  - FSRS-6 dep: use `io.github.open-spaced-repetition:fsrs:1.0.0` (Maven Central — the MIT-licensed
    Java port, fully usable from Kotlin; verified 2026-07-15). ⚠ Do NOT vendor the `FSRS-Kotlin`
    repo — it is broken, uncompilable app code (imports classes from an unrelated app). See
    `.claude/PLAYBOOK.md` API notes. Pin the version; one unit test scheduling Again/Hard/Good/Easy.
    Java `record` interop: call `result.card()` / `result.reviewLog()` as methods, not properties.
- **A1 · Pack loading = generic.** ⇦ **NEXT STAGE.** Import via SAF; read `pack_manifest` and **build
  the UI config from it** (exam name in app bar, subject filters, tag chips from `tag_defs`). Per-exam
  progress DB, never overwritten. "Switch exam" screen lists imported packs.
  - **⚠ Nothing exam-specific may enter the app — ever.** The word "MRCP" must not appear in any
    resource, constant, or layout; every exam-facing label resolves from `pack_manifest` at runtime.
    This is the §0 `multi_pack` / `first_exam` contract and it is the whole reason the app can play a
    future pack (MRCP Part 2, USMLE, another subject) **with no code change**. Verified clean at A0 —
    keep it that way. A single hardcoded string here silently converts a generic player into a
    single-exam app.
  - **Schema-version gate (PATCH 02 — required).** On import, read `pack_manifest.schema_version` and
    the DB's `PRAGMA user_version` (currently **1**) and **verify them before opening anything else**.
    A pack from a newer/unknown schema must be **refused with a clear message naming both versions** —
    never a crash, never a partial load, never a silent mis-read of a renamed column. Pin the supported
    version(s) in one constant. *Why this is not optional:* a future pack built against a bumped schema
    is the exact case a naive loader corrupts, and it is the failure mode that turns "works with other
    packs" from a promise into a hope.
  - **Degrade gracefully on empty tables.** `text_blobs`, `blob_quizzes`, `interlink_quizzes`,
    `figures` and `dup_clusters` ship **empty** in the current pack (trimmed at C11 — see §7). Absent
    ≠ broken: a pack missing an asset type must simply not offer it. Do not assume any table is
    populated; drive availability off `counts_json` / actual rows.
  - **§11e progress export/import belongs HERE, not at A7** — §11e's own words: *"Treat it as part of
    A1, not a nice-to-have for later."* Export/import is per-pack, exactly like the progress DB. This
    is not a resequencing of §8; it is A1 doing what §11e already assigned it. **Now urgent:** a real
    26,965-item pack exists, so the moment it is studied, progress becomes real and losable (§11d).
- **A2 · Study Feed (build first, make excellent).** Infinite vertical pager; per-type composables:
  - **Reel** — hook; tap to reveal; swipe; got-it/shaky → FSRS.
  - **Text blob** — passage + read-progress + `est_read_seconds`; on scroll-past, schedule its
    `blob_quiz` a few cards later and set a re-read to resurface **in-app next session** (no notification).
  - **Image** — figure + caption; pinch-zoom; tap → linked atom.
  - **Flashcard** — tap-flip; 4-button rating → FSRS.
  - **MCQ** — best-of-five; pick → **answer revealed inline** + explanation + per-distractor "why wrong";
    → FSRS; wrong → local mistake ledger. No negative marking. Show "key disputed" if `key_conflict`.
  - **Importance is visible on every card**: a compact 1–5 indicator, plus a **PYQ badge** with years
    (e.g. "Asked 2019 · 2023"). Tap the indicator → a small sheet rendering `importance_signals_json`
    ("2 past papers · 4 distinct sources · highlighted · hub concept").
  - **Filter chips:** All together · Subject ▾ · My mistakes · Read only · MCQs only · Flashcards only ·
    **PYQ only** · **5★ only** · **4★+** · Starred · Tag ▾.
  - **Sort control:** Smart (FSRS + importance mix, default) · Importance ↓ · Importance ↑ ·
    **Priority** (importance × weakness) · Random.
  - **One-tap actions on every card:** ⭐ star + tag chips from `tag_defs`, single tap to apply/remove.
- **A3 · Feed ordering engine.** Compose next card by: (1) FSRS due pool cross-type, most urgent first;
  (2) **importance weighting** for new/among-equal items (5★ and PYQ surface earliest); (3) variety
  rules (never >2 same type in a row; land blob quiz-backs at the right delay; sprinkle reels for
  pacing); (4) daily new-item drip + burst length. When an explicit sort is chosen it overrides (2).
  Persist a recent-type ring buffer in `feed_state`.
- **A4 · Topic Dossier reader.** Library/Search screen (**sortable by importance**): search entity by
  name/alias → open pre-built `topic_pages.body_md` as one long scrollable page with **inline diagrams**,
  collapsible per-subject sections (`topic_sections`) + jump-nav, links to that entity's MCQs/flashcards.
  Pure render — nothing generated.
- **A5 · Concept map.** Force-directed (Fruchterman–Reingold) on pan/zoom Compose Canvas; **node size
  scales with centrality, colour with importance**; tap node → summary + typed edges → "Quiz me on
  these links" pulls `interlink_quizzes`.
- **A6 · Mistakes, mastery, stats.** Mistakes review = feed filtered to unresolved `mistakes`,
  **filterable by star/tag and sortable by importance**; re-drill until correct N times → resolved.
  Per-subject mastery bars (FSRS stability + accuracy) + forgetting-curve view + a "high-importance
  gaps" list (5★ items you keep failing). Local gamification: streaks, daily goal, XP — tasteful.
- **A7 · Settings + polish.** **AMOLED true-black default** (pure `#000000` surfaces, high-contrast
  text, restrained bright fills; Material3 dark scheme, dynamic-color off); light/dim toggle. Retention
  target, feed intensity/burst, importance-first toggle, re-import/switch pack, optional local
  FSRS-weight optimization from my own review history. **Export / Import progress (§11e) — built at
  A1** (PATCH 02, per §11e's own "part of A1"); A7 only surfaces its Settings entry points + the
  post-session export nudge — it must not defer, re-implement, or wait on it. Icon, empty states,
  **release APK via Gradle CLI**.

**Acceptance (end-to-end):**
1. Import `pack_MRCP_PART1.db`; app bar shows exam name; filters + tag chips come from the pack.
2. Mixed feed shows all six item types; every card shows its 1–5 importance; PYQs show a year badge.
3. Tap an importance indicator → the "why" breakdown renders from `importance_signals_json`.
4. "5★ only" and "PYQ only" filters work; Importance ↓ sort works; Priority sort differs from
   Importance ↓ (proving weakness is folded in locally).
5. ⭐/tag a card in one tap; it persists across restart and appears in Mistakes filters.
6. Answer an MCQ → explanation → wrong answer persists in Mistakes → re-surfaces via FSRS.
7. Read a blob → quiz-back appears a few cards on → blob re-scheduled next session.
8. Search a drug → dossier shows cross-subject sections + diagrams, instantly, no generation.
9. **Airplane mode on — everything works.** Force-stop, reopen — no background process ran.
10. Import a *second* pack (different exam) → switch; each keeps its own progress.
11. Re-import an updated MRCP pack → progress/streaks/FSRS preserved.
12. **Export progress → wipe app data → reinstall → import progress → every mistake, tag, star, streak
    and FSRS due-date is back.** This is the §11d insurance policy; prove it works.
13. The release APK installs on my phone from a clean sideload and opens without a network connection
    ever being available.

---

## 9. Exam profile (this is how "the app stays the same, the data changes")

`profiles/mrcp_part1.yaml`:

```yaml
exam_code: MRCP_PART1
exam_name: "MRCP(UK) Part 1"
blueprint_source_url: ""          # fetch current Federation Part 1 weights at C0; else fallback + flag
mcq_style: best_of_five
negative_marking: false

source_tiers:                      # provenance (which drop) is the prior; folders refine it (C1c)
  past_papers: {tier: 1, tier_weight: 1.00, default_reliability: recall}   # from papers*.zip
  qbanks:      {tier: 2, tier_weight: 0.75, default_reliability: unknown}  # from *mcq*.rar
  pearls:      {tier: 3, tier_weight: 0.65, default_reliability: unknown,
                source_salience: 0.75,       # whole book is high-yield by construction (§7 C1c)
                exempt_noise_ceiling: true}  # ⚠ do NOT zero it for flagging ~everything — that's correct
  textbooks:   {tier: 3, tier_weight: 0.60, default_reliability: unknown}
  references:  {tier: 4, tier_weight: 0.45, default_reliability: unknown}

drops:                             # what I dropped at the repo root; C0 moves these to sources/inbox/
  - {glob: "textbooks*.zip", default_kinds: [textbook, pearls, reference], classify: per_file}
  - {glob: "*mcq*.rar",      default_kinds: [qbank], subject_from: folder_names}
  - {glob: "papers*.zip",    default_kinds: [past_paper], require: year_per_file}

importance:
  weights: {pyq: 0.33, corrob: 0.24, freq: 0.07, emphasis: 0.11,
            blueprint: 0.07, centrality: 0.06, tier: 0.05, trend: 0.07}
  emphasis_mix: {my_highlight: 0.6, book_flag: 0.4}   # §5b — both mean "a human marked this"
  recency_lambda: 0.15             # per year decay on past-paper hits
  recent_window_years: 10          # "still live" window — drives the multi-year floor + fading cap
  thresholds: {five: 0.80, four: 0.62, three: 0.42, two: 0.22}
  cluster_ambiguous_band: [0.82, 0.92]   # only this cosine band goes to opus adjudication
  floors:
    pyq_min_importance: 4
    multi_year_pyq_forces: 5       # requires ≥1 hit inside recent_window_years (see SCORING.md)
    uncorroborated_max: 3
    fading_max: 3                  # historically asked, dead since ⇒ capped

staleness:
  enabled: true
  guideline_sensitive_cues: ["first-line", "target", "threshold", "recommended", "guideline",
                             "management of", "treatment of", "screening", "cut-off"]
  # An old paper's key on these topics may be outdated. Flag, never auto-overwrite.

nomenclature:
  canonicalize_orthography: true   # deterministic ae/oe→e, -ise→-ize, -our→-or (match key only)
  display_convention: uk           # ALWAYS display UK spelling — that's what I sit the exam in
  alias_hints:                     # what no rule can derive. Extend as the compiler proposes.
    # ── the 2003 BAN→rINN switch: my older papers predate it ──
    - [furosemide, frusemide]
    - [lidocaine, lignocaine]
    - [amoxicillin, amoxycillin]
    - [meticillin, methicillin]
    - [ciclosporin, cyclosporine, cyclosporin]
    - [colestyramine, cholestyramine]
    - [chlorphenamine, chlorpheniramine]
    - [beclometasone, beclomethasone]
    - [dosulepin, dothiepin]
    - [thiopental, thiopentone]
    - [phenobarbital, phenobarbitone]
    - [sulfasalazine, salazopyrin]
    # ── the two rINN exceptions: dual-labelled 2004–2025, single-titled from BNF 92 ──
    - [adrenaline, epinephrine, "adrenaline/epinephrine"]
    - [noradrenaline, norepinephrine, "noradrenaline/norepinephrine"]
    # ── UK↔US divergences (bite if any reference book is US-sourced, e.g. Harrison's) ──
    - [paracetamol, acetaminophen]
    - [salbutamol, albuterol]
    - [glibenclamide, glyburide]
    - [pethidine, meperidine]
    - [rifampicin, rifampin]
    - [isoprenaline, isoproterenol]
    - [oestrogen, estrogen]
    - [co-trimoxazole, "trimethoprim-sulfamethoxazole", TMP-SMX]
    # ── common abbreviation ↔ full name ──
    - [glyceryl trinitrate, GTN, nitroglycerin]

scripts:                           # §5g — allowlist, NOT "strip non-ASCII"
  drop:  [arabic, bengali]         # the arabic block covers Farsi/Persian/Urdu too — no separate block
  allow: [latin, latin_ext, greek, punctuation, super_subscript, arrows, math_ops, diacritics]
  drop_threshold: 0.30             # drop a span when >30% of its letters sit in a dropped block
  ocr_languages: [eng]             # never point OCR at a script you haven't detected first
  whole_doc_nonlatin: ask          # a predominantly non-Latin PDF ⇒ stop and ask, don't skip silently

dedupe:                            # §5b — the same pdf loose AND inside a zip
  exact_hash: auto_collapse        # sha256 identical ⇒ collapse without asking
  fingerprint: report              # page count + normalized text hash ⇒ report, then ask
  fuzzy_minhash_threshold: 0.90    # ≥ this ⇒ POSSIBLE dup ⇒ ASK ME. never auto-merge
  editions_of_same_work: ask       # Harrison's 20th + 21st = one voice, not two. ask before deciding
  cross_drop_tier_conflict: ask    # ⛔ same pdf in two drops implying different tiers ⇒ I decide (C1b)

archives:                          # §7 C1a — I drop ONE zip holding pdfs + rar + nested zips
  inbox: sources/inbox/            # drop zone; the compiler unpacks, I never unpack by hand
  formats: [zip, rar, 7z, tar]
  max_depth: 8                     # nested archive recursion limit
  max_expanded_gb: 60              # archive-bomb guard; abort + report if exceeded
  on_password: ask                 # never skip a locked archive silently
  on_corrupt: ask
  ignore_globs: ["__MACOSX/*", "*.DS_Store", "Thumbs.db", "*.url", "*.lnk"]

zoning:                            # §5i — the front-matter example trap
  detect_body_start: [roman_to_arabic_pagination, first_chapter_heading, toc_end]
  confirm_boundaries: ask          # ⛔ show me the page numbers per book before atomizing
  atomize_zones: [body]            # PROSE only. front_matter is read for conventions, never content
  mcq_scan_zones: [body, back_matter]    # ⇦ textbook MCQs: end-of-chapter AND appendix question banks
                                         #   NEVER front_matter — that's where the §5i demo MCQs live
  confirm_back_matter_exclusion: ask     # ⛔ show me the page range before fencing off any back matter
  boilerplate_verbatim_threshold: 0.97   # near-identical wording across works…
  boilerplate_position_window: 30        # …AND within the first N pages of each ⇒ boilerplate
  exclude_boilerplate_from_pack: true    # not 1★ — gone. a demo MCQ has zero study value
  book_flag_noise_ceiling: 0.60    # a book flagging >60% of itself as high-yield = noise ⇒ tell me

subjects:                          # fallback taxonomy; real weights filled from blueprint at C0
  - {name: "Cardiology", weight: null}
  - {name: "Clinical sciences", weight: null,
     subtopics: ["clinical anatomy","biochemistry & metabolism","physiology",
                 "cell & molecular biology","genetics","immunology","statistics & EBM"]}
  - {name: "Clinical pharmacology, therapeutics & toxicology", weight: null}
  - {name: "Dermatology", weight: null}
  - {name: "Endocrinology", weight: null}
  - {name: "Gastroenterology & hepatology", weight: null}
  - {name: "Geriatric medicine", weight: null}
  - {name: "Haematology", weight: null}
  - {name: "Infectious diseases (incl. GUM/HIV)", weight: null}
  - {name: "Nephrology", weight: null}
  - {name: "Neurology (incl. ophthalmology)", weight: null}
  - {name: "Psychiatry", weight: null}
  - {name: "Respiratory medicine", weight: null}
  - {name: "Rheumatology", weight: null}

highlight_colors: {yellow: true, green: true, pink: true}

tags:                              # the 1-tap tags the app renders (rename freely)
  - {key: careless, label: "Careless slip",   color: "#FBBF24", description: "Knew it, misread/misclicked"}
  - {key: gap,      label: "Knowledge gap",    color: "#EF4444", description: "Didn't know the fact"}
  - {key: confused, label: "Mixed up options", color: "#8B5CF6", description: "Couldn't discriminate"}
  - {key: guessed,  label: "Guessed",          color: "#3B82F6", description: "Right/wrong by luck"}
  - {key: revisit,  label: "Must revisit",     color: "#10B981", description: "High-yield / weak, come back"}

alias_hints: []                    # drug synonyms / brand↔generic pairs to help dossier + graph
```

**To add a new exam:** copy this file, edit taxonomy/tiers/weights/tags, drop that exam's PDFs into
`sources/`, re-run the compiler. Same compiler code, same app — a new pack. Basic/clinical sciences are
~a fifth of MRCP Part 1, so weight that bucket properly; but let the **PYQ + corroboration signals**
dominate, since they reflect what's actually asked in *my* material.

---

## 10. Durability, resumption, and token budget — READ FIRST

I am afraid of two different things and they need two different fixes. **Losing work** is a durability
problem. **Running out of tokens** is a budget problem. Solving one does not solve the other.

### 10a. The rule that makes compaction a non-event

> **State lives on disk, never in the conversation.** Context is disposable. If the session compacts,
> crashes, or I close the laptop, the next session must be able to resume from disk **alone** — with no
> memory of what was said. If resuming requires re-reading the transcript, the design is wrong.

This inverts the usual worry. Don't race to save state *before* compaction — save it **after every
stage completes**, so by the time compaction happens there is nothing left to lose. Compaction then
costs nothing. Concretely, after **every** stage and **every** batch, write:

```jsonc
// compiler/.compiler_state.json   ← the machine-readable source of truth
{
  "schema": 1,
  "updated": "2026-07-15T14:02:11Z",
  "stage": "C11",                       // current stage id
  "completed": ["C0","C1", "...", "C10"],
  "next_action": "resume asset generation from mcq cursor 6200",
  "sources_seen": {"harrison.pdf": "sha256:…", "…": "…"},   // hash → skip unchanged on re-run
  "batches": [                          // Batch API jobs in flight — survive ANY session death
    {"id": "msgbatch_01…", "stage": "C11", "kind": "gen_mcq",
     "submitted": "2026-07-15T13:40:00Z", "status": "in_progress",
     "cursor": {"from": 6000, "to": 7000}}
  ],
  "cursors": {"atomize_page": 4310, "gen_mcq": 6200},
  "blocked_on": null,                   // set to a question string when waiting at a ⛔ gate
  "token_spend_estimate_usd": 41.20
}
```

Rules for it: write it **atomically** (temp file + `os.replace`) so a kill mid-write can't corrupt it;
treat it as append-mostly; and never let it disagree with `work/<stage>.done`. `JOURNAL.md` stays
**human prose** — one appended entry per milestone (what was built, decisions, reject rates, counts).
The JSON is what resumes the build; the journal is what I read. **Maintain both — they are not
substitutes.**

### 10b. Hooks (verified against the Claude Code hooks reference)

Write these as standalone scripts in `.claude/hooks/`, registered in `.claude/settings.json` — not as
inline command strings.

| Hook | Matcher | Job |
|---|---|---|
| `SessionStart` | `startup`, `resume`, `compact` | **This is the resume mechanism.** Its stdout is injected into my context. Emit `.compiler_state.json` + the last ~20 lines of `JOURNAL.md` + any in-flight batch IDs. **Keep it under 10,000 characters** — that's the injection cap. Never dump the whole journal. It re-runs on resume with `source: "resume"`, so the state it injects is always fresh. |
| `PreCompact` | `auto`, `manual` | **Backstop only.** Fires immediately before compaction (auto = context filled, manual = `/compact`). It cannot block compaction. Flush anything in-flight to `.compiler_state.json` and append a `**[compacted at HH:MM]**` marker to `JOURNAL.md`. Keep it fast and synchronous so it finishes before compaction proceeds. |
| `SessionEnd` | — | Log the stop reason; flush state. Cannot block termination. |
| `SubagentStop` | — | Append the subagent's stage result to `JOURNAL.md` so a subagent's work is durable even if the parent dies. |

**Honest limitation, since I asked for a 30% trigger:** there is **no native hook that fires at a
context-remaining threshold**. `PreCompact` fires *at* compaction, not before it. If I want compaction
to trigger earlier, tune `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` — but if §10a is implemented properly, the
threshold is irrelevant, because nothing valuable is ever only in context. Build §10a first; treat the
hooks as insurance, not as the plan.

### 10c. Where the tokens actually go — and the big lever

**Most of this compiler should never put a PDF into a context window at all.** These stages are pure
Python and cost **zero tokens**:

| Stage | Mechanism | Tokens |
|---|---|---|
| PDF/OCR extraction | Docling / PyMuPDF | **0** |
| Highlight harvesting | PyMuPDF annots + colour mask | **0** |
| Near-dup clustering | local embeddings + hnswlib/FAISS | **0** |
| Dedupe / merge | embeddings + union-find | **0** |
| Centrality | PageRank | **0** |
| Importance scoring | arithmetic (§5) | **0** |
| Packaging | SQLite writes | **0** |

> **A 500-page PDF must never enter a context window.** The extractor subagents **write and run
> scripts**; they do not read pages. If a subagent is reading book text into its context to "process"
> it, that is a bug — stop and fix the pipeline. This single rule is worth more than every other
> optimization here combined.

Genuine LLM work is only: atomization (text → grounded facts), asset generation (cards / MCQs / blobs /
dossiers), grounding validation, and Opus judgment (calibration, adjudication, QA).

### 10d. Split the two budgets

At 15+ PDFs (plausibly 4,000–6,000+ pages) and 15k+ MCQs, atomization and generation run to **millions
of tokens**. Running that *through Claude Code subagents* spends my Claude Code allowance on
mechanical bulk work — which is exactly what I'm afraid of. So separate them:

- **Claude Code budget** → writing the compiler, orchestrating stages, debugging, and the Opus judgment
  calls. Bounded and small. This is where subagents stay valuable (isolated context, tool restriction).
- **Compile budget** → the compiler itself calls the Anthropic **Message Batches API** directly from
  Python, BYOK, metered, at **50% off both input and output tokens** on every model. Results land
  within a 24-hour SLA, most much sooner.

This keeps everything I asked for — Claude Code, subagents, judgment routing — while moving the
mechanical millions onto the cheapest transport available.

**Batch mechanics the compiler must implement:**
- One `custom_id` per request, matching a durable row id. **Result order is not guaranteed** —
  reconcile by `custom_id`, never by position. (This is the classic indexing bug; it will silently
  mis-pair atoms with pages.)
- **Persist every batch ID to `.compiler_state.json` the moment it is submitted, before polling.** A
  submitted batch survives session death; an unrecorded batch ID is money set on fire.
- Poll and resume: on restart, re-poll known batch IDs before submitting anything new.
- **Design for partial failure** — requests fail individually, and errors arrive hours later in a flat
  result set, not at call time. Retry per `custom_id`.
- **Stack prompt caching with batch.** Cached input bills at a fraction of base, and the batch discount
  applies on top — the grounding/atomization system prompt is identical across thousands of requests,
  so cache it.
- Route by model: Haiku for atomization/extraction cleanup, Sonnet for generation, Opus only for §4's
  judgment agents.
- **Test with a small batch before scaling.** Submit ~20 requests, verify reconciliation end-to-end,
  then scale.
- Note: batch is **not eligible for Zero Data Retention**. My sources are textbooks and question banks,
  not patient data, so this is fine — but flag it if that ever changes.
- Check current per-batch request and size limits in the docs at C0 rather than assuming; split
  oversized jobs into sequential batches and record each ID.

### 10e. Budget gate

Before any mass LLM stage, print an estimate — page/item count × est. tokens × model rate × 0.5 —
and **wait for my approval** (this is the C11 ⛔). Track running spend in
`.compiler_state.json.token_spend_estimate_usd` and report it at every stage boundary. If a stage would
exceed the estimate by >25%, stop and tell me rather than pushing on.

---

## 11. Git repository, what stays out of it, and APK delivery

### 11a. Create the repo (C0, first thing)

Initialise a git repo named **`studyfeed`** at the project root, on my Hetzner box. Commit as the build
progresses — not one dump at the end.

- **Make it PRIVATE.** Not a preference — a requirement, for the reason in §11b. If you use `gh repo
  create`, it must be `--private`. **Ask me before creating anything remote**; I may want it local-only
  or under a specific account (`kayomarz`). Do not push to a remote until I've confirmed which one.
- Sensible commits per milestone: `spec: initial content pack schema`, `compiler: ingest + OCR stage`,
  `app: study feed pager`. Tag pack builds (`pack-v0.1.0`) and app releases (`app-v0.1.0`).
- **Work on and push `dev`; never push `main`** — I merge it. (This matches my global rule; if my
  `~/.claude/CLAUDE.md` says otherwise, **my global rule wins — ask me**.) Commit at every ⛔ gate so
  each approved state is recoverable.
- `.gitignore` goes in **before the first commit** — a copyrighted PDF committed once lives in the
  history forever, and rewriting history later is miserable.

### 11b. What must NOT be committed — read carefully

"Ignore the books, commit everything else" is not quite right, and the gap is the dangerous part. My
sources are **copyrighted textbooks and commercial question banks**. Ignoring `sources/` does not make
the repo clean, because several *derived* artifacts contain that same copyrighted text:

| Artifact | Contains | Commit? |
|---|---|---|
| `sources/**` | The PDFs themselves | ❌ **Never** |
| `work/**/*.jsonl` | Verbatim extracted book text, page by page | ❌ **Never** |
| `dist/*.db` | The compiled pack — atoms, and **curated MCQ stems passed through verbatim** | ❌ **Never** |
| `dist/figures/**` | Images extracted from the books | ❌ **Never** |
| `quarantine.jsonl` | Rejected source text | ❌ **Never** |
| `dist/build_report.md`, `dist/importance_report.md` | **Quote MCQ stems** in the top-30 / most-corroborated tables | ❌ **Never** (see below) |
| `*.apk` | Bundles the pack if assets are embedded | ❌ **Never** |
| `keystore.jks`, `keystore.properties` | Signing key + passwords | ❌ **Never** (see §11d) |
| `compiler/`, `spec/`, `app/` source | My own code | ✅ Yes |
| `profiles/*.yaml` | Taxonomy, weights, tags, drug aliases — no book content | ✅ Yes |
| `.claude/agents/`, `.claude/hooks/`, `.claude/settings.json` | Config | ✅ Yes |
| `.compiler_state.json` | Cursors, hashes, batch IDs — **no content** | ✅ Yes (useful across machines) |
| `README.md`, `JOURNAL.md`, `CLAUDE.md` | Prose | ✅ Yes, with the constraint below |

**Two rules that follow from this:**

1. **Reports and `JOURNAL.md` must log counts, IDs, and decisions — never content samples.** When a
   stage wants to show me 20 sample extracted spans or the top-30 5★ MCQ stems, print that **to my
   terminal**, and write only `mcq_id` + score + signal breakdown to the committed file. A report that
   quotes 30 question stems is a copyright problem wearing a build artifact's clothes.
2. **The reports I *do* want to keep** (with stems, for my own review) go to `dist/` and stay ignored.

```gitignore
# ── copyrighted source + anything derived from it ──
# I drop my archives at the PROJECT ROOT. These patterns MUST exist before the first commit —
# a zip committed once lives in git history forever and rewriting history later is miserable.
*.zip
*.rar
*.r00
*.7z
*.tar
*.tar.gz
*.cbz
*.epub
*.djvu
compiler/sources/
compiler/sources/inbox/
compiler/work/
compiler/dist/
**/quarantine.jsonl
*.pdf

# ── build artifacts ──
*.apk
*.aab
app/build/
.gradle/
local.properties

# ── signing: losing these is bad, committing them is worse ──
*.jks
*.keystore
keystore.properties

# ── env / secrets ──
.env
*.env
**/.anthropic_key

# ── python ──
__pycache__/
.venv/
*.egg-info/

# keep the state file — it has cursors and hashes, no content
!compiler/.compiler_state.json
```

**Check before the first push:** run `git status --ignored` and show me what's being excluded, and
`git log --stat` to confirm no PDF or `.db` ever entered history. If one did, tell me immediately —
that's a stop-and-fix, not a note for later.

### 11c. Deliver the APK

The APK is the point of all this. **⚠ AMENDED 2026-07-17 (PATCH 02):** it is built on the **laptop**
via Gradle CLI (§0 `build_env`) — *not* on the Hetzner box. The box is headless and OOM-bound and
never builds the app. Android Studio may be open, but **the build must not depend on it**.

- `./gradlew assembleDebug` during development — debug-signed, sideloads fine, quickest loop.
- `./gradlew assembleRelease` for what I actually study from — minified and faster.
- **Install over USB from the laptop: `adb install -r <apk>`** — direct to the phone, no copy step.
  ~~scp / Cockpit Navigator~~ are **moot**: those existed only because the APK used to be built on a
  remote box. There is nothing to ask here anymore — do not re-raise the scp-vs-Cockpit question.
- **The pack is NOT delivered with the APK.** It moves separately: `scp` the `.db` from the box's
  `compiler/dist/` to the laptop, then `adb push` it to the phone (or import via SAF). This split is
  the point — the app is generic and the pack is data (§0 `multi_pack`), so a new pack never needs a
  new APK.
- **Never commit the APK.** If I want versioned builds, use GitHub Releases (`gh release create
  app-v0.1.0 ./path/app-release.apk`) so binaries live outside git history — but **ask first**, since
  that uploads a pack-bearing binary to a remote.
- Print the APK size and the `pack.db` size separately at each build. If the pack is bundled in assets
  rather than side-loaded via SAF, tell me — at this corpus size it will make the APK enormous, and my
  intent is that the pack is imported, not embedded.

### 11d. The keystore risk — this one can cost me the exam

If I sign a release APK with a self-signed keystore and later **lose that keystore, I cannot upgrade
the app in place.** Android will reject the new APK as a signature mismatch, so my only option is
uninstall-and-reinstall — **which deletes `progress.db`, and with it every mistake, tag, star, streak
and FSRS scheduling state I've built up.** Six weeks of daily study, gone, weeks before the exam.

So:
- Generate the keystore **once**, at A0. Store it **outside the repo**. Tell me explicitly where it is
  and that I must back it up somewhere off this server. Do not proceed past A0 until I confirm I have.
- Put passwords in `keystore.properties` (gitignored), never in `build.gradle`.
- **And make the data recoverable regardless** — see §11e.

### 11e. Progress export/import (add this to the app — it is not optional)

Because of §11d, and because phones get lost, the app must let me get my progress out:

- **Settings → Export progress** — writes `progress_<exam_code>_<date>.db` to Downloads via SAF.
- **Settings → Import progress** — restores it, replacing the current progress DB after a confirm.
- Both are plain file copies of a SQLite file: **no network, no background work, no tokens.** Fully
  consistent with every constraint above.
- Prompt me to export after any long session — an in-app nudge, not a notification.

This is cheap to build and it is the difference between "my phone died" being an annoyance and being a
catastrophe. Treat it as part of A1, not a nice-to-have for later.

> **⚠ A1 OWNS THIS — confirmed 2026-07-17 (PATCH 02).** §8 A1 now names it explicitly and §8 A7 has
> been reduced to surfacing the Settings entry points, so the spec names exactly one owner.
> **It is now urgent, not theoretical:** Phase 1 shipped a real 26,965-item pack, so the moment it is
> studied, progress becomes real and losable. The earlier "safe to defer" reasoning rested on no pack
> existing — **that premise is dead.**
> Export is **per-pack** (progress is per-pack), and per the PATCH 01 §11e revision it is a **ZIP**
> (`progress.db` + `note_images/`), not a bare file copy.

---

## 12. Global engineering rules

- **Ask, don't assume (§1).** Batch non-blocking questions to the next ⛔ gate; block immediately on
  anything irreversible, expensive, or clinically wrong. Never fill a gap with a guess.
- **Zoning is for prose, not for questions (§5i).** Atomize `body` only; MCQ-parse `body` **and**
  `back_matter`; never touch `front_matter`. A demo MCQ in the front is fake and must go; a question
  bank in an appendix is real and must stay. Never fence back matter without showing me first.
- **Key trust is per-item, not per-source (§5a).** A printed answer key in a textbook is a curated key
  at qbank-level trust. Never re-derive a printed answer.
- **File-dedupe at ingest, not downstream (§5b).** The same PDF loose and in a zip = one source, or
  every importance score is wrong. Exact hash auto-collapses; fuzzy matches get asked about. Count
  corroboration over distinct **works**, not files.
- **Script filter is an allowlist, never "strip non-ASCII" (§5g).** Greek and medical notation are
  load-bearing. Drop Arabic/Bengali blocks by proportion, span-level, and log every drop.
- **Nothing derived from the books enters git (§11b).** Not the PDFs, not `work/` JSONL, not `pack.db`,
  not figures, not reports that quote stems. Counts and IDs in committed files; content to my terminal.
- **Decoupling is the point.** Anything exam-specific lives in the profile → pack → manifest, never in
  app code. The app is a generic renderer of a spec-conforming pack.
- **State on disk, never in context (§10a).** Write `.compiler_state.json` after every stage and every
  batch submission. A fresh session must resume from disk alone. Append to `JOURNAL.md` per milestone —
  prose for me, JSON for the machine, both maintained.
- **Never read a book into a context window (§10c).** Extraction, clustering, scoring, dedupe and
  centrality are Python and cost zero tokens. Subagents run scripts; they don't read pages.
- **Bulk LLM work goes through the Batch API (§10d)**, not through Claude Code subagents. Record every
  batch ID to state *before* polling. Reconcile by `custom_id`, never by result order.
- **Scoring is deterministic and reproducible.** LLMs calibrate and adjudicate; **arithmetic scores.**
  Cache every Opus verdict by content hash so re-runs are free and byte-identical.
- **Distinct sources beat repeats.** Anywhere frequency is used, damp it; corroboration across
  *different* sources/publishers is the real signal. Never drop a citation on merge.
- **No realtime generation, ever, in the app.** Dossiers, distractors, quiz-backs, importance — all
  pre-compiled. The app renders and schedules.
- **No background execution.** Resurfacing happens in-app on next open. (A single opt-in daily
  `AlarmManager` alarm is the only thing I'd ever consider — default is none.)
- **AMOLED-first.** Pure-black default; avoid large bright fills; this is a stated battery goal.
- **Idempotent + resumable compiler.** Stage checkpoints; hash sources; packaging upserts.
- **Grounding is a hard gate.** Ungrounded ⇒ `quarantine.jsonl`, never shipped.
- **Checkpoints** at every ⛔ and before anything destructive/expensive — show counts/costs, wait.
- **JOURNAL.md** per milestone. **Tests:** compiler (grounding, dedupe-preserves-citations, cluster
  determinism, importance reproducibility on a golden fixture, floors enforced, dossier coverage);
  app (FSRS scheduling, feed variety, importance sort/filter, tag/star persistence,
  import-preserves-progress, offline/no-background lint).

---

## 13. Kickoff — do this now

> ## ✅ KICKOFF IS DONE (2026-07-15/16). §13 IS HISTORY — DO NOT EXECUTE IT.
>
> Every numbered step below **already happened**: the repo exists, `.gitignore` landed before the
> archives, the three drops were ingested and classified, the durability layer + hooks were built and
> proven, and the compiler ran **C0 → C13**. Re-running any of it would re-ingest archives that are
> long since processed. Kept verbatim as the historical record (§10a).
>
> **A fresh session goes to §8 A1 instead** (see the resume box at the top of this file), reads
> `JOURNAL_APP.md`, and builds on the laptop. The only line below that still binds is the last one:
> **when in doubt at any point: ask.**

**Read this file from disk, not from my paste.** I saved it as `BUILD_PROMPT.md` in the repo root, and
`CLAUDE.md` points at it. That is deliberate: by §10a, instructions that live only in the conversation
are eroded by compaction. **If you ever find yourself unsure what you're building, re-read
`BUILD_PROMPT.md` — it is the spec, and it is on disk.**

1. Confirm the plan in one short message and list the subagents you'll create **with their models**.
   If anything here is ambiguous, **ask now** rather than picking (§1).
2. **Git first, before anything touches the archives.** `git init`, write and commit `.gitignore`
   (§11b), then `git status` — **my three archives at the repo root MUST show as ignored.** If any is
   tracked, stop and tell me. Then move them into `compiler/sources/inbox/` and hash them into state.
   **Ask before creating any remote.**
3. Report `echo $CLAUDE_CODE_SUBAGENT_MODEL` — if it's set, it overrides all frontmatter routing and my
   whole haiku/sonnet/opus split silently collapses to one model.
4. **Verify the environment (C0):** RAR extractor that reads **RAR5** (my MCQ bank is a `.rar` — if
   this fails, tier 2 is unreadable and there's no point starting), `df -h`, OCR, embedding model.
5. **Build the durability layer next, before any pipeline code** (§10a/§10b): `.compiler_state.json`
   writer with atomic replace, `JOURNAL.md` appender, and the four hooks in `.claude/hooks/` +
   `.claude/settings.json`. Prove it: write a state file, run `/compact`, confirm the `SessionStart`
   hook injects the state back. **Demonstrate this before we build anything expensive.**
6. Write `spec/SPEC.md` + `schema.sql` + `SCORING.md` + `profiles/mrcp_part1.yaml`, scaffold
   `compiler/`, print the stage plan + routing table + thresholds + the token-budget estimate. ⛔
7. Then C1: unpack the three drops, dedupe across them, and **show me the classification table** —
   which files are papers (with years), which are qbank, and which of the textbook drop are
   textbook vs pearls vs reference. Do not guess years. Do not resolve a cross-drop tier conflict alone.
8. Do **not** mass-generate assets, overwrite any pack, push to a remote, or generate a keystore
   without hitting the ⛔ gate first.

**If you are a fresh session reading this after a crash or compaction:** do not start over. Read
`compiler/.compiler_state.json`, re-poll any batch IDs listed under `batches`, read the tail of
`JOURNAL.md`, and resume at `next_action`. Ask me only if the state file is missing or self-contradictory.

Build the full product across all three parts. Phases exist only so I can approve as we go — the target
is the complete, offline, exam-agnostic app plus a first MRCP Part 1 pack, delivered as an installable
APK, not a stripped-down version. **When in doubt at any point: ask me.**

---

## PATCH 01 — Part B (Phase 2, app) — applied 2026-07-16

> These two amendments belong in **§8 (app build order, as A8 after A7)** and **§11e**. Recorded here
> verbatim so they are on disk (§10a). Part A (stable keys) is already applied IN PLACE: §5j, §6a
> `stable_key`×4 (+ indexes in `spec/schema.sql`), §6b retrofit (fsrs_cards/reviews/mistakes/stars/tags
> → stable_key) + `user_notes`/`user_note_images`. Part B changes nothing in the compiler; it is a hard
> consumer of `stable_key`.

### §A8 · My own notes — text + images, local (app build order, after A7)

- **Add a note from anywhere:** a note action on every feed card, on a dossier section, and on a
  concept-map node — plus standalone notes attached to nothing. Store the **`stable_key`** of what it's
  attached to, **never the row id** (§5j).
- **Text:** markdown body, rendered like pack content. **Images:** pick via SAF, **copy into
  app-private storage**, store a relative path — **not** a blob in the DB and **not** a `content://` URI
  (it dies when the source app clears its cache). **No CAMERA permission** unless in-app capture is
  later requested; the picker needs none. Still **no INTERNET, no background work, no generation**.
- **Where they surface:** inline on the card (small affordance, never covering content); as their own
  section in a dossier; a Notes screen filterable by subject / anchor kind / orphaned; searchable.
- **⚠ Notes are NOT grounded and MUST NOT touch scoring** — no `importance`, no corroboration, no
  `S_emphasis`. Render them **visually distinct** from pack content.
- **Optionally reviewable:** promote a note to a flashcard; it gets FSRS state but stays flagged
  user-authored.
- **Survives recompile:** on import, resolve `anchor_stable_key` → current id. Unresolved ⇒
  `is_orphaned = 1`, surfaced in a "notes needing a home" list with a re-anchor action. **Never delete
  a note because the pack changed.**

### §11e revision · Progress export/import must be a ZIP (note images live on the filesystem)

- **Export progress** → writes a **zip**: `progress.db` + `note_images/`, to Downloads via SAF (the
  plain single-file copy would silently lose every note image).
- **Import progress** → restores both.
- **Acceptance test 12** now round-trips an **image-bearing note**: export → wipe app data → reinstall →
  import → the note *and its image* are back.

---

## PATCH 02 — Phase 1 closed · app builds on the laptop — applied IN PLACE 2026-07-17

> **Authorised by the user on 2026-07-17**, who directed: *"edit the buildmd and other files to be
> relevant of the future changes — I will be making the app on the laptop"* and *"I want the app to
> work with other databases also in future."* This **reverses the 2026-07-16 ruling** that §0
> `build_env` stays verbatim. Applied **in place** (not appended-only) because the stale text was
> actively misdirecting fresh sessions; recorded here as the audit trail (§10a).
>
> **This patch changes NOTHING about the pack format, the schema, or the decoupling contract**
> (§2, §6, `spec/schema.sql`). It reconciles the spec with two facts that landed after it was written.

### The two facts

1. **Phase 1 is COMPLETE (C0–C13).** `compiler/dist/pack_MRCP_PART1.db` exists — 26,965 playable
   items, 147,146 stable_keys, PATCH-01 reproducibility gate cleared. The compiler is dormant.
2. **The app is built on the laptop** (Android Studio + physical phone), not the Hetzner box, which is
   headless and OOM-bound. A0 shipped from there on 2026-07-16/17.

### What changed, and why

| § | Was | Now |
|---|---|---|
| §0 `build_env` | `hetzner_headless_claude_code` (locked) | **Split**: compiler→box, app→laptop. **CLI-buildable intent unchanged**; only "over SSH" is moot. |
| START HERE | resume → `.compiler_state.json`, begin at §13 | Phase 1 closed → app sessions read `JOURNAL_APP.md` and resume at **§8 A1**. |
| §7 | C0–C13 as pending work with live ⛔ gates | Marked **COMPLETE / historical**. Empty-table gaps flagged for A2. |
| §8 A0 | "buildable headless… **over SSH**" | ✅ **DONE**; "over SSH" dropped, CLI intent kept + build-enforced by `build-logic/`. |
| §8 A1 | pack loading, generic | + **schema-version gate**, + **degrade on empty tables**, + **§11e**, + explicit no-hardcoded-exam rule. ⇦ next stage. |
| §8 A7 | "Export/Import — required, not optional" | Built at **A1**; A7 surfaces entry points only (one owner, no duplicate). |
| §11c | build on the box; `scp`; **ask** scp-vs-Cockpit | **`adb install -r`** over USB from the laptop. The scp/Cockpit question is **moot — do not re-raise it.** Pack ships separately from the APK. |
| §11e | "part of A1" (contradicted by §8) | **A1 owns it, urgent** — a real pack now exists, so the "safe to defer" premise is dead. |
| §13 | Kickoff — "do this now" | **History. Do not execute.** |

### On "work with other databases in future" — what it does and does not mean

Clarified with the user 2026-07-17: it means **more exam packs in this same pack format** (MRCP Part 2,
USMLE, another subject) — **not** foreign/third-party DBs (Anki, CSV). **Do not build an importer or
adapter layer**; that scope is explicitly out.

This needs **no new architecture** — it *is* §0 `multi_pack: true` + `first_exam` ("the app must NOT be
hardcoded to it") + §8 A1's "Pack loading = **generic**". Verified 2026-07-17: the app holds **zero**
exam-specific strings (the only "MRCP" under `app/` is a comment in `strings.xml` forbidding it).
The contract is **kept, not extended**. Two additions make it safe rather than merely intended:

- **The schema-version gate (§8 A1)** — check `pack_manifest.schema_version` + `PRAGMA user_version`
  on import; refuse an unsupported pack with a clear message. A future pack on a bumped schema is
  exactly what a naive loader corrupts.
- **Empty tables are normal** (§7/§8 A1) — the shipped pack has none of `text_blobs`/`figures`/etc.
  A pack lacking an asset type must simply not offer it.

**The standing rule that protects all of this:** nothing exam-specific may ever enter the app. One
hardcoded string turns a generic player into a single-exam app, silently.
