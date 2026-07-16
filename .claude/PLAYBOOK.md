# PLAYBOOK.md — command cookbook & mistakes ledger for {{PROJECT_NAME}}

> A living reference for Claude Code and its agents. **Read it on demand** — it is
> intentionally NOT auto-loaded into context, so it costs nothing until it's needed.
>
> - *Before acting:* skim the relevant section instead of rediscovering a command or
>   re-hitting a known trap.
> - *After a stumble:* the `mistake-tracker` agent appends what was learned. A bug written
>   down once is a bug never debugged twice. Entries stay short, dated, and specific.

---

## Command cookbook (verified)

```bash
# Only commands that have actually been RUN and worked belong here, with the gotcha inline.
{{command}}    # {{what it does; any trap}}
```

---

## External service & API notes

Populated by the **`docs-researcher`** agent. Each entry: service, version, minimal correct
snippet, gotchas, source URL + date read. Keep the answer, drop the prose.

## API notes

### Anthropic Message Batches API (Python SDK `anthropic`) — read 2026-07-15

**Version:** `anthropic` 0.116.0 on PyPI (latest as of read date), requires Python >=3.9.
**Site rebrand:** `docs.claude.com` now 301/302-redirects to **`platform.claude.com`** — use the
new host in future lookups; both cited below for traceability.

```python
import time
import anthropic
from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
from anthropic.types.messages.batch_create_params import Request

client = anthropic.Anthropic()  # reads ANTHROPIC_API_KEY from env

# 1. Create — requests: list[Request]; Request/MessageCreateParamsNonStreaming are
#    TypedDicts (plain dicts of the same shape work identically at runtime; the
#    typed constructors just buy you IDE/mypy checking).
batch = client.messages.batches.create(
    requests=[
        Request(
            custom_id="item-001",  # required, unique per batch, ^[a-zA-Z0-9_-]{1,64}$
            params=MessageCreateParamsNonStreaming(
                model="claude-...",       # any active model
                max_tokens=1024,          # must be >=1; max_tokens=0 (cache pre-warm) is REJECTED in batch
                messages=[{"role": "user", "content": "..."}],
            ),
        ),
        # ... up to 100,000 requests OR 256 MB total, whichever hits first
    ]
)
# batch.processing_status == "in_progress" immediately after create

# 2. Poll — processing_status has exactly 3 values: "in_progress" | "canceling" | "ended"
while True:
    batch = client.messages.batches.retrieve(batch.id)
    if batch.processing_status == "ended":
        break
    time.sleep(60)
print(batch.request_counts)  # .processing / .succeeded / .errored / .canceled / .expired

# 3. Stream results — ORDER IS NOT GUARANTEED. Always match on custom_id.
for result in client.messages.batches.results(batch.id):
    match result.result.type:              # "succeeded" | "errored" | "canceled" | "expired"
        case "succeeded":
            msg = result.result.message
        case "errored":
            err_type = result.result.error.error.type  # e.g. "invalid_request_error" (fix+retry) vs other (safe to retry as-is)
        case "canceled" | "expired":
            pass  # not billed

# 4. Cancel (only meaningful while in_progress) — NOT immediate.
client.messages.batches.cancel(batch.id)  # -> processing_status becomes "canceling"; poll until "ended"
```

**MessageBatch object fields** (verified from API reference): `id`, `type` (always
`"message_batch"`), `processing_status`, `request_counts {processing, succeeded, errored,
canceled, expired}`, `created_at`, `expires_at` (= created_at + 24h), `ended_at`,
`cancel_initiated_at`, `archived_at`, `results_url` (null until `ended`, a `.jsonl` file).

**Limits / SLA / pricing (exact quotes, platform.claude.com/docs/en/build-with-claude/batch-processing):**
- "A Message Batch is limited to either 100,000 Message requests or 256 MB in size, whichever is reached first."
- "most batches completing within 1 hour... Batches expire if processing does not complete within 24 hours."
- "Batch results are available for 29 days after creation. After that, you may still view the Batch, but its results will no longer be available for download."
- "All usage is charged at 50% of the standard API prices."
- ZDR: "This feature is **not** eligible for Zero Data Retention (ZDR). Data is retained according to the feature's standard retention policy."
- Caching stacks: "The pricing discounts from prompt caching and Message Batches can stack... cache hits are provided on a best-effort basis. Users typically experience cache hit rates ranging from 30% to 98%." (identical `cache_control` blocks in every request + a steady request stream + `ttl:"1h"` instead of the 5m default help hit rate for long-running batches.)
- Order: "Batch results can be returned in any order, and may not match the ordering of requests when the batch was created... always use the `custom_id` field." (docs' own worked example shows request 2's result before request 1's.)
- Error surfacing: "Validation of the `params` object for each message request is performed **asynchronously**, and validation errors are returned when processing of the **entire batch has ended**." I.e. no create-time or streamed-individually validation — a malformed request only surfaces (as `result.type == "errored"` in that request's own result line) once the whole batch finishes, up to 24h later.
- Not allowed inside a batch request (validation error if included): `stream: true`, `speed` (fast mode), `store`/`previous_thread_event_id` (Threads), `cache_hint`/`context_hint`, `max_tokens: 0`.

**Sources:** [platform.claude.com/docs/en/build-with-claude/batch-processing](https://platform.claude.com/docs/en/build-with-claude/batch-processing) (redirected from docs.claude.com), [platform.claude.com/docs/en/api/creating-message-batches](https://platform.claude.com/docs/en/api/creating-message-batches), [pypi.org/project/anthropic](https://pypi.org/project/anthropic/) — all read 2026-07-15.

---

### Docling (Python PDF extraction) — read 2026-07-15

**Version:** `docling` 2.113.0 on PyPI (latest). Requires Python **>=3.10,<4.0** (3.9 support
dropped as of docling 2.70.0) — compiler targets 3.11, compatible, no action needed.

```bash
pip install docling
# CPU-only Linux (avoids pulling CUDA torch wheels — much smaller install):
pip install docling --extra-index-url https://download.pytorch.org/whl/cpu
# OCR-engine extras installed only if selected:
pip install "docling[tesserocr]"   # or easyocr / ocrmac / rapidocr
```

**Minimal convert + page numbers + reading order:**
```python
from docling.document_converter import DocumentConverter

converter = DocumentConverter()
doc = converter.convert("/path/to/file.pdf").document

# iterate_items() walks the body tree = reading order (documented guarantee, not an
# accident of implementation): "The reading order of the document is encapsulated
# through the body tree and the order of children in each item in the tree."
for item, level in doc.iterate_items():
    page_no = item.prov[0].page_no if item.prov else None  # 1-indexed
    text = getattr(item, "text", None)

print(doc.export_to_markdown())  # whole-doc markdown, reading order preserved
```
Ground truth (docling_core source): `DocItem.prov: list[ProvenanceItem] = []`;
`ProvenanceItem = {page_no: int, bbox: BoundingBox, charspan: CharSpan}`. Page numbers are
1-indexed (`DEFAULT_PAGE_RANGE: PageRange = (1, sys.maxsize)` in `docling/datamodel/settings.py`).

**OCR enable/disable + language (GOTCHA: language-code convention is per-engine):**
```python
from docling.datamodel.base_models import InputFormat
from docling.datamodel.pipeline_options import PdfPipelineOptions, EasyOcrOptions, TesseractCliOcrOptions
from docling.document_converter import DocumentConverter, PdfFormatOption

pipeline_options = PdfPipelineOptions()
pipeline_options.do_ocr = True             # default True; False disables OCR entirely
pipeline_options.do_table_structure = True # default True
# EasyOCR (2-letter ISO 639-1 codes; default ["fr","de","es","en"]):
pipeline_options.ocr_options = EasyOcrOptions(lang=["en"])
# Tesseract instead (3-letter codes; default ["fra","deu","spa","eng"]):
# pipeline_options.ocr_options = TesseractCliOcrOptions(lang=["eng"])
# Force full-page OCR (every page purely via OCR, vs only layout-flagged regions):
# pipeline_options.ocr_options = TesseractCliOcrOptions(lang=["eng"], force_full_page_ocr=True)

converter = DocumentConverter(format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=pipeline_options)})
```
`"en"` vs `"eng"` is **not** interchangeable and is not validated for you — EasyOCR silently
gets a wrong/no-op language if given `"eng"`, and vice versa for Tesseract. Default
`PdfPipelineOptions.ocr_options` is `OcrAutoOptions()` (auto-selects an engine) — always set
`ocr_options` explicitly when the language matters. `force_full_page_ocr: bool = False` lives
on the OCR-options object, not on `PdfPipelineOptions`.

**Tables / figures with page refs:**
```python
for table in doc.tables:                       # list[TableItem]
    df = table.export_to_dataframe(doc=doc)      # -> pandas.DataFrame
    html = table.export_to_html(doc=doc)
    page_no = table.prov[0].page_no

pipeline_options.generate_picture_images = True  # must be set BEFORE convert() to retain images
for picture in doc.pictures:                    # list[PictureItem]
    img = picture.get_image(doc)                  # PIL.Image (None if not generated)
    page_no = picture.prov[0].page_no
```

**Offline / first-run / heavy deps:** PyTorch is a hard dependency (layout, table-structure,
most OCR models run on it — use the `--extra-index-url .../cpu` install on CPU-only Linux).
Models auto-download from Hugging Face into `~/.cache/docling/models/` on first use. For a
build-time compiler that must not touch the network at run invocation:
```bash
docling-tools models download                              # predefined set -> ~/.cache/docling/models/
docling-tools models download --output-dir ./models --all   # everything, custom dir
```
then point the pipeline at the prefetched cache via **any one** of: `PdfPipelineOptions(artifacts_path=...)`,
the `--artifacts-path` CLI flag, or the `DOCLING_ARTIFACTS_PATH` env var. `docling` and
`docling-tools` are both console-script entry points installed by `pip install docling`
(defined in `pyproject.toml` under `[project.scripts]`). Recommended pattern for this repo:
prefetch once in the dev/CI image, set `DOCLING_ARTIFACTS_PATH` in the compiler's run env, and
the compiler itself never needs network for models.

**Sources:** [pypi.org/project/docling](https://pypi.org/project/docling/) (v2.113.0);
[docling-project.github.io/docling](https://docling-project.github.io/docling/usage/); GitHub
raw source (ground truth) — `docling/datamodel/pipeline_options.py`,
`docs/examples/{custom_convert,full_page_ocr,export_tables,export_figures,minimal}.py`,
`docs/getting_started/installation.md`, `docs/concepts/docling_document.md`,
`docling/cli/models.py` + `pyproject.toml`; and `docling-core`'s
`docling_core/types/doc/common/reference.py` + `items/node.py` (ProvenanceItem/DocItem.prov) —
all `docling-project/docling` and `docling-project/docling-core` on GitHub, read 2026-07-15.

---

### FSRS-6 for Kotlin/Android — read 2026-07-15

**IMPORTANT — corrects `BUILD_PROMPT.md` line 943-944.** That line offers "resolve
`io.github.open-spaced-repetition:fsrs` (Maven Central) **or** vendor `FSRS-Kotlin`" as if
those were two equivalent options. Verified they are **not**:

- `io.github.open-spaced-repetition:fsrs` **does** resolve on Maven Central — confirmed via
  the real repository directory listing, not a guess — but it is the **`java-fsrs`** project
  (pure Java, package `io.github.openspacedrepetition`), **not Kotlin**.
- `open-spaced-repetition/FSRS-Kotlin` on GitHub is **not a usable library**: its entire git
  tree is 3 files (`FSRS.kt`, `models.kt`, `README.md`) — no `LICENSE`, no `build.gradle`, no
  Maven Central publish, no GitHub releases. Worse, `FSRS.kt` imports
  `app.arteh.flashcard.db.{FlashCard,Grade,Rating}` and Android resources
  (`R.color.grad_blue`, etc.) that don't exist anywhere in that repo — it's a source dump from
  a *different, unrelated app*, not a generic library, and won't compile as-is. **Do not vendor
  this repo.**
- `open-spaced-repetition/android-fsrs` also exists but is a standalone demo Android *app*
  (not a library, not published), last pushed 2023-10-17 — predates FSRS-6. Not a fit.

**Verified Maven coordinate** (via `repo1.maven.org` directory listing +
`maven-metadata.xml`, group `io.github.open-spaced-repetition` has exactly one artifact, `fsrs`):

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.open-spaced-repetition:fsrs:1.0.0")
}
```
Version history on Central: 0.1.0 → 0.1.1 → 0.1.2 → 0.2.0 → 0.3.0 → 0.4.0 → 0.5.0 → 0.5.1 →
0.6.0 → 1.0.0-rc1 → **1.0.0** (latest/release, published 2025-07-30). Source:
[open-spaced-repetition/java-fsrs](https://github.com/open-spaced-repetition/java-fsrs), MIT,
GPG-signed release, Gradle module metadata published. **Java 17 minimum** (README badge). Pure
Java — fully usable from Kotlin via ordinary interop, no Kotlin-specific artifact exists in
this group. Runtime transitive deps: `jackson-databind:2.15.3` +
`jackson-datatype-jsr310:2.19.2` (used only by the library's own `toJson()/fromJson()`
convenience methods; Lombok is compile-time-only for the library, not a transitive dep).

**Scheduling API** (ground truth: `java-fsrs` source — `Card.java`, `Rating.java`, `State.java`,
`Scheduler.java`, `ReviewLog.java`, `CardAndReviewLog.java`):

- `Rating` enum: `AGAIN(1), HARD(2), GOOD(3), EASY(4)`.
- `State` enum: `LEARNING(1), REVIEW(2), RELEARNING(3)` — no separate "NEW" state; a fresh
  `Card.builder().build()` defaults to `state = LEARNING, step = 0`.
- `Card` (builder): `cardId: int` (auto from timestamp if omitted), `state: State`,
  `step: Integer` (nullable — null once in REVIEW), `stability: Double`, `difficulty: Double`,
  `due: Instant`, `lastReview: Instant`. Lombok `@Getter/@Setter` → these map to normal Kotlin
  properties (`card.due`, `card.stability`, ...).
- `Scheduler` (builder) — **this is how FSRS-6 weights/parameters are supplied:**
```java
Scheduler scheduler = Scheduler.builder()
    .parameters(myOptimizedWeights)   // double[21]; omit to use the built-in FSRS-6 defaults
    .desiredRetention(0.9)            // default
    .learningSteps(new Duration[]{Duration.ofMinutes(1), Duration.ofMinutes(10)})  // default
    .relearningSteps(new Duration[]{Duration.ofMinutes(10)})                        // default
    .maximumInterval(36500)           // default, days
    .enableFuzzing(true)              // default
    .build();
```
  Built-in default 21-weight FSRS-6 vector (`Scheduler.DEFAULT_PARAMETERS`, verified from
  source): `[0.2172, 1.1771, 3.2602, 16.1507, 7.0114, 0.57, 2.0966, 0.0069, 1.5261, 0.112,
  1.0178, 1.849, 0.1133, 0.3127, 2.2934, 0.2191, 3.0004, 0.7536, 0.3332, 0.1437, 0.2]`.
  `decay = -parameters[20]` is exactly FSRS-6's personalizable decay exponent — independently
  cross-checked against the algorithm's own wiki ("FSRS-6 has 21 trainable parameters"), not
  just inferred from this one source.
- Review a card / compute next due date + updated stability/difficulty:
```java
Card card = Card.builder().build();
CardAndReviewLog result = scheduler.reviewCard(card, Rating.GOOD, Instant.now());
card = result.card();                 // record accessor — NOT result.getCard()
Instant nextDue      = card.getDue();          // Kotlin: card.due
double  newStability  = card.getStability();   // Kotlin: card.stability
double  newDifficulty = card.getDifficulty();  // Kotlin: card.difficulty
ReviewLog log = result.reviewLog();   // record accessor — NOT result.getReviewLog()
```
  GOTCHA: `CardAndReviewLog` is a Java **record** — its accessors are bare `card()` /
  `reviewLog()`, which Kotlin does **not** auto-map to properties (that sugar only applies to
  JavaBean `getX()/isX()`), so call them as methods from Kotlin. Overloads:
  `reviewCard(card, rating)`, `(card, rating, reviewDatetime)`, `(card, rating, reviewDuration)`,
  `(card, rating, reviewDatetime, reviewDuration)`.
- Retrievability (feeds a weakness/priority score): `scheduler.getCardRetrievability(card, now)` → `Double` in [0,1].
- Regression oracle if this ever gets ported/wrapped: `FSRSTest.testReviewCard` — a fresh card
  reviewed GOOD×6, AGAIN×2, GOOD×5 (fuzzing disabled) produces interval-days sequence
  `[0, 4, 14, 45, 135, 372, 0, 0, 2, 5, 10, 20, 40]`.

**Recommendation for a headless, no-network-at-build-time Gradle build:** depend on the real,
signed, versioned Central artifact `io.github.open-spaced-repetition:fsrs:1.0.0` directly —
it's Java 17, nothing Android-incompatible in Card/Rating/State/Scheduler. "No network at
runtime" for Gradle is the standard dependency-resolution problem, not FSRS-specific: pre-warm
the Gradle cache (or a local Maven mirror) with `fsrs-1.0.0.jar` + its two Jackson deps in the
CI image, then build with `--offline`. If a hard zero-Maven-Central policy is required, do
**not** vendor `FSRS-Kotlin` (unlicensed and broken) — instead hand-port `Scheduler.java`
(~250 lines, MIT, self-contained) to Kotlin, using the interval sequence above as the
correctness check.

**Sources:** [repo1.maven.org/.../open-spaced-repetition/](https://repo1.maven.org/maven2/io/github/open-spaced-repetition/)
(directory listing), [.../fsrs/maven-metadata.xml](https://repo1.maven.org/maven2/io/github/open-spaced-repetition/fsrs/maven-metadata.xml),
[.../fsrs/1.0.0/fsrs-1.0.0.pom](https://repo1.maven.org/maven2/io/github/open-spaced-repetition/fsrs/1.0.0/fsrs-1.0.0.pom);
[github.com/open-spaced-repetition/java-fsrs](https://github.com/open-spaced-repetition/java-fsrs)
(source + README, MIT); [github.com/open-spaced-repetition/FSRS-Kotlin](https://github.com/open-spaced-repetition/FSRS-Kotlin)
(inspected directly, confirmed unusable); [github.com/open-spaced-repetition/android-fsrs](https://github.com/open-spaced-repetition/android-fsrs)
(inspected, confirmed stale/pre-FSRS-6); [awesome-fsrs wiki — The Algorithm](https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm)
(independent 21-parameter cross-check) — all read 2026-07-15.

---

## Mistakes ledger (real traps in THIS repo, with the fix)

Maintained by the **`mistake-tracker`** agent. Entry shape:

- **YYYY-MM · The rule, imperative, one line.** What happened and why, 1–3 sentences.
  **Fix/prevention:** the exact command, check, or habit that prevents it. (seen 1x)

Cross-project lessons go to `~/.claude/MISTAKES.md` instead. Any entry reaching `(seen 2x)`
gets promoted to a one-line rule in this repo's `CLAUDE.md`.

- **2026-07 · Use `unrar` for RAR5 extraction, not `unar`/libarchive.** At C1a, `unar` 1.10.1 (and
  libarchive) **silently truncated 3 PDFs mid-file** (`unar rc=1: "attempted to read more data than
  was available"`) — including a recent Aug-2022 past paper — leaving valid-looking but incomplete
  files. `unar` read the RAR5 *listing* fine at C0, which masked the *extraction* bug.
  **Fix/prevention:** `apt install unrar` (multiverse, RARLAB reference); `unpack.py` now prefers
  `unrar x -o+ -inul` for `.rar`, falling back to `unar` only if `unrar` is absent. After extracting
  any archive, verify a sample file's on-disk size equals the archive's stated size and that
  `pdfinfo` opens it. (seen 1x)

- **2026-07 · Text-based file-dedup is BLIND to scanned PDFs — never merge text-empty files except by
  exact hash.** At C1b the past papers are scanned images (0 chars, 0 fonts); `pdftotext` returned
  empty for every page, so all 34 dated papers shared one identical empty fingerprint and merged into
  2 "works" — which would have wiped out the entire PYQ signal (the heaviest weight). Sampling
  *content* pages instead of cover pages did NOT help (still image, still empty).
  **Fix/prevention:** flag a file `scanned` when sampled text < 30 words; scanned files may be deduped
  ONLY by exact SHA-256, never by fingerprint or fuzzy Jaccard. Content-level dedup for them is
  deferred to post-OCR clustering (C5). Check `pdffonts` / a page char-count before trusting any
  text-based comparison of PDFs. (seen 1x)

- **2026-07 · Scanned-page OCR: three gotchas that make it ~20× too slow or crash.** At C2, OCR'ing
  8,961 scanned pages: (a) the scans have HUGE native page dims (44 MP @150 dpi) — render at a FIXED
  pixel width (`fitz.Matrix(2500/page.rect.width, …)`), not a DPI, or tesseract takes ~17 s/page; (b)
  Python 3.14 defaults multiprocessing to `forkserver`, which can't re-import a `<stdin>`/`-m` main and
  crashes the pool — use `ProcessPoolExecutor(mp_context=mp.get_context('fork'))`; (c) tesseract
  multithreads by default, so N pool workers each spawn a multithreaded tesseract and thrash — set
  `OMP_THREAD_LIMIT=1` in the subprocess env so N workers = N threads = N cores.
  **Fix/prevention:** fixed-width render + fork context + `OMP_THREAD_LIMIT=1` → 0.77 s/pg on 4 cores
  (from 16.8). (seen 1x)

- **2026-07 · Same filename ≠ same file across drops — verify the SPECIFIC drop's path, not the name.**
  `16-Clinical Science.pdf` exists in BOTH the Qbank drop (Passmedicine *questions*) and the Textbook
  drop (Passmedicine High-Yield *Textbook* prose). A name-keyed smoke resolver grabbed the Qbank copy,
  so I reported the Textbook "N-Subject" files as "qbank exports" and nearly reclassified 16 prose
  textbook files to qbank (would have corrupted tiers + routed prose to MCQ-parsing).
  **Fix/prevention:** the pipeline is path-qualified — spot-checks must be too. Before changing a
  same-named file's classification, `pdftotext` the exact `dedupe_canonicals` path and check for
  content markers (`Question N of` vs prose). (seen 1x)

- **2026-07 · Read the TEXT block from a model result, never `content[0]` — Sonnet-5/Haiku-4.5 emit a
  leading `ThinkingBlock`.** At C4 all 40 test-batch requests succeeded server-side, but harvest crashed
  in `iter_results` with `AttributeError: 'ThinkingBlock' object has no attribute 'text'` — `m.content[0]`
  was a thinking block, not the answer. This would have broken the ENTIRE harvest of a paid run. (The
  traceback was a red herring: editing the running `.py` while it crashed mis-rendered the source lines;
  only the exception *message* was trustworthy.)
  **Fix/prevention:** `text = "".join(b.text for b in m.content if getattr(b, "type", None) == "text")` —
  skip thinking/other block types; never index `content[0]`. (seen 1x)

- **2026-07 · Content-selection filters must be SOURCE-aware, and JSON output needs token headroom.** At
  C4 the MCQ page filter keyed on `correct answer|explanation` — correct for Pastest (self-contained
  "Explanation" pages) but it missed ~63% of PassMedicine, which states the key in prose / peer-% not the
  word "explanation" (2358 of ~5396 qbank Qs). Separately, stitched PassMedicine questions have long
  explanations that overran `max_tokens=1200`, truncating the JSON mid-object → ~35% unparseable (paid,
  wasted).
  **Fix/prevention:** make `build_mcq` kind-aware (Pastest = answer-page filter; PassMedicine = `Question
  N of M` page stitched with its continuation pages); mcq `max_tokens=3000`; demand raw JSON (no ``` ```
  fences). ALWAYS run a zero-cost coverage audit (matched vs total; sample the SKIPPED set) BEFORE any
  mass-LLM spend. (seen 1x)
