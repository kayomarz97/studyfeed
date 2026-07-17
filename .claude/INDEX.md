# INDEX.md — map of StudyFeed

> The codebase map Claude scans FIRST — instead of grepping around and burning tokens.
> One line per entry: what it is and when to touch it. **Update this file in the SAME
> commit whenever files are added, moved, or repurposed** — a stale map is worse than none.
> Authoritative spec: `BUILD_PROMPT.md` (**re-read from disk — PATCH 02, 2026-07-17, rewrote §0/§7/§8
> A0+A1/§11c/§13 + START HERE**; §7 and §13 are now *history, do not execute*. **PATCH 03, 2026-07-17:**
> the C11 asset trim was **undone** — blobs/interlink/figures **ship POPULATED, use them**; capability is
> detected **per pack at runtime** so the DB stays swappable. Any text saying they "ship empty" is stale.)
> **STATUS: Phase 1 (compiler) COMPLETE — C0–C13, pack shipped + imported to the laptop
> (32,957 playable feed items). Phase 2 (app) is live: A0 done, A1 next.**
> Resume truth: **`JOURNAL.md`** — one merged log (⛔ standing orders at the top, then Phase 1 /
> Phase 2 sections). `JOURNAL_APP.md` was merged into it and deleted on 2026-07-17.
> Machine resume truth for the dormant compiler stays `compiler/.compiler_state.json`.

## How the system works (keep to ~10 lines)

Three decoupled parts joined only by the pack format. **(1) Profile + sources in:** an exam
`profiles/<exam>.yaml` plus three archives in `compiler/sources/inbox/`. **(2) Compiler:**
`studyfeed_compiler/run.py` drives a resumable 14-stage DAG (C0–C13) — unpack → file-dedupe →
zone/usage → extract+OCR+script-filter → highlights → atomize+ground → cluster/corroborate →
PYQ/trend/staleness → merge → alias+graph → **deterministic 1–5 importance** (`importance.py`) →
dossiers → generate assets (Batch API) → validate → package. Each stage checkpoints to
`.compiler_state.json` (via `state.py`) + `work/<stage>.done`; heavy LLM work goes through the
Anthropic **Batch API**, never Claude Code context (§10c/§10d). **(3) Output:** `dist/pack_<EXAM>.db`
(conforms to `spec/schema.sql`) + `figures/` + `manifest.json`, which the generic `app/` (Phase 2)
imports and renders offline. Everything exam-specific lives in the profile → pack → manifest.

## File map

| Path | What it does / when to touch it |
|---|---|
| `BUILD_PROMPT.md` | **The authoritative spec. Read before acting.** Do not re-save. **PATCH 02 (2026-07-17) applied IN PLACE** — §0 `build_env` split (compiler→box, app→laptop), §7+§13+START HERE 1–4 marked *history/do-not-execute*, §8 A1 gained a schema-version gate + §11e, §11c → `adb install -r`. The PATCH sections at the end are the audit trail. |
| `JOURNAL.md` | **The single human log, and the resume truth for app work** (prose; counts/decisions only, never book content). Merged 2026-07-17 — was two files. Layout: ⛔ **standing orders at the top (read first)** → **Phase 1 — compiler** (C0–C13, dormant, `state.py`-written) → **Phase 2 — app** (LIVE; hand-written here, append new app entries at the END). Also carries the now-RESOLVED §0 `build_env` divergence as history. |
| `CLAUDE.md` | Project working agreement (2 sections filled; the rest is global). |
| `LAPTOP_SETUP.md` | Phase 2 dev-machine bootstrap: paste-into-Claude prompt — installs Claude Code CLI, bootstraps the `claude-master` global rulebook, links Android Studio (plugin 27310), preps SDK/adb, clones the repo on `dev`. App is built on the laptop (needs a screen/device); compiler stays on the box. |
| `spec/schema.sql` | **Canonical content-pack DDL (source of truth).** `PRAGMA user_version` = schema version (**INTEGER 1** — note `pack_manifest.schema_version` is **TEXT "1"**; parse, never `==` across the two). |
| `spec/SPEC.md` | **Human contract — read before A1.** 2-DB model (pack read-only + `ATTACH`-ed beside a read-write per-`exam_code` progress DB), manifest, versioning (app refuses a **newer** schema, older still loads), runtime invariants, progress-DB DDL. Pack = a **directory** imported via SAF; `figures.path` is **relative**, so layout is load-bearing. |
| `spec/manifest.schema.json` | JSON Schema for `manifest.json` shipped beside each pack. |
| `spec/SCORING.md` | The deterministic 1–5 importance model + worked examples. |
| `profiles/mrcp_part1.yaml` | First exam profile: tiers, weights, floors, scripts, dedupe, zoning, aliases, tags, `data_corrections` (§5j stable_keys of web-verified-wrong atoms, excluded at pack). |
| `compiler/pyproject.toml` | uv project (py≥3.11); deps unpinned until `uv lock` + verify (pre-C2). |
| `compiler/studyfeed_compiler/run.py` | Resumable stage DAG + ⛔ gates. `--list` / `--from` / `--only`. |
| `compiler/studyfeed_compiler/state.py` | **Sole writer** of `.compiler_state.json` (atomic) + `JOURNAL.md` appender (`append_journal`, append-only). Dormant with the compiler — it is no longer the *only* writer of `JOURNAL.md`: app entries are now hand-written into the Phase 2 section. It appends to the file's END, so a woken compiler would land its entry under Phase 2 — move it by hand if that ever happens. |
| `compiler/studyfeed_compiler/config.py` | Loads a profile → typed access + paths + Batch-API model routing. |
| `compiler/studyfeed_compiler/importance.py` | The scoring engine (deterministic; per SCORING.md). *stub → C9.* |
| `compiler/studyfeed_compiler/{unpack,filededupe,classify}.py` | **C1 ingest — implemented.** Recursive unpack (prefer `unrar`), file-dedupe (scanned = hash-only), classify (kind/year/subject). |
| `compiler/studyfeed_compiler/scriptfilter.py` | §5g Unicode-block allowlist (drop Arabic/Bengali, keep Greek). Implemented. |
| `compiler/studyfeed_compiler/ingest/extract.py` | **C2 extract — implemented.** PyMuPDF text + fixed-width parallel OCR (tesseract) + script filter; per-work JSONL in `work/extracted/`. |
| `compiler/studyfeed_compiler/bookusage.py` | **C3 emphasis harvest — implemented.** Digital annots = 0 (probed all PDFs) → `is_highlighted`=0; `book_flagged` from whole-source salience (pearls §profile) + per-topic markers in extracted text (SKELETON 🎤/▶️, key-point, pearl; noise-ceiling §5i) → `work/emphasis.jsonl` → feeds S_emphasis in C9. |
| `compiler/studyfeed_compiler/llm.py` | **C4 LLM ingest — implemented.** Batch API: atomize prose (Haiku) + parse MCQs (Sonnet; kind-aware, PassMedicine pages stitched); `submit`/`status`/`harvest` → `work/atoms.jsonl` + `work/mcqs.jsonl`. Plus `test-textbook`/`submit-textbook`/`harvest-textbook`: `_mcq_units_textbook` extracts MCQs from textbook-embedded qbanks (SsAcademy SKELETON) — detects textbook works with ≥20 `Question N` headers, splits Questions/Answers sections at number resets, PAIRS Q↔A, idempotent-appends to `mcqs.jsonl` (kind stays `textbook`; atoms kept). |
| `compiler/studyfeed_compiler/cluster.py` | **C5 clustering — implemented.** fastembed (ONNX) stem embeddings → exact block-wise cosine → union-find; distinct-**work** counts (§5b, via `load_mcqs_canonical` → `workgroups.canonical_map`) + boilerplate net (§5i). Exposes `finalize_clusters`/`write_cluster_outputs`/`embed`/`_UF` (reused by C7 dedupe). |
| `compiler/studyfeed_compiler/adjudicate.py` | **C5 corroboration Phase 2 — implemented.** Opus judges the ambiguous cosine band (≥0.89), verdicts cached by pair-hash, YES pairs merged into clusters → rewrites `mcq_clusters.jsonl`/`mcq_corrob.jsonl`. |
| `compiler/studyfeed_compiler/workgroups.py` | **Work-identity grouping (§5b).** Per-subject slices of one book share a canonical work_id (`canonical_map()`); redundant duplicate copies dropped. C5/C7 corroboration MUST apply it so one book's slices don't fake cross-corroboration. |
| `compiler/studyfeed_compiler/pyq.py` | **C6 PYQ analysis + trend — implemented.** Per-MCQ `is_pyq`/`pyq_years`/`pyq_last_year`/`S_pyq`/`pyq_trend` from the C5 clusters → `mcq_pyq.jsonl`. Staleness deferred to post-C8. |
| `compiler/studyfeed_compiler/dedupe.py` | **C7 atom dedupe/merge — implemented.** fastembed + hnswlib ANN (never all-pairs at 133k) → union-find near-dup merge (SIM 0.95, tuned to avoid transitive over-merge/laundering §5b); keeps best-sourced text, UNIONs citations (never drops a source), counts distinct **works** (canonicalised) → `atoms_merged.jsonl`. |
| `compiler/studyfeed_compiler/alias.py` | **C8a alias normalization (§5e) — implemented.** `canonical_key` (orthographic match key, never rewrites UK display) + `Aliases` synonym resolver from `profile.nomenclature.alias_hints`. `key(name)` = merge key: one entity = one node. |
| `compiler/studyfeed_compiler/graph.py` | **C8 concept graph — implemented.** Haiku Batch API extracts `(s,r,o)` triples (terse `N\|s\|r\|o`) → fold by alias key → typed grounded edges + networkx PageRank centrality → `concepts.jsonl`/`concept_edges.jsonl`; novel-alias proposals → `dist/alias_proposals.yaml` (⛔). Deterministic `investigated_by` disease→test direction fix (Sonnet-audit rec). `test`/`submit`/`harvest`/`build`. |
| `compiler/studyfeed_compiler/importance.py` | **C9 deterministic 1–5 importance — implemented.** 8 weighted signals (SCORING.md) → raw → bucket .80/.62/.42/.22 → hard floors. Scores MCQs (full signals), concepts (centrality + derived corrob + approx PYQ from MCQ-stem match), atoms (own corrob/freq/tier + inherited centrality/PYQ). `emphasis`=0 until C3. Outputs `{mcq,concept,atom}_importance.jsonl`. |
| `compiler/studyfeed_compiler/dossier.py` | **C10 topic dossiers — implemented.** Per canonical concept, gather ALL constituent atoms (via `graph_edges_raw.rationale`=atom.statement[:240]; `atom_importance` line-aligned with `atoms_merged`) into one long grounded `body_md` sectioned by subject (past-paper/qbank atoms are subject-less → trailing "Question-bank & exam notes" section) + concept-map connections + related MCQs (name/alias phrase-match). Scope = `profile.dossier` (≥min_atoms OR ≥min_subjects; excluded count logged). importance=MAX of constituent atoms (§5d); `stable_key`=sha256("topic\|"+canonical_key)[:16]. Deterministic (byte-identical across PYTHONHASHSEED), zero-cost, 100% grounded. figure/flashcard links deferred to C11/C13 → `work/dossiers.jsonl` (page + nested sections). |
| `compiler/studyfeed_compiler/generate.py` | **C11 asset generation — orchestration implemented (core scope).** Deterministic scaffolding around the CC `gen-*` subagents (CC-plan usage, NOT the paid API — user directive): `prep_*` chunks scored inputs IMPORTANCE-DESC into `work/gen_input/<asset>/NNNN.jsonl` + resumable `work/gen_manifest.json`; subagents (run via background Workflows, concurrency-capped) write `work/gen_output/<asset>/NNNN.jsonl`; `validate_*` is the deterministic grounding gate (ref resolves, non-empty, salient-token overlap with cited atom — checks front+back for cards; exactly-5-options + valid key for MCQs); `collect` → `work/gen_<asset>.jsonl`. Built: flashcards ≥4★ (9,510), reels top-3k (3,000), generated best-of-five MCQs top-2k (2,000), **blobs** top-3k dossiers (2,997; 60–150w + 4-opt quiz-back, gen-blobs) + **interlink** best-edges excl. `associated_with` (2,998; gen-interlink). Blob/interlink importance = *effective* concept importance (dossier star where available, else concept_importance — dodges the noisy concept tail). 3★ flashcard tail + figures still deferred. |
| `compiler/studyfeed_compiler/validate.py` | **C12 validate + QA — implemented.** Deterministic audit over `work/` artifacts (pre-packaging): grounding/dead-links, importance∈1..5 + populated signals, PYQ≥4★ floor (non-fading; SCORING.md caps fading-dead PYQs ≤3), curated-key preservation (unparseable→quarantine), importance distributions (degeneracy flag), PYQ/dossier/blueprint coverage, and a **byte-identical reproducibility re-score**. Writes `dist/build_report.md` (gitignored). Opus `qa-reviewer` subagent adds the semantic-grounding layer (does a generated card follow from its atom) → appended to the report. `audit()` + CLI (`--no-repro`). |
| `compiler/studyfeed_compiler/pack.py` | **C13 packaging — implemented (FINAL stage).** Assembles all artifacts into `dist/pack_<EXAM>.db` (conforms to `spec/schema.sql`) + `dist/manifest.json` (validates vs `manifest.schema.json`). Natural 0-indexed ids (source.id=work_id, atom.id=line#, concept.id=jsonl_id, gen mcq.id=1e6+idx) → no remapping. `stable_key` (§5j) = `sha256(normalize(text)\|canon_work_id\|page_ref)[:16]` for atoms/curated-mcqs, `sha256("generated"\|atom_key)` for gen-mcqs, `sha256("concept"/"topic"\|canonical_key)` for concepts/topic_pages; deterministic collision-break. QUARANTINES curated MCQs with no valid A–E key (empty or EMQ >5-opt) — 34 not shipped. `pack_manifest.checksum`=content-checksum over sorted stable_keys (reproducibility anchor); `manifest.json.checksum`=db file sha256. `verify()` = the PATCH-01 gate: build twice → identical stable_key set (PROVEN). `build`/`verify` CLI. Now populates text_blobs/blob_quizzes/interlink_quizzes (+ blob/interlink feed_items) + **figures** (1,121; `figure_id` links on atoms/mcqs/topic_pages by source+page) + **key_conflict** flags (staleness). has_blobs/interlink/figures flags. Only-empty table left: dup_clusters. |
| `compiler/studyfeed_compiler/figures.py` | **Figures (deferred tail) — implemented.** `extract` pulls embedded images from the 35 in-scope DIGITAL textbooks (PyMuPDF), filters size/aspect + drops page-furniture + content-dedup (5,813→1,154 candidates); `prep-caption` chunks them; vision `figure-handler` subagents triage (keep medical / drop decorative) + caption → `collect` keeps 1,121, copies PNGs to gitignored `dist/figures/`, writes `work/figures.jsonl`. Verbatim copyrighted images, never committed. |
| `compiler/studyfeed_compiler/staleness.py` | **Post-C8 staleness — implemented.** `build` narrows to PYQ MCQs on guideline-sensitive concepts with a management/target/threshold cue (1,529, oldest-paper first); `staleness-checker` subagents judge each key vs CURRENT UK guidance (conservative) → `collect` → `work/mcq_key_conflicts.jsonl` {idx,key_conflict,note} → pack sets `mcqs.key_conflict` (25 flagged; NEVER overwrites a key — app shows "key disputed"). |
| `compiler/studyfeed_compiler/{ingest,highlights,generators}/` | `ingest/extract.py` = C2 (implemented). highlight-harvest lives in `bookusage.py` (C3); asset generation in `generate.py` + `gen-*` agents (C11) — these subdirs are vestigial. |
| `compiler/studyfeed_compiler/{ingest,highlights,generators}/` | Extractors / highlight harvest / asset generators. *stubs.* |
| `compiler/.compiler_state.json` | Machine resume truth: stage, next_action, decisions, archive hashes, batch IDs. Committed. |
| **— Phase 2: the app · THE LIVE FRONT (A0 ✅ done · **A1 next**) · built on the LAPTOP, not the box — see `JOURNAL.md` "Phase 2 — app" —** | |
| `app/README.md` | **Read before touching the app.** JAVA_HOME gotcha, build/install commands, the guard, signing + the §11d keystore warning, APK sizes. |
| `settings.gradle.kts` | Gradle root: `includeBuild("build-logic")` + repos (FAIL_ON_PROJECT_REPOS). Owns `:app` ONLY — `compiler/`+`spec/` are never Gradle modules. |
| `build.gradle.kts` / `gradle.properties` | Root build (plugin versions, applies nothing) / JVM args. **No `org.gradle.java.home`** — that would hardcode one laptop's path; JAVA_HOME supplies the JDK. |
| `gradle/libs.versions.toml` | **Every version pinned, never dynamic.** AGP 9.3.0 · Gradle 9.5.0 (AGP's default, not newest) · Kotlin 2.4.10 · Compose BOM 2026.06.01 · fsrs 1.0.0. Rationale + citations: PLAYBOOK "Android build toolchain". |
| `gradle/wrapper/*` | Committed wrapper, pinned to Gradle 9.5.0 **with `distributionSha256Sum`** (the distro is verified, not trusted). |
| `build-logic/` | **The offline/no-background guard.** An *included build*, not `buildSrc` — a plugin using AGP's Variant API cannot work from buildSrc's classloader scope (see its settings.gradle.kts). |
| `build-logic/src/main/kotlin/.../OfflineGuaranteesPlugin.kt` | Wires both checks into every `assemble<Variant>` + `check` → an APK that violates §8 A0 **cannot be built**. |
| `build-logic/src/main/kotlin/.../CheckMergedManifestTask.kt` | Parses the **merged** manifest (catches library-injected components); fails on network/background permissions, any service/receiver, non-allowlisted providers. Parses XML — never grep (a grep matched our own warning comment). |
| `build-logic/src/main/kotlin/.../CheckDependencyDenylistTask.kt` | Walks the variant runtime classpath incl. transitives; fails on HTTP clients / WorkManager / Play Services / AI SDKs, naming who pulled them in. Denylist by design (see the class comment). |
| `app/build.gradle.kts` | compileSdk/targetSdk 36, minSdk 26, `applicationId dev.kayomarz.studyfeed` (**permanent**). Keystore read from outside the repo; absent → release unsigned but debug still builds. No `kotlin-android` plugin: AGP 9 has built-in Kotlin. |
| `app/src/main/AndroidManifest.xml` | **Zero permissions, one activity — read the comment before editing.** Strips profileinstaller's injected receiver via `tools:node="remove"`; `allowBackup=false` (no cloud copies of progress). |
| `app/src/main/java/.../MainActivity.kt` | The app's ONLY component; A0 placeholder UI. Feed = A2, pack import = A1 — don't grow it into either. |
| `app/src/main/java/.../ui/theme/` | AMOLED true-black Material3; dynamic colour deliberately off (colour encodes importance). Full theming = A7. |
| `app/src/test/java/.../fsrs/FsrsSchedulerTest.kt` | FSRS-6 Again/Hard/Good/Easy + **upstream's regression oracle** (interval sequence must match java-fsrs exactly — catches a bad weight vector on a version bump). |
| `.claude/agents/*.md` | 27 subagent defs + model routing (haiku mechanics / sonnet compiles / opus judges). |
| `.claude/hooks/*.py` + `settings.json` | Durability: SessionStart resume injection + PreCompact/SessionEnd/SubagentStop. |
| `.claude/PLAYBOOK.md` | Verified API notes (Batch API, Docling, FSRS-6, **Android build toolchain**) + mistakes ledger. Read on demand — read the Android entry before touching any Gradle file. |
| `.claude/plans/` | Dated plan docs. |

## Don't touch / generated

- `C:\Users\kayom\keys\studyfeed\**` — **the release keystore + its passwords. Outside the repo on
  purpose (§11d).** Never copy in, never commit, never print. Losing it = no in-place upgrade =
  progress.db wiped. `app/build/`, `.gradle/`, `*.apk` are gitignored; never commit an APK (§11c).
- `compiler/sources/inbox/**` — **copyrighted archives.** Gitignored; never commit; never hand-unpack.
- `compiler/work/**`, `compiler/dist/**` — generated + gitignored (extracted text, pack DB, figures, reports).
  Fix the profile/compiler and re-run; never hand-edit outputs. Reports quoting stems stay in `dist/` (ignored).
- Stub modules — safe to flesh out at their stage; keep the docstring's spec reference accurate.
