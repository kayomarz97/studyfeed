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

### fastembed (Qdrant) — local text embeddings, C5 clustering — read 2026-07-16

**Version:** `fastembed` 0.8.0 on PyPI (latest, released 2026-03-23) — matches what's actually
installed. Requires Python **>=3.10**; classifiers cover 3.10–3.14. Already declared (unpinned,
on purpose) in `compiler/pyproject.toml` — no `uv add` needed, just `uv lock && uv sync`.

**Bundled automatically** (no separate install): `numpy`, `onnxruntime` (CPU build),
`huggingface-hub`, `tokenizers`, `pillow`, `loguru`, `tqdm`, `requests`, `mmh3`,
`py-rust-stemmers`. For Python 3.14 specifically, PyPI pins `numpy>=2.3.0` and
`onnxruntime>=1.24.2`. Do **not** install `fastembed-gpu` (separate, mutually exclusive package)
— plain `fastembed` auto-selects `CPUExecutionProvider` with zero flags when no GPU provider is
present (verified).

**Minimal correct usage** (verified by actually running it end-to-end, not just reading docs):
```python
from fastembed import TextEmbedding
import numpy as np

model = TextEmbedding(model_name="BAAI/bge-small-en-v1.5")   # this IS the default; dim=384
vectors = np.array(list(model.embed(stems, batch_size=256)))  # (n, 384) float32, already L2-normalized
```
- Ground truth signature (`fastembed/text/text_embedding.py`):
  `TextEmbedding.__init__(model_name="BAAI/bge-small-en-v1.5", cache_dir=None, threads=None, providers=None, cuda=Device.AUTO, device_ids=None, lazy_load=False, **kwargs)`.
- `.embed(documents, batch_size=256, parallel=None, **kwargs) -> Iterable[NumpyArray]` is a
  **generator** (`yield from`) — nothing runs until you iterate/`list(...)` it.
- Empirically confirmed on this exact box (Python 3.14.3, 4 cores): each item is
  `numpy.ndarray`, `dtype=float32`, `shape=(384,)`.
- Model metadata for `bge-small-en-v1.5` states query/passage prefixes are "not so necessary"
  (unlike the older non-v1.5 `bge-small-en`) — no need to prepend `"query: "`/`"passage: "` to
  MCQ stems.

**Normalization — definitive, do not re-normalize:** vectors ARE L2-normalized by fastembed
itself. Source (`fastembed/common/utils.py::normalize`, called unconditionally from
`OnnxTextEmbedding._post_process_onnx_output`) does `v / max(||v||_2, 1e-12)`. Empirically
confirmed on real output: `np.linalg.norm(vec) == 1.0` (± float32 rounding) and
`np.dot(vec, vec) == 1.0`. **Plain dot product = cosine similarity.**

**Offline / cache — definitive:**
- Cache dir resolution: `cache_dir=` kwarg > `FASTEMBED_CACHE_PATH` env var > default
  `<tempdir>/fastembed_cache` (e.g. `/tmp/fastembed_cache` — not persistent across reboots on
  most distros; **pin `FASTEMBED_CACHE_PATH` explicitly for a build box**). First call downloads
  ~67 MB from HF repo `qdrant/bge-small-en-v1.5-onnx-q` via `huggingface_hub.snapshot_download`.
- **v0.8.0 (installed version) newly respects the standard `HF_HUB_OFFLINE=1` env var**
  ([PR #614](https://github.com/qdrant/fastembed/pull/614), merged 2026-03-23). Empirically
  confirmed: with the model already cached, setting only `HF_HUB_OFFLINE=1` (no code change, no
  explicit `local_files_only=`) makes `TextEmbedding(...)` load+embed in ~0.2 s with zero network.
  **On fastembed <0.8.0 the env var alone was NOT honored** — you had to pass
  `local_files_only=True` explicitly; that kwarg still works in 0.8.0 too.
- Gotcha: without `HF_HUB_OFFLINE=1` / `local_files_only=True`, fastembed calls the HF API
  (`model_info`/`list_repo_tree`) on **every** `TextEmbedding()` construction to verify the cache
  — "already downloaded" alone does not guarantee zero network. Recommended pattern: warm the
  cache once with network, then set `HF_HUB_OFFLINE=1` for the actual build run.

**CPU / threading — verified by reproducing a crash, not just reading docs:**
- `threads=None` (default) leaves onnxruntime's own intra-op threading, which already uses all
  cores in one process — measured **58 texts/sec single-process** on this box's 4 cores with
  realistic ~415-char MCQ-stem input (9,576 stems -> 165 s total, well under 3 min). No CPU flag
  needed.
- **Do NOT call `.embed(texts, parallel=0)` (or any `parallel>1`) from a plain script/`-m`
  entrypoint without an `if __name__ == "__main__":` guard.** Reproduced the exact crash class
  already in this ledger for tesseract: fastembed's `ParallelWorkerPool` hardcodes
  `"forkserver"` (falling back to `"spawn"`) and **never** `"fork"`
  (`fastembed/text/onnx_text_model.py::_embed_documents`) — running it unguarded throws
  `ConnectionResetError [Errno 104]` / "attempt... before bootstrapping phase", same failure
  mode as the C2 OCR pool, different library. Since single-process already clears the full
  9,576-stem corpus in ~3 minutes, **use the default `parallel=None` for C5 and skip
  multiprocessing entirely** — sidesteps the crash class and uses less peak memory (each
  parallel worker loads its own model+tokenizer copy, non-trivial on a box already low on free
  RAM). If parallel is ever wanted anyway: fastembed's own workers already pin themselves to
  `threads=1` internally, so there's no thread-oversubscription risk (unlike tesseract) — only
  the start-method crash, i.e. a `__main__`-guard problem, not a double-threading one.

**Sources:** [pypi.org/project/fastembed](https://pypi.org/project/fastembed/) (v0.8.0, exact
`requires_dist` via the PyPI JSON API); GitHub `qdrant/fastembed` source read directly (ground
truth): `fastembed/text/text_embedding.py`, `fastembed/text/onnx_embedding.py`,
`fastembed/text/onnx_text_model.py`, `fastembed/common/{utils,onnx_model,model_management,types}.py`;
[GitHub releases](https://github.com/qdrant/fastembed/releases) (v0.8.0 changelog) and
[PR #614](https://github.com/qdrant/fastembed/pull/614) (`HF_HUB_OFFLINE` fix); own empirical
runs in this repo's exact toolchain (`uv run --python 3.14 --with fastembed`, 4-core box) — all
read/verified 2026-07-16.

---

### Android build toolchain (AGP / Gradle / Kotlin / Compose) — read 2026-07-16

Scaffolding a new Android app on this machine: Windows 11, JBR 21.0.9 bundled with Android
Studio at `C:\Program Files\Android\Android Studio\jbr` (`java` NOT on PATH), SDK platforms
android-36/36.1 only, build-tools 36.0.0/36.1.0 only, **no Gradle installed, no `gradle` on
PATH**. Target compileSdk/targetSdk 36, minSdk 26, Kotlin+Compose(Material3)+Room+DataStore,
fully offline app.

**Version catalog (`gradle/libs.versions.toml`) — exact strings, verified 2026-07-16:**
```toml
[versions]
agp = "9.3.0"
kotlin = "2.4.10"
composeBom = "2026.06.01"

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
# ^ compose-bom 2026.06.01 maps to compose-ui/runtime/foundation/animation 1.11.4, material3 1.4.0
```

**1. AGP 9.3.0** (released July 2026, "Android Gradle plugin 9.3.0"). Raw compatibility table
(fetched HTML, not the summarizer — ground truth):
```
Gradle          min 9.5.0   default 9.5.0
SDK Build Tools min 36.0.0  default 36.0.0
NDK             default 28.2.13676358
JDK             min 17      default 17
```
The table states JDK **17** as both minimum and default — it does **not** explicitly bless JDK
21. However: (a) the same docs elsewhere say "choose a JDK version that is higher than or equal
to" the plugin's minimum (`/build/jdks`), i.e. 17 is a floor not a ceiling; (b) Gradle's own
compatibility matrix states "A JVM version between 17 and 26 is required to execute Gradle" for
the 9.5.x/9.6.x line; (c) Google's own `android/gradle-recipes` repo, `agp-9.3` branch (the exact
matching version), ships a working project built with Gradle 9.5.0. **Conclusion: JDK 21.0.9
(the bundled JBR) satisfies AGP 9.3.0's ≥17 requirement and is within Gradle's supported 17–26
execution range — this is a sourced inference from the "minimum" semantics + Gradle's own JVM
range, not an explicit "JDK 21 supported" sentence in the AGP table itself.** AGP 9.3 max API
level = 37.0.
Source: [developer.android.com/build/releases/agp-9-3-0-release-notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes) (raw HTML table, read 2026-07-16); [developer.android.com/build/jdks](https://developer.android.com/build/jdks); [docs.gradle.org/current/userguide/compatibility.html](https://docs.gradle.org/current/userguide/compatibility.html).

**2. Gradle 9.6.1** — latest stable, released 2026-06-26; compatible with AGP 9.3.0 (which needs
≥9.5.0). Gotcha: Gradle's own compatibility page says it is "tested with Android Gradle Plugin
9.0 through 9.3.0-alpha06" (final 9.3.0 not explicitly named as tested) — a minor gap, but
Google's `agp-9.3` recipe branch itself pins Gradle 9.5.0, so 9.6.1 (same minor line, newer
patch) is expected to work; if a build breaks, fall back to `9.5.0` (AGP's own default).
```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip
```
Official published SHA-256 for that exact distribution (`gradle-9.6.1-bin.zip.sha256`, fetched
directly from `services.gradle.org`, read 2026-07-16):
```
9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14
```
Source: [gradle.org/releases](https://gradle.org/releases/); [services.gradle.org/distributions/gradle-9.6.1-bin.zip.sha256](https://services.gradle.org/distributions/gradle-9.6.1-bin.zip.sha256); [docs.gradle.org/current/userguide/compatibility.html](https://docs.gradle.org/current/userguide/compatibility.html).

**3. Kotlin 2.4.10** (bug-fix release, 2026-07-14; base language release is 2.4.0, 2026-06-03).
Confirmed: since Kotlin 2.0 the Compose compiler still lives in the Kotlin repo as the
`org.jetbrains.kotlin.plugin.compose` Gradle plugin, versioned identically to Kotlin itself (no
separate `kotlinCompilerExtensionVersion` to track). Apply alongside the Kotlin Android plugin:
```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)   // org.jetbrains.kotlin.plugin.compose, version == kotlin version
}
```
Source: [kotlinlang.org/docs/releases.html](https://kotlinlang.org/docs/releases.html); [developer.android.com/develop/ui/compose/compiler](https://developer.android.com/develop/ui/compose/compiler).

**4. Compose BOM 2026.06.01** (also `2026.06.00` exists one patch behind). Maps to:
`compose-ui`/`compose-runtime`/`compose-foundation`/`compose-animation` → `1.11.4`,
`compose-material3` → `1.4.0`. Since Kotlin 2.0+, the old Compose-to-Kotlin compatibility table
is superseded — "When you use the Compose Compiler Gradle plugin, you don't have to check
Compose to Kotlin compatibility" (verbatim from docs).
Source: [developer.android.com/develop/ui/compose/bom/bom-mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping); [developer.android.com/jetpack/androidx/releases/compose-kotlin](https://developer.android.com/jetpack/androidx/releases/compose-kotlin).

**5. Merged-manifest verification task — the current, non-deprecated Variant/Artifacts API**
(verified against Google's own `android/gradle-recipes`, **`agp-9.3` branch** — the exact
version match — `transformManifest/build-logic/plugins/src/main/kotlin/{CustomPlugin,CheckMergedManifestTask}.kt`,
read 2026-07-16; no deprecation of any of this API found in
[gradle-plugin-api-updates](https://developer.android.com/build/releases/gradle-plugin-api-updates)
through AGP 8.12/9.x):
```kotlin
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

abstract class CheckMergedManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty   // Provider<RegularFile> target

    @TaskAction
    fun taskAction() {
        val text = mergedManifest.get().asFile.readText()
        // ... fail the build here if a forbidden permission/component is found
    }
}

// in your plugin's apply():
val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
androidComponents.onVariants { variant ->
    val taskName = "check${variant.name.replaceFirstChar { it.titlecase() }}MergedManifest"
    project.tasks.register<CheckMergedManifestTask>(taskName) {
        mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
}
```
**Key facts:** `variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)` returns a
`Provider<RegularFile>`; assigning it into a `RegularFileProperty` (`@InputFile`) is what wires
the implicit task dependency — AGP registers the real producing task under the hood, so there is
**no hardcoded task-name dependency** (`dependsOn("mergeDebugResources")` etc. is neither needed
nor idiomatic). `SingleArtifact.MERGED_MANIFEST` is documented as `Replaceable` and
`Transformable`; for pure *read-only verification* (this use case) only `.get()` is needed, not
`.use(...).wiredWithFiles(...).toTransform(...)` (that variant is for actually rewriting the
manifest). No current deprecation of `onVariants`/`AndroidComponentsExtension`/`artifacts.get` —
the AGP API-updates page's most recent removals stop at AGP 8.0 (old Transform API) and 8.8
(Resource Configurations DSL), nothing touching this path through 9.x.
Source: [github.com/android/gradle-recipes, branch `agp-9.3`](https://github.com/android/gradle-recipes/tree/agp-9.3/transformManifest) (official Google-maintained repo, matches our exact AGP version); [developer.android.com/build/releases/gradle-plugin-api-updates](https://developer.android.com/build/releases/gradle-plugin-api-updates).

**6. Dependency denylist check (group:module across transitives), config-cache-safe:**
Android variant runtime classpath configuration name = **`<variant>RuntimeClasspath`** (e.g.
`debugRuntimeClasspath`); compile classpath = `<variant>CompileClasspath` (e.g.
`debugCompileClasspath`) — confirmed via `developer.android.com/build/gradle-dependency-resolution`.
```kotlin
abstract class DenylistCheckTask : DefaultTask() {
    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @TaskAction
    fun check() {
        rootComponent.get().dependencies.forEach { dep ->
            // walk dep (ResolvedDependencyResult) -> dep.selected (ResolvedComponentResult)
            // -> .moduleVersion (ModuleVersionIdentifier: group/name/version) against denylist
        }
    }
}

// wiring (configuration time):
tasks.register<DenylistCheckTask>("checkDebugDenylist") {
    rootComponent.set(
        configurations.named("debugRuntimeClasspath").get()
            .incoming.resolutionResult.rootComponent   // Provider<ResolvedComponentResult>
    )
}
```
**Config-cache gotcha (Gradle official docs, `configuration_cache_requirements.html`):**
"referencing resolved dependency results is disallowed" if captured eagerly
(`ArtifactResolutionQuery`, `ResolvedArtifact`, `ArtifactResult` as task fields) — the compatible
pattern is `resolutionResult.rootComponent`, a **lazy `Provider<ResolvedComponentResult>`** that
"tracks the producer tasks of this resolution result." Declare it as a `Property<...>` task
input, never hold the `Configuration` object itself on the task. If you need the resolved *files*
(not just component identity), declare a `FileCollection` input instead of referencing the
`Configuration`.
Source: [docs.gradle.org/current/userguide/graph_resolution.html](https://docs.gradle.org/current/userguide/graph_resolution.html); [docs.gradle.org/current/userguide/configuration_cache_requirements.html](https://docs.gradle.org/current/userguide/configuration_cache_requirements.html); [developer.android.com/build/gradle-dependency-resolution](https://developer.android.com/build/gradle-dependency-resolution).

**7. Bootstrapping the wrapper with NO Gradle installed (this machine's exact situation):**
Gradle's own docs are explicit that there's no way around installing a Gradle runtime first:
"Generating the Wrapper files requires an installed version of the Gradle runtime on your
machine" (`gradle_wrapper.html`). The officially documented manual-install path
(`docs.gradle.org/current/userguide/installation.html`, which itself uses `gradle-9.6.1` in its
own worked example):
1. Download `https://services.gradle.org/distributions/gradle-9.6.1-bin.zip`.
2. Extract to e.g. `C:\Gradle\gradle-9.6.1` (any folder).
3. Add `C:\Gradle\gradle-9.6.1\bin` to `PATH` (temporarily, just to bootstrap); verify `gradle -v`.
4. From the new project root (no `gradlew` yet): `gradle wrapper --gradle-version 9.6.1` — this
   generates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
   `gradle/wrapper/gradle-wrapper.properties`. After this, always use `./gradlew`/`gradlew.bat`;
   the manually-installed Gradle can be removed from PATH/deleted.
**Gap — could NOT verify from official docs:** whether Android Studio's own installation bundles
a directly CLI-invokable Gradle distribution at a stable, documented path. Nothing in
`docs.gradle.org` or `developer.android.com` states this, so do not assume such a path exists —
use the manual-zip method above instead, which IS officially documented.
(Non-official but factual, if a bootstrap shortcut is ever wanted: Google's own
`android/gradle-recipes` GitHub repo ships pre-generated `gradlew`/`gradle-wrapper.jar` +
`gradle-wrapper.properties` per sample, checked into git — confirmed present in the `agp-9.3`
branch — which could be copied instead of running `gradle wrapper` locally, but this is a
convenience observation, not the documented procedure.)
Source: [docs.gradle.org/current/userguide/gradle_wrapper.html](https://docs.gradle.org/current/userguide/gradle_wrapper.html); [docs.gradle.org/current/userguide/installation.html](https://docs.gradle.org/current/userguide/installation.html).

---

### Android Auto Backup (progress.db cloud backup) — read 2026-07-17

**Context:** offline sideloaded app, minSdk 26 / targetSdk 36, `android:allowBackup="false"`
currently. Question was whether enabling Google-Drive Auto Backup mitigates "user loses the
signing keystore." **Verdict: it does NOT.** Restore is signature-gated at the framework level.

**1. THE deciding fact — restore checks the signing certificate, and a from-scratch new key fails it.**
Not documented in prose on developer.android.com (confirmed absent from `/identity/data/autobackup`,
`/identity/data/backup`, `/identity/data/keyvaluebackup`, `/reference/.../BackupManager`, and
`/studio/publish/app-signing` — explicitly checked, none mention it). Ground truth comes from AOSP
source (frameworks/base), which both key-value and file-based (Auto Backup) restores share via
`PerformUnifiedRestoreTask`:
```java
// services/backup/java/com/android/server/backup/PerformUnifiedRestoreTask.java
if (!BackupUtils.signaturesMatch(metaInfo.sigHashes, mCurrentPackage, pmi)) {
    Slog.w(TAG, "Signature mismatch restoring " + packageName);
    ...
    executeNextState(UnifiedRestoreState.RUNNING_QUEUE);   // <- restore for THIS package is skipped
    return;
}
```
```java
// services/core/java/com/android/server/backup/BackupUtils.java — signaturesMatch()
// Don't allow unsigned apps on either end...
final int nStored = storedSigHashes.size();
if (nStored == 1) {
    // if the app is only signed with one sig, it's possible it has rotated its key
    // the checks with signing history are delegated to PackageManager
    return pmi.isDataRestoreSafe(storedSigHashes.get(0), target.packageName);
} else { /* multi-signer: require every stored hash present in current signers */ }
```
System-partition apps (`FLAG_SYSTEM`) are explicitly exempted — irrelevant here, this app is a
normal sideloaded app. **Key rotation (APK Signature Scheme v3 lineage) is the only escape hatch**,
and it requires signing a rotation certificate *with the original key* before it's lost — i.e. you
must plan ahead while you still have the old keystore. If the keystore is already lost and you sign
a brand-new, unrelated key, there is no lineage, `isDataRestoreSafe` returns false, restore of that
package's data is silently skipped (log line only: "Signature mismatch restoring `<pkg>`"), and the
user gets a clean install with **zero backed-up data** — no error surfaced to the user in the UI.
**Conclusion: Auto Backup does not solve "lost keystore, need new signature, same applicationId."**
It only helps for uninstall/reinstall or new-device scenarios where the SAME keystore signs the
reinstall. If keystore loss is the threat being mitigated, the fix must be an app-level export/import
of `progress.db` (e.g. Storage Access Framework "Save As" to a user-chosen file/cloud folder), not
Auto Backup.
Sources (docs pages checked and found silent on signature/restore):
[developer.android.com/identity/data/autobackup](https://developer.android.com/identity/data/autobackup),
[developer.android.com/identity/data/backup](https://developer.android.com/identity/data/backup),
[developer.android.com/identity/data/keyvaluebackup](https://developer.android.com/identity/data/keyvaluebackup),
[developer.android.com/studio/publish/app-signing](https://developer.android.com/studio/publish/app-signing).
Ground truth (AOSP, read 2026-07-17):
[.../BackupUtils.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/services/core/java/com/android/server/backup/BackupUtils.java),
[.../PerformUnifiedRestoreTask.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/services/backup/java/com/android/server/backup/restore/PerformUnifiedRestoreTask.java).

**2. No permission needed; system does the work, not the app.**
Backup/restore is performed by the OS Backup Manager + Google's transport (`BackupTransportService`
in GMS), out-of-process from the app. Docs confirm restore happens purely on install: "Data is
restored whenever the app is installed, whether from the Play Store, during device setup..., or by
running `adb` install." Nothing in `/identity/data/autobackup`, `/identity/data/backup`, or the
`<application>` manifest docs requires any manifest `<uses-permission>` for Auto Backup to function —
INTERNET permission is not required (the app never touches the network; the OS/GMS process does).
Source: [developer.android.com/identity/data/autobackup](https://developer.android.com/identity/data/autobackup).

**3. Quota: 25 MB per app, per user — unchanged as of this read, and it's an all-or-nothing miss, not partial.**
> "Every app can allocate up to 25 MB of backup data per app user."
> "Caution: If the amount of data is over 25 MB, the system calls `onQuotaExceeded()` and doesn't
> back up data to the cloud."
> "The system periodically checks whether the amount of data later falls under the 25 MB threshold
> and continues Auto Backup when it does."
Implication for this app: **the large imported content-pack `.db` (hundreds of MB) must be
`<exclude>`d from the backup rules**, or it alone blows the 25 MB quota and the small `progress.db`
never gets backed up either (whole-app quota, not per-file). Key-value backup (separate, older API)
has its own, smaller 5 MB/app limit — not relevant here since this app would use Auto Backup (file
rules), not `BackupAgentHelper` key-value.
Source: [developer.android.com/identity/data/autobackup](https://developer.android.com/identity/data/autobackup), [developer.android.com/identity/data/backup](https://developer.android.com/identity/data/backup).

**4. Include/exclude XML — BOTH the new and legacy attribute must be declared for minSdk 26.**
API 31+ (Android 12+) uses `android:dataExtractionRules`; the old `android:fullBackupContent` is
**ignored** on API 31+ but is still what API ≤30 devices read, so both must be shipped side by side:
> "When you point to the new XML configuration, the `android:fullBackupContent` attribute that
> points to the old config is ignored on devices running Android 12 or higher." (the old config
> "is still required for devices running Android 11 or lower.")
Manifest:
```xml
<application
    android:allowBackup="true"
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules">
```
`res/xml/data_extraction_rules.xml` (API 31+; note separate `<cloud-backup>` vs `<device-transfer>`
sections — this app only cares about cloud-backup, but rules typically must exist in both blocks or
device-transfer defaults to including everything):
```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
  <cloud-backup disableIfNoEncryptionCapabilities="true">
    <exclude domain="database" path="." />
    <include domain="database" path="progress.db" />
  </cloud-backup>
  <device-transfer>
    <exclude domain="database" path="." />
    <include domain="database" path="progress.db" />
  </device-transfer>
</data-extraction-rules>
```
`res/xml/backup_rules.xml` (API ≤30 legacy, `<full-backup-content>` root tag, same include/exclude
semantics, no cloud-backup/device-transfer split):
```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
  <exclude domain="database" path="." />
  <include domain="database" path="progress.db" />
</full-backup-content>
```
Valid `domain` values confirmed on the canonical schema page: `file`, `database`, `sharedpref`,
`external`, `root` (the `device_*`-prefixed variants surfaced by one AI-summarized fetch could NOT
be corroborated on the direct schema quote and should be treated as unverified — don't rely on them).
The `database` domain corresponds to files under `Context.getDatabasePath(String)` (includes
Room/SQLite `-wal`/`-shm` sidecar files, since those live alongside the `.db` under the same path).
`disableIfNoEncryptionCapabilities` is a `<cloud-backup>`-only optional attribute (default false);
setting `true` skips cloud backup entirely on devices/OS versions that can't guarantee client-side
encryption of the backup blob — worth setting `true` for FSRS/health-adjacent study data.
Sources: [developer.android.com/about/versions/12/behavior-changes-12#backup-restore](https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore), [developer.android.com/guide/topics/manifest/application-element](https://developer.android.com/guide/topics/manifest/application-element).

**5. `allowBackup` default is `true` at every API level, unchanged for targetSdk 36; must set explicitly to false to opt out (already done in this app).**
> "The default value of this attribute is `true`." — the manifest docs make no exception for any
> current targetSdk. **Gap — could not verify:** no official changelog entry found (checked
> `/identity/data/autobackup`, manifest docs) describing any Android 13/14/15/16 change to
> Auto Backup's on/off default or its scheduling; the 24h/idle/Wi-Fi conditions below are stated as
> current with no version caveat, so treat them as still current for API 36 devices. One
> manufacturer-specific carve-out is documented: "On devices from some device manufacturers, you
> can't disable device-to-device migration of your app's files" via `allowBackup="false"` alone —
> cloud backup can still be disabled by that flag, but D2D migration on some OEM skins may not be.
Source: [developer.android.com/guide/topics/manifest/application-element](https://developer.android.com/guide/topics/manifest/application-element).

**6. Works for sideloaded/adb-installed apps; requires a Google account + device-level backup toggle on.**
> "Data is restored whenever the app is installed, whether from the Play Store, during device setup
> ..., or by running `adb install`." Cloud backup data is stored "in a private folder in the user's
> Google Drive account" — a signed-in Google account is required by construction (not stated as an
> explicit precondition in prose, but follows directly from where the data is stored — flagging this
> inference rather than presenting it as a verbatim quote). Also requires: "The user has enabled
> backup on the device" (Settings > System > Backup) and idle/Wi-Fi conditions (see #8). D2D
migration (phone-to-phone Wi-Fi Direct-style transfer during setup) is a **separate transport**
(`D2dTransport`, tested via a different `bmgr transport`/`backup_enable_d2d_test_mode` flag) from
the Google Drive cloud transport (`com.google.android.gms/.backup.BackupTransportService`) — same
manifest rules apply to both, but the question's threat model (new key on new device) fails the
same signature check under either transport, since both funnel through the same
`PerformUnifiedRestoreTask`/`BackupUtils.signaturesMatch` code path in AOSP.
Source: [developer.android.com/identity/data/autobackup](https://developer.android.com/identity/data/autobackup), [developer.android.com/identity/data/testingbackup](https://developer.android.com/identity/data/testingbackup).

**7. SQLite/WAL consistency during backup — Gap: not addressed by official docs.**
Checked `/identity/data/autobackup`, `/identity/data/backup`, `/identity/data/keyvaluebackup`: none
discuss WAL/journal files, whether the DB must be closed, or corruption risk for a live SQLite file
captured mid-write. The `database` domain is documented only as covering files under
`getDatabasePath(String)` — no consistency guarantee is stated anywhere. **Do not assume atomicity.**
If this feature is built, treat it like any other file-copy backup of a live SQLite DB: checkpoint
WAL into the main file (`PRAGMA wal_checkpoint(TRUNCATE)`) and only back up while the DB is closed or
between transactions to avoid restoring a torn/inconsistent file — this is general SQLite guidance,
not something the Android backup docs promise for you.
Source: gap — none of the fetched official pages mention this; flagging rather than guessing.

**8. Backup timing: system-scheduled only; nothing the app can trigger for Auto Backup (file-based).**
> "Backups occur automatically when all of the following conditions are met: The user has enabled
> backup on the device... At least 24 hours have elapsed since the last backup... The device is
> idle... The device is connected to a Wi-Fi network (if the user hasn't opted in to mobile-data
> backups)." "In practice, these conditions occur roughly every night, but a device might never back
> up (for example, if it never connects to a network)." This matches the "no background work"
> constraint — the app does nothing; the system's own daemon runs the pass. `BackupManager.dataChanged()`
> exists but **explicitly does not apply here** — AOSP javadoc: "Note: This only works if your
> application is performing Key/Value backups." Auto Backup (file-based, what this app would use) has
> no app-triggerable "back up now" API in the public SDK; only manual testing hooks (`adb shell bmgr
> backupnow <pkg>`, requires a debug/dev-enabled `bmgr`) exist, not something shippable in production.
Sources: [developer.android.com/identity/data/autobackup](https://developer.android.com/identity/data/autobackup) (schedule quotes), AOSP [BackupManager.java `dataChanged()` javadoc](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/app/backup/BackupManager.java) (read 2026-07-17).

**Bottom line for the decision:** Auto Backup is real, free, requires no permissions/network code
in the app, and can be scoped with include/exclude rules to just `progress.db` while excluding the
large content-pack `.db` (must exclude it — would blow the 25 MB quota otherwise). But it is
**useless for the "lost keystore" disaster** because restore is hard-gated on signing-certificate
match (or a pre-established key-rotation lineage signed with the *old* key before it was lost) — a
brand-new unrelated key on a same-`applicationId` reinstall gets a clean install with no data
restored, silently. If keystore-loss recovery is the goal, pair Auto Backup (nice-to-have, covers
uninstall/reinstall-with-same-key and device-migration cases) with an explicit in-app export/import
of `progress.db` via Storage Access Framework, which is signature-independent.

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

- **2026-07 · A STRIDED sample under-counts near-dup MERGE RATE — validate precision on it, never recall.**
  At C7 an 8k *strided* sample of 133k atoms collapsed only 4.9%, so the SIM threshold looked safe; the
  full run collapsed ~39% and exposed catastrophic over-merges. Striding scatters duplicate pairs so few
  co-occur in the sample, hiding the true merge rate.
  **Fix/prevention:** tune/inspect merge PRECISION on a strided sample, but estimate merge RATE and hunt
  over-merges only on the FULL set (or a CONTIGUOUS slice that keeps dup families together). Cache
  embeddings (`work/atom_vecs.npy`) so full re-runs are ~2 min, not ~18. (seen 1x)

- **2026-07 · Embedding cosine ALONE over-merges short TEMPLATED facts — use a dual gate + LEADER
  clustering, not single-linkage union-find.** At C7, `"X causes Y"` atoms score ~0.95 cosine even when X
  differs; single-linkage chained a whole template family into one 74-member FALSE cluster
  (digoxin/hypokalaemia/ischaemic… "prolonged PR interval"), which LAUNDERS distinct facts into fake
  corroboration (§5b/§364) — the exact anti-pattern the spec warns about.
  **Fix/prevention:** gate a merge on cosine≥0.95 AND IDF-weighted entity-token overlap≥0.5 (keys on the
  rare words template-siblings do NOT share), and cluster by LEADER (an atom joins only if it clears both
  gates vs the cluster's LEADER, never a transitive intermediate) so one stray edge can't chain a family.
  Verify: `distinct_work_count` canonicalised (§5b), largest cluster small + genuine, all atoms conserved.
  Precision-first — tuning has an irreducible inversion (a heavily-reworded TRUE pair can score below a
  template FALSE pair). (seen 1x)

- **2026-07 · Item-type-specific FLOORS must not be applied across item types.** At C9 the
  multi-year-PYQ→5★ floor is CONCEPT/MCQ-only per SCORING.md, but I applied it to atoms via the UNION of
  their linked concepts' PYQ years — an atom mentioning 2+ separately-PYQ'd concepts trivially hit "≥2
  years" and force-promoted, giving a degenerate 33% of atoms at 5★.
  **Fix/prevention:** read the floor's SUBJECT in SCORING.md (MCQ / concept / atom) — atoms get PYQ only
  as an inherited SIGNAL, never the concept multi-year floor. Sanity-check every score pass with a bucket
  histogram; any bucket >45% or an unexpectedly fat 5★ band ⇒ a floor is mis-firing. (seen 1x)

- **2026-07 · Never quote an output-heavy LLM cost from an assumed format — MEASURE on a real sample.**
  At C8 I estimated ~$34 for Sonnet concept extraction; the measured cost was ~$104 (3×), because output
  tokens (Sonnet $15/M, the dominant term) were far higher than my JSON-format guess. The gate approval
  was for the wrong number.
  **Fix/prevention:** before any budget-gate quote, run the real prompt on a ≥100-item sample and
  extrapolate from ACTUAL in/out tokens; switching JSON→terse (`N|s|r|o`) cut output ~40%, and Haiku +
  a Sonnet-subagent audit did the job for ~$6. Output tokens dominate cost — estimate them empirically. (seen 1x)

- **2026-07 · Never blind-merge orthographic near-duplicate ENTITY names — medical homonyms differ by one
  letter.** C8's `alias.canonical_key` didn't normalise plurals / hyphens / underscores / apostrophes, so
  400+ trivial variants ("absence seizure"/"seizures", "Addison disease"/"Addison's") became alias
  proposals and split concept nodes. The tempting fix — a blind plural/hyphen strip — would have WRONGLY
  merged distinct entities: *Klebsiella pneumonia* vs *pneumoniae* (disease vs species), *hepatitis B* vs
  *C*, *hypocalcaemia* vs *hypokalaemia*, *folic* vs *folinic acid*, *LDH* vs *LDL*, *acute fever* vs
  *acute Q fever*, and every type/stage/class/laterality family.
  **Fix/prevention:** an Opus subagent (medical knowledge, Claude Code usage — no API spend) adjudicated
  all 400 pairs → 226 same / 174 different; only the confirmed-same went into
  `profile.nomenclature.alias_hints` (explicit, verified). A one-entity-one-node merge is irreversible
  corruption, so adjudicate near-identical names with domain knowledge; default to KEEP-SEPARATE when a
  letter/number/qualifier distinguishes them. (seen 1x)

- **2026-07 · `sorted(set(...))` with a NON-TOTAL key is not reproducible across processes — string hash
  randomization reorders ties.** C10 dossiers built byte-differently on two runs: connections were
  `sorted(set(conns), key=lambda x: (-sources, relation, other))`, and two edges tying on all three but
  differing in *direction* fell back to `set` iteration order — which depends on `PYTHONHASHSEED` (strings
  are salted per process), so the page text changed run-to-run. Reproducible scoring is a hard
  non-negotiable, and a per-run diff on the SAME seed would have missed it entirely.
  **Fix/prevention:** every `sorted()` that feeds output must have a **total** key (append a unique
  tiebreak — here the direction; elsewhere the row id/index). Test determinism by rebuilding under two
  DIFFERENT seeds: `PYTHONHASHSEED=1 … && PYTHONHASHSEED=999 … && diff`. Never trust a same-seed re-run to
  prove reproducibility. (seen 1x)

- **2026-07 · A deterministic grounding gate must not drop short tokens or check only the answer field —
  it false-rejects correct short-answer cards.** At C11 the flashcard grounding validator used
  `salient(back) & salient(atom)` with a `len(w)>3` token filter. A perfect card — atom "…HLA antigen
  **DR4**" → back "**HLA-DR4**." — scored ZERO overlap: `DR4`/`HLA`/`p53`/`70-80%` are all ≤3 chars or
  numeric and were filtered out, and the terse answer alone rarely echoes the statement's long words. 391
  of 9,511 cards (4.1%) were quarantined as "ungrounded" when only **1** actually was.
  **Fix/prevention:** keep alphanumeric-with-digit and numeric tokens (`DR4`, `p53`, `B12`, `70`) regardless
  of length; ground-check the WHOLE card (front+back+why), since a Q/A card's grounding is in the pairing,
  not the answer. **Always SAMPLE the rejects before trusting a reject rate** — a 4% "hallucination rate"
  was really a validator bug. (seen 1x)

- **2026-07 · Workflow `args` arrives `undefined` when passed alongside an inline `script`; a 4-core box
  caps workflow concurrency at 2.** At C11 the first flashcards Workflow died instantly —
  `undefined is not an object (evaluating 'ids.length')` — because `const {ids}=args` saw `args===undefined`
  (the recovery hint even re-passed args as a *string*). Separately, `min(16, cores-2)` on this 4-core box
  means only 2 subagents run at once per workflow, so an 80-chunk fan-out is hours, not the ~1h a big box
  would give.
  **Fix/prevention:** for inline-`script` workflows, **embed the data (ids/paths) as `const`s in the script
  body** instead of relying on `args` threading (or write the script to a file and launch by `scriptPath`);
  verify health early (check the workflow's `subagents/.../` transcript dir shows agents spawned). Run
  independent assets as **separate concurrent workflows** (each gets its own cap-2 pool) to raise effective
  throughput; keep total live agents modest (saw a transient agent reap at higher concurrency). (seen 1x)

- **2026-07 · "Has a key" ≠ "is playable" — quarantine on NO VALID GRADEABLE KEY, not on empty answer.**
  At C13 the quarantine skipped only curated MCQs with an *empty* answer (32), so two extended-matching
  questions whose answers were `G`/`I` (>5 options — no fit for a best-of-five `opt_a..e`/`correct_idx 0-4`
  schema) slipped through with `correct_idx = NULL` → ungradeable cards the app can't score. Only the
  post-build `correct_idx IS NULL` integrity check caught them.
  **Fix/prevention:** quarantine = the answer does not map to a valid A–E index (empty OR out-of-range);
  compute the key index first and exclude when it's `None` (34, not 32). After packaging, always assert the
  playable invariants on the built DB (`correct_idx` non-null and in range, 0 dangling `feed_items` refs,
  every playable row `importance ∈ 1..5` + populated signals) — a row can satisfy "has a value" and still be
  unplayable. (seen 1x)

- **2026-07 · AGP 9.0+ has BUILT-IN Kotlin — applying `org.jetbrains.kotlin.android` is a hard
  error.** Assumed the classic `plugins { alias(libs.plugins.kotlin.android) }` block was still
  needed; AGP 9 rejects it outright: "The 'org.jetbrains.kotlin.android' plugin is no longer
  required for Kotlin support since AGP 9.0". Built-in Kotlin replaces `kotlin-android` ONLY — the
  Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) is still applied separately, and
  `kotlin.compilerOptions.jvmTarget` now defaults from `android.compileOptions.targetCompatibility`
  (no explicit `kotlin { compilerOptions { jvmTarget } }` block needed). This came from an otherwise
  well-sourced docs-researcher report that predated/missed the AGP 9 change.
  **Fix/prevention:** on AGP 9+, drop `kotlin-android` from `plugins{}`, keep only the Compose
  compiler plugin; the build is the final authority over any research report on version-boundary
  facts. (seen 1x)

- **2026-07 · A Gradle plugin that touches the AGP Variant/Artifacts API cannot live in
  `buildSrc`.** With `compileOnly(agp)` in buildSrc the plugin compiled but died at apply time:
  `NoClassDefFoundError: com/android/build/api/variant/Variant` — buildSrc's jar sits on the root
  buildscript classpath, a PARENT classloader of the one the app's `plugins{}` block resolves AGP
  into, and a parent can't see a child's classes. Switching to `implementation(agp)` just changed
  the failure: "Error resolving plugin [id: 'com.android.application', version: '9.3.0'] ... already
  on the classpath with an unknown version".
  **Fix/prevention:** use an INCLUDED BUILD (`pluginManagement { includeBuild("build-logic") }`)
  with `compileOnly(agp)` for any plugin using the Variant API — never buildSrc. This is the
  pattern Google's own `android/gradle-recipes` uses. (seen 1x)

- **2026-07 · Never grep a manifest (or any XML/code) for forbidden content — parse it.** A
  grep-based check for forbidden permissions/components falsely reported INTERNET + a `<service>`
  in a build that had neither — it matched the warning comment inside our own
  AndroidManifest.xml that lists the forbidden items as text.
  **Fix/prevention:** any structural check over XML/code must parse the format (e.g.
  `javax.xml.parsers.DocumentBuilder`), never regex raw text. A guard that cries wolf gets
  switched off. (seen 1x)

- **2026-07 · `keytool` defaults to legacy JKS — always pass `-storetype PKCS12`.** Generating an
  Android signing keystore with `-storetype JKS` triggers "The JKS keystore uses a proprietary
  format. It is recommended to migrate to PKCS12"; caught before anything was signed and
  regenerated as PKCS12.
  **Fix/prevention:** always pass `-storetype PKCS12` when generating a keystore. Also update
  `.gitignore` in the same step — it covered `*.jks`/`*.keystore` but not `*.p12`, so a format
  change can silently escape ignore rules. (seen 1x)

- **2026-07 · Windows paths in a `.properties` file need forward slashes, not hand-escaped
  backslashes.** Writing `keystore.properties`'s `storeFile` via a sed one-liner that escaped
  backslashes failed with "unterminated `s' command" and silently produced a broken path line.
  **Fix/prevention:** use forward slashes in `.properties` paths (Java accepts `C:/...` on
  Windows); never hand-escape a Windows path through sed. (seen 1x)

- **2026-07 · Gradle 9 refuses to bootstrap a wrapper in a directory with no build.** `gradle
  wrapper` failed with "Run gradle init to create a new Gradle build in this directory" because no
  settings file existed yet.
  **Fix/prevention:** write a minimal `settings.gradle.kts` first, then run `gradle wrapper`.
  (seen 1x)
