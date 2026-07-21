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

**8. Android Studio ↔ AGP compatibility — read 2026-07-17.** Trigger: this laptop's installed
Studio (build `AI-253.29346.138.2531.14876573`, data dir `AndroidStudio2025.3.1`) refuses to sync
the project's pinned AGP 9.3.0 — "The project is using an incompatible version (AGP 9.3.0)...
Latest supported version is AGP 9.0.0." Verified against the raw HTML `<table>` on
developer.android.com (not the AI-summarized version of the same page — cross-checked both and the
summarizer's numbers matched, but the raw table is quoted below as ground truth).

*(1) Which Studio supports AGP 9.3.0 — the official table* (raw `<table>` from
`developer.android.com/studio/releases`, "Last updated 2026-07-16 UTC"):
```
Android Studio version              Required AGP version
Quail 2  | 2026.1.2                 7.1-9.3      <- supports AGP 9.3.0
Quail 1  | 2026.1.1                 7.1-9.2
Panda 4  | 2025.3.4                 7.1-9.2
Panda 3  | 2025.3.3                 7.0-9.1
Panda 2  | 2025.3.2                 7.0-9.1
Panda 1  | 2025.3.1                 7.0-9.0      <- installed on this laptop
Otter 3 Feature Drop | 2025.2.3     4.2-9.0
```
**Quail 2 (2026.1.2) is the only named Studio row that reaches AGP 9.3.** Channel — page prose:
"This page lists new features and improvements in the latest version in the **stable channel**,
Android Studio Quail 2" (i.e. not a preview build; corroborated independently — the main
`developer.android.com/studio` download page also defaults to "Download Android Studio Quail 2 |
2026.1.2" as of this read). Its closed-issues page (`/studio/releases/fixed-bugs/studio/2026.1.2`)
shows the full channel history in order — `Canary 1`…`Canary 7`, `RC 1`, `RC 2`, then the
suffix-less `2026.1.2` (stable) — confirming the normal canary→RC→stable graduation.
**Gap, not published on developer.android.com:** an exact release day, and the IntelliJ-platform
build ID (`AI-2NN.xxxxx`-style) for Quail 2. While a version is current (not yet archived) its page
carries no "(Month Year)" suffix and no build-ID column exists anywhere (download links instead show
the internal artifact version `2026.1.2.10`, e.g.
`.../ide-zips/2026.1.2.10/android-studio-quail2-windows.zip`). Best bound from what IS dated:
predecessor "Quail 1 (June 2026)" (its own archived page title) + this page's own "Last updated
2026-07-16 UTC" + the closed-issues page's "Last updated 2026-07-14 UTC" + AGP 9.3.0 itself going
stable "Last updated 2026-07-15 UTC" → Quail 2 stable shipped in the July 2026 window, tightly
bound to on/around 2026-07-14–16, but no verbatim "released on `<date>`" sentence exists for it.

*(2) Newest stable Studio as of 2026-07-17 = Quail 2 | 2026.1.2; its AGP ceiling is 9.3 — NOT
canary/beta-only.* This means **updating Android Studio is a fully docs-supported fix** for this
project's pinned AGP 9.3.0 (the current *stable* Studio already covers it — this is not a "wait for
canary" situation).

*(3) AGP ceiling for the INSTALLED Studio 2025.3.1 — confirmed exactly as the IDE reported.* Row
`Panda 1 | 2025.3.1 → 7.0-9.0` = ceiling AGP **9.0**, matching the IDE's own error text verbatim.
Release name + date (its own archived page title,
`/studio/releases/past-releases/as-panda-1-release-notes`): **"Android Studio Panda 1 (February
2026)."** **Fallback-pinning caution (synthesis, not a direct quote):** the table's "9.0" is
shorthand for the whole 9.0.x patch line (same convention as every other row, e.g. "8.9" =
8.9.x), but the IDE dialog named the ceiling **"AGP 9.0.0"** specifically — and 9.0.1 is what
`developer.android.com`'s AGP-9.0 page currently documents (see (4) — Google keeps one living page
per AGP *minor*, updated in place through patches). If downgrading, **pin exactly `9.0.0`**; treat
`9.0.1` as unverified-to-open-in-Panda-1 until tested.

*(4) Fallback data — AGP 9.0.0/9.0.1 compatibility* (raw `<table>`,
`developer.android.com/build/releases/agp-9-0-0-release-notes`, "Last updated 2026-07-15 UTC" — note
the page's own `<title>` is now **"Android Gradle plugin 9.0.1 (January 2026)"**, i.e. this URL is
the living page for the whole 9.0.x line, not a preserved 9.0.0-only snapshot):
```
Gradle          min 9.1.0   default 9.1.0
SDK Build Tools min 36.0.0  default 36.0.0
NDK             default 28.2.13676358
JDK             min 17      default 17
```
"The maximum API level that Android Gradle plugin 9.0 supports is API level 36.1." (AGP 9.3 raises
this to 37 — see point 1 above.)

**Built-in Kotlin — IDENTICAL in 9.0.0 and 9.3.0; introduced AT 9.0, unchanged since.** Verbatim
from the AGP-9.0 page: "Android Gradle plugin 9.0 introduces built-in Kotlin support and enables it
by default. That means you no longer have to apply the `org.jetbrains.kotlin.android` (or
`kotlin-android`) plugin in your build files to compile Kotlin source files." And: "Ensure your
project is compatible with built-in Kotlin: The `org.jetbrains.kotlin.android` plugin is not
compatible with the new DSL." Exact hard-error text (`developer.android.com/build/migrate-to-built-in-kotlin`
— the same error this ledger's AGP-9-built-in-Kotlin entry below already recorded independently, now
docs-confirmed verbatim):
```
Failed to apply plugin 'com.jetbrains.kotlin.android'
> The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```
**AGP 9.3.0's own release notes page has ZERO mentions of "Kotlin," "KGP," or "built-in Kotlin"**
(grepped the raw HTML — no matches) — nothing changed for this feature between 9.0 and 9.3. **One
real but non-behavioral difference found:** the bundled/minimum Kotlin Gradle Plugin (KGP) runtime
dependency crept upward — AGP 9.0's page states "a runtime dependency on Kotlin Gradle plugin (KGP)
2.2.10"; AGP 9.2.0's own Fixed-Issues table lists issue #483083119, **"Update Kotlin Gradle plugin
dependency to 2.3.10."** Not relevant to this project either way — Kotlin 2.4.10 (pinned) clears
both floors. **Conclusion: dropping `kotlin-android` from `plugins{}` is correct and required on AGP
9.0.0 exactly as much as on 9.3.0 — that part of the build does NOT need to change if AGP is
downgraded to 9.0.0 as a fallback.**

*(5) The AGP ceiling is an Android-Studio-IDE gate, not a Gradle/AGP gate — CLI builds are believed
unaffected (docs-supported inference, not a verbatim guarantee).* No single sentence says "CLI is
exempt," but: the compatibility page's own policy note names the actor explicitly — "If your
project's AGP version is incompatible, **Android Studio** will require you to update AGP" (subject =
the IDE). Corroborating: the canonical CLI-build doc, `developer.android.com/build/building-cmdline`
("Build your app from the command line," documents this project's own `gradlew assembleDebug`),
contains **zero** mentions of "Android Studio," version ceilings, or compatibility tables anywhere in
its raw HTML. **Practical read: `.\gradlew.bat assembleDebug` from a terminal should build fine
against AGP 9.3.0 even while Studio 2025.3.1 refuses to *sync the project in the IDE*** — Gradle has
no knowledge of "which Android Studio is installed" when invoked headlessly. **Not yet verified by
actually running the build** — flagging as a docs-grounded inference for the caller to confirm
empirically.

*(6) No documented override — updating Studio (or downgrading AGP) are the only supported paths.*
Checked every relevant official page (`/studio/releases`, `/build/releases/about-agp`,
`/build/agp-upgrade-assistant`, `/studio/known-issues`) — **none document a flag, VM option, or
Gradle property to make an older Studio accept a newer AGP.** The AGP Upgrade Assistant only steps a
project's AGP forward *within* what the current Studio already supports — not an escape hatch for an
already-too-new AGP. The compatibility page's "still open... using an older version of Android
Studio" note is the OPPOSITE case (an old AGP a new Studio dropped under the 3-year policy) and does
not apply here. Non-official corroboration only (not developer.android.com, context not source of
truth): Google's public issue tracker has an open **feature request**, "Implement flag to disable AGP
minimum version checking" (`issuetracker.google.com/issues/295373352`) — a request, not a shipped
feature, consistent with "no such flag exists."

Sources (raw HTML via `curl`, read 2026-07-17):
[developer.android.com/studio/releases](https://developer.android.com/studio/releases) (compat table
+ stable-channel statement, updated 2026-07-16);
[.../fixed-bugs/studio/2026.1.2](https://developer.android.com/studio/releases/fixed-bugs/studio/2026.1.2)
(channel history, updated 2026-07-14);
[.../past-releases/as-panda-1-release-notes](https://developer.android.com/studio/releases/past-releases/as-panda-1-release-notes)
("Panda 1 (February 2026)");
[.../past-releases/as-quail-1-release-notes](https://developer.android.com/studio/releases/past-releases/as-quail-1-release-notes)
("Quail 1 (June 2026)");
[developer.android.com/build/releases/agp-9-3-0-release-notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
(updated 2026-07-15);
[developer.android.com/build/releases/agp-9-0-0-release-notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
(updated 2026-07-15, currently shows 9.0.1);
[developer.android.com/build/releases/agp-9-2-0-release-notes](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
(KGP 2.3.10 fixed-issue);
[developer.android.com/build/migrate-to-built-in-kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
(verbatim hard-error text);
[developer.android.com/build/building-cmdline](https://developer.android.com/build/building-cmdline)
(CLI build doc, silent on Studio version);
[developer.android.com/build/releases/about-agp](https://developer.android.com/build/releases/about-agp);
[developer.android.com/build/agp-upgrade-assistant](https://developer.android.com/build/agp-upgrade-assistant);
[developer.android.com/studio/known-issues](https://developer.android.com/studio/known-issues).

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

### Android app A1 dependencies — Room+KSP, SQLite ATTACH, SAF, ZIP, Storage — read 2026-07-17

Researched for §8 A1 against the pinned toolchain (AGP 9.3.0 built-in Kotlin, Kotlin 2.4.10,
minSdk 26/compileSdk 36, fully-offline build-enforced). Five sub-topics below, each independently
sourced and dated.

**1. Room version + KSP under AGP 9's built-in Kotlin.**

Latest stable Room: **2.8.4** (released 2025-11-19). Requires Kotlin ≥2.0 (satisfied by 2.4.10):
> "Room now targets Kotlin language 2.0 and will require projects to also compile with Kotlin 2.0
> and equivalent or higher language version. Support for KSP2 is also added and is recommended
> when using Room with Kotlin 2.0 or higher." (Room 2.7.0 release notes)
Room's own minSdk floor is **API 23** (raised from 21 in 2.8.0-rc02) — well under our minSdk 26, no
conflict. `androidx.room:room-ktx` was merged into `androidx.room:room-runtime` as of 2.7.0-alpha01
("the artifact is now blank. Please remove it from your dependency list.") — **do not add
`room-ktx` as a separate dependency**, `room-runtime` alone brings the coroutine/Flow APIs.
```kotlin
// build.gradle.kts
dependencies {
    val room_version = "2.8.4"
    implementation("androidx.room:room-runtime:$room_version")
    ksp("androidx.room:room-compiler:$room_version")
}
```
(exact block from `/training/data-storage/room#declaring_dependencies`; the room release-notes page
separately documents an *optional* Room **Gradle plugin** — `id("androidx.room") version "2.8.4"`,
used only for schema-export tooling — whose own minimum AGP requirement was raised "from 8.1 to
8.4" in 2.8.0-rc02. That plugin is not required for basic Room usage and its AGP floor (8.4) is far
below our pinned 9.3.0, so no conflict either way.)

**KAPT is a hard error under built-in Kotlin — KSP is not optional, it's the only path:**
> "The `org.jetbrains.kotlin.kapt` (or `kotlin-kapt`) plugin is incompatible with built-in Kotlin.
> If you use `kapt`, we recommend that you migrate your project to KSP."
(`developer.android.com/build/migrate-to-built-in-kotlin`)

**KSP1 is explicitly dead for our toolchain — KSP2 (the default since "beginning of 2025") is
mandatory, not just recommended:**
> "KSP1 will not be compatible with: Kotlin version `2.3.0` and higher. Android Gradle plugin (AGP)
> version `9.0` and higher. Projects using KSP1 may not behave correctly (or fail the build) if you
> upgrade to these or any subsequent versions of AGP or Kotlin."
(`github.com/google/ksp` README, "Deprecation notice for KSP1"). Our stack is Kotlin 2.4.10 + AGP
9.3.0 — **both** of KSP1's stated cutoffs. KSP2 has been the default since KSP 2.0.0 (`ksp {
useKsp2 = false }` is the opt-out, not opt-in), so a plain `id("com.google.devtools.ksp")`
application already gets KSP2 with no extra flag.

**KSP version to pair with Kotlin 2.4.10 — direct answer, from kotlinlang.org's own live
quickstart example** (fetched raw HTML, not a summarizer — verbatim `<pre>` block):
```kotlin
plugins {
    kotlin("jvm") version "2.4.10"
    id("com.google.devtools.ksp") version "2.3.10"
}
```
i.e. **KSP 2.3.10** is the version kotlinlang.org's own docs pair with Kotlin 2.4.10 as of this
read. Corroborating: KSP switched its version-numbering scheme at 2.3.0 — every release from
`2.2.21-2.0.5` onward through the `2.3.x` line is a bare version (`2.3.0`...`2.3.10`, confirmed via
`repo1.maven.org` maven-metadata.xml for `com.google.devtools.ksp.com.google.devtools.ksp.gradle.plugin`),
no longer a `<kotlin>-<ksp>` compound like the pre-2.3 releases — KSP no longer claims to track one
exact Kotlin patch per release. KSP 2.3.10's own GitHub release notes (fetched via GitHub API,
`google/ksp` tag `2.3.10`, published 2026-07-09) list two directly relevant fixes:
- `"Sanitize ':' in internal-name module suffix so KSP works with Kotlin 2.4.0 default module
  names (#2964)"` — explicit compatibility work for Kotlin 2.4.0.
- `"Fix R-class resolution in KSP when AGP 9 built-in Kotlin is enabled (#2857)"` — **direct,
  explicit confirmation that KSP has been fixed to work under AGP 9's built-in Kotlin**, which is
  as close to an official "yes, KSP works under built-in Kotlin" statement as exists anywhere.
**Gap — not stated as a table anywhere official:** no compatibility MATRIX (as opposed to one
worked example) was found; `kotlinlang.org/docs/ksp-and-kotlin-versions.html` (the URL a
prior-generation compat table lived at) now 404s, consistent with the versioning-scheme change
above. Treat KSP 2.3.10 (the version in kotlinlang.org's live Kotlin-2.4.10 example) as the
correct pin; if a future KSP patch release (`2.3.11`+) exists by build time, prefer whatever
kotlinlang.org's quickstart example pairs with Kotlin 2.4.10 at that time over this snapshot.

**How KSP is applied — completely normal, built-in Kotlin does NOT change plugin wiring for
third-party Gradle plugins.** Checked `/build/migrate-to-built-in-kotlin` explicitly for any
special KSP wiring instructions: none exist — the page's only third-party-plugin guidance is the
kapt-incompatibility note above. KSP is applied exactly as any other Gradle plugin:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    // no kotlin-android — built-in Kotlin
    alias(libs.plugins.compose.compiler)
    id("com.google.devtools.ksp") version "2.3.10"
}
dependencies {
    ksp("androidx.room:room-compiler:2.8.4")   // the `ksp(...)` configuration, not annotationProcessor
}
```
Sources: [developer.android.com/jetpack/androidx/releases/room](https://developer.android.com/jetpack/androidx/releases/room)
(raw HTML, "Version 2.7"/"Version 2.8" sections + Declaring dependencies, read 2026-07-17);
[developer.android.com/training/data-storage/room](https://developer.android.com/training/data-storage/room);
[developer.android.com/build/migrate-to-built-in-kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin);
[developer.android.com/build/migrate-to-ksp](https://developer.android.com/build/migrate-to-ksp);
[github.com/google/ksp README](https://github.com/google/ksp/blob/main/README.md) (raw, read
2026-07-17); [github.com/google/ksp/releases](https://github.com/google/ksp/releases) +
GitHub API `repos/google/ksp/releases/tags/2.3.10` (release body, read 2026-07-17);
[repo1.maven.org/.../ksp.gradle.plugin/maven-metadata.xml](https://repo1.maven.org/maven2/com/google/devtools/ksp/com.google.devtools.ksp.gradle.plugin/maven-metadata.xml);
[kotlinlang.org/docs/ksp-quickstart.html](https://kotlinlang.org/docs/ksp-quickstart.html) (raw
HTML `<pre>` block, read 2026-07-17) — `kotlinlang.org/docs/ksp-and-kotlin-versions.html` checked
and confirmed 404 as of this read.

---

**2. SQLite ATTACH across a read-only content pack + a Room-managed progress DB — THE risky
unknown, and it resolves to a hard "don't rely on Room's pooled connection for this."**

**Ground truth, SQLite's own docs (`sqlite.org/lang_attach.html`, read 2026-07-17):**
> "The ATTACH DATABASE statement adds another database file to the current database connection."
ATTACH is scoped to **one connection**, full stop — this is stated as the very first sentence of
the spec page, not an inference. A `DETACH DATABASE` on that same connection is the only way to
remove it; nothing in the SQLite docs suggests ATTACH state propagates to any other connection,
pooled or not.
> "There is a limit, set using `sqlite3_limit()` and `SQLITE_LIMIT_ATTACHED`, to the number of
> databases that can be simultaneously attached to a single database connection."
Default limit (`sqlite.org/limits.html`): **`SQLITE_MAX_ATTACHED` = 10** by default, **hard ceiling
125** (`sqlite3_limit()` can only lower it, never raise past 125). One ATTACH (the content pack)
is nowhere near this limit.
> "Transactions involving multiple attached databases are atomic, assuming that the main database
> is not `:memory:` and the journal_mode is not WAL. If the main database is `:memory:` or if the
> journal_mode is WAL, then transactions continue to be atomic **within each individual database
> file**. But if the host computer crashes in the middle of a COMMIT where two or more database
> files are updated, some of those files might get the changes where others might not."
So ATTACH + WAL is not *forbidden* at the SQLite engine level, but a multi-DB transaction loses
cross-file atomicity under WAL — acceptable here since the content pack is read-only (nothing to
roll back) and only `progress.db` is ever written.

**Android framework-level finding — the crux, and it is explicit, not inferred.** The stock
`android.database.sqlite.SQLiteDatabase.enableWriteAheadLogging()` javadoc (fetched raw HTML,
`developer.android.com/reference/android/database/sqlite/SQLiteDatabase`, read 2026-07-17) states
outright:
> "Parallel execution of queries is performed by opening multiple connections to the database and
> using a different database connection for each query... **If the database has any attached
> databases, then execution of queries in parallel is NOT possible.** Likewise, write-ahead logging
> is not supported for read-only databases or memory databases. In such cases,
> `enableWriteAheadLogging()` returns false."
This is a documented, verbatim statement (not our own inference) that **WAL's multi-connection
parallel-read pool and ATTACH DATABASE are mutually exclusive** at the framework
(`android.database.sqlite`) level: a `SQLiteDatabase` with anything attached cannot run queries in
parallel across pooled connections — meaning if you `ATTACH` on one connection, that connection
stops being one of many interchangeable pool members and effectively serializes queries that need
the attached schema. Combined with SQLite's own "ATTACH is per-connection" fact above, the
practical implication for a Room database (which pools connections under WAL by default) is: **an
ATTACH issued via one pooled connection is invisible to queries that land on a different pooled
connection, and forcing everything through one connection to keep the ATTACH visible defeats
Room/WAL's whole reason for existing.**

`OPEN_READONLY` — confirmed exact constant + purpose (raw reference HTML):
> "Open flag: Flag for `openDatabase(File, OpenParams)` to open the database for reading only.
> This is the only reliable way to open a database if the disk may be full."
All three `SQLiteDatabase.openDatabase(...)` overloads take a `String path` or `File path` — **no
overload accepts a `Uri`** (confirmed from the reference page's own method-signature listing, an
inference from the API surface itself rather than a stated "no" sentence — see also point 3 below
re: content:// + SQLite).

**Room + this driver-level fact — GAP, not covered by any official Room doc found.** Checked
`developer.android.com/training/data-storage/room`, the Room release notes
(`/jetpack/androidx/releases/room`), and `developer.android.com/kotlin/multiplatform/sqlite`
explicitly for any mention of "ATTACH," multi-database queries, or interaction between Room's
connection pool and attached databases: **none exists in any of these pages.** Room 2.7+ is built
on a pluggable `androidx.sqlite.SQLiteDriver` (`AndroidSQLiteDriver` — wraps the framework
`android.database.sqlite` API above — vs. the "recommended" `BundledSQLiteDriver`, a from-source
cross-platform SQLite build in `androidx.sqlite:sqlite-bundled`). Docs state `BundledSQLiteDriver`
is recommended ("It includes the SQLite library compiled from source, offering the most up-to-date
version and consistency across all the supported KMP platforms") but **do not document its
connection-pooling model, its WAL defaults, or whether it shares the same "ATTACH disables
parallel queries" behavior as the framework driver** — that behavior was only confirmed for
`android.database.sqlite.SQLiteDatabase` above, not for `BundledSQLiteDriver`. Also unconfirmed:
what driver a plain `RoomDatabase.Builder` uses if `setDriver()` is never called (no official
sentence found stating the no-`setDriver()` default).

**Practical read (synthesis, not a doc quote) for A1's design:** do not attempt to ATTACH the
content pack onto Room's own pooled connection and JOIN through Room's DAO layer — the pool can
silently route the query to a connection that never saw the ATTACH, and even where it works,
having anything attached forces that connection out of parallel rotation (documented above). The
two safer, docs-grounded patterns are (a) open a **separate, dedicated, single, non-pooled**
`SQLiteDatabase` connection specifically for cross-DB reads (`openDatabase(path, ..., flags)` with
no `ENABLE_WRITE_AHEAD_LOGGING` flag, so it's a single ordinary connection you fully control, issue
`ATTACH DATABASE '<contentPackPath>' AS content` on that one connection via `execSQL()`, then run
`rawQuery()`s that reference `content.<table>` on that same connection only — never Room's pool),
or (b) avoid ATTACH+JOIN entirely and instead query the two DBs independently (Room for
`progress.db`, a second read-only `SQLiteDatabase.openDatabase(..., OPEN_READONLY)` for the content
pack) and join the two result sets in Kotlin. **This is a docs gap, flagged rather than guessed** —
no official Room source states which pattern it recommends; the recommendation above follows
directly from the two verbatim facts quoted (ATTACH is per-connection; ATTACH disables parallel
pooled queries), not from a Room-authored best-practice doc.

Sources: [sqlite.org/lang_attach.html](https://sqlite.org/lang_attach.html) (read 2026-07-17);
[sqlite.org/limits.html](https://sqlite.org/limits.html);
[sqlite.org/c3ref/c_limit_attached.html](https://sqlite.org/c3ref/c_limit_attached.html);
[developer.android.com/reference/android/database/sqlite/SQLiteDatabase](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase)
(raw HTML, `enableWriteAheadLogging()` + `OPEN_READONLY` + `openDatabase` overload list, read
2026-07-17); [developer.android.com/training/data-storage/room](https://developer.android.com/training/data-storage/room);
[developer.android.com/kotlin/multiplatform/sqlite](https://developer.android.com/kotlin/multiplatform/sqlite)
(driver table + "recommended: BundledSQLiteDriver" quote); Room release notes (as cited in §1)
checked and confirmed silent on ATTACH/multi-DB.

---

**3. Storage Access Framework — pick a directory, persist access, list ~1,121 files fast, and
confirm SQLite cannot open a `content://` URI directly.**

`ACTION_OPEN_DOCUMENT_TREE` — exact intent pattern (`/training/data-storage/shared/documents-files`,
raw HTML, read 2026-07-17):
```kotlin
val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
    putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri)   // optional
}
startActivityForResult(intent, your-request-code)
```
> "When using `ACTION_OPEN_DOCUMENT_TREE`, your app gains access only to the files in the directory
> that the user selects."
Access restrictions on API 30+ (verbatim): cannot request the internal-storage root, a "reliable"
SD-card root, or the `Download` directory as a *tree*; cannot pick individual files from
`Android/data/` or `Android/obb/` subtrees.

**Performance caution — official, direct:**
> "Caution: If you iterate through a large number of files within the directory that's accessed
> using `ACTION_OPEN_DOCUMENT_TREE`, your app's performance might be reduced."
And from the `androidx.documentfile.provider.DocumentFile` class-level javadoc itself (fetched raw,
`developer.android.com/reference/androidx/documentfile/provider/DocumentFile`, read 2026-07-17) —
this is the officially documented reason and the officially documented faster alternative, in the
API's own words:
> "This is a utility class designed to emulate the traditional `File` interface. It offers a
> simplified view of a tree of documents, but **it has substantial overhead**. For optimal
> performance and a richer feature set, use the `android.provider.DocumentsContract` methods and
> constants directly."
So for ~1,121 files, prefer `DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
parentDocumentId)` + `ContentResolver.query(childrenUri, projection, ...)` (a normal cursor query —
takes a projection array, so request only the columns needed, e.g. `Document.COLUMN_DOCUMENT_ID` +
`COLUMN_DISPLAY_NAME` + `COLUMN_SIZE`) over `DocumentFile.listFiles()`/`.findFile()`, which the
library's own docs say to avoid for anything beyond casual use. Exact signature (raw reference
HTML):
```java
public static Uri buildChildDocumentsUriUsingTree(Uri treeUri, String parentDocumentId)
```
> "Build URI representing the children of the target directory in a document provider... This is
> typically used to access documents under a user-selected directory tree, since it doesn't
> require the user to separately confirm each new document access."
**Not officially confirmed (flagging, not asserting):** a non-official but directly relevant
technical note (CommonsWare blog, not developer.android.com — cited for context only, not as
authority) explains that even `DocumentsContract` queries cost **two IPC hops** (app → system
`ContentProvider` → the actual `DocumentsProvider`), so "faster than `DocumentFile`" does not mean
free — no official page quantifies the actual throughput for ~1,121 files, so budget/measure this
empirically during A1 rather than assuming a specific number.

`takePersistableUriPermission` — exact snippet + limits (raw HTML):
```kotlin
val contentResolver = applicationContext.contentResolver
val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
contentResolver.takePersistableUriPermission(uri, takeFlags)
```
> "Caution: Even after calling `takePersistableUriPermission()`, your app doesn't retain access to
> the URI if the associated document is moved or deleted. In those cases, you need to ask
> permission again to regain access to the URI."
Without this call, the docs state the grant "lasts until the user's device restarts" — persisting
it is what survives reboots, which is required since this app must re-copy/re-verify the pack
directory across app restarts without re-prompting the picker every time.

**Can SQLite open a `content://` URI directly — confirmed NO, by omission from the API surface,
not a verbatim "no" sentence.** Every `SQLiteDatabase.openDatabase(...)` overload (checked all
three in the raw reference HTML — §2 above) takes `String path` or `File path`; none accepts a
`Uri`. No official page states "SQLite cannot open content:// URIs" in so many words, but the
absence of any `Uri`-accepting `openDatabase` overload, combined with SQLite itself being a C
library that opens paths via `sqlite3_open()`/`sqlite3_open_v2()` (not a URI-content-provider
abstraction — confirmed on `sqlite.org/lang_attach.html`'s own reference to
`sqlite3_open()`/`sqlite3_open_v2()` filename semantics), is why the compiled 135MB content pack
`.db` (and the ~1,121 supporting files) **must be copied into `filesDir` (or another
directly-openable path) before SQLite can touch it** — opening it in place from inside the
SAF-picked tree is not possible through any documented SQLite/Android API.

Sources: [developer.android.com/training/data-storage/shared/documents-files](https://developer.android.com/training/data-storage/shared/documents-files)
(raw HTML, read 2026-07-17); [developer.android.com/reference/androidx/documentfile/provider/DocumentFile](https://developer.android.com/reference/androidx/documentfile/provider/DocumentFile)
(raw HTML, class javadoc + `listFiles()`, read 2026-07-17); [developer.android.com/reference/android/provider/DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract)
(raw HTML, `buildChildDocumentsUriUsingTree`, read 2026-07-17); [developer.android.com/reference/android/database/sqlite/SQLiteDatabase](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase)
(as cited in §2); [sqlite.org/lang_attach.html](https://sqlite.org/lang_attach.html).

---

**4. ZIP export/import (§11e) — java.util.zip + zip-slip. Google has a dedicated official page for
exactly this, with copy-paste-correct mitigation code.**

`developer.android.com/privacy-and-security/risks/zip-path-traversal` (raw HTML, read 2026-07-17),
verbatim:
> "The Zip Path Traversal vulnerability, also known as ZipSlip... The underlying reason for this
> problem is that inside ZIP archives, each packed file is stored with a fully qualified name,
> which allows special characters such as slashes and dots. **The default library from the
> `java.util.zip` package doesn't check the names of the archive entries for directory traversal
> characters (`../`)**, so special care must be taken when concatenating the name extracted from
> the archive with the targeted directory path."
> "Impact: The Zip Path Traversal vulnerability can be used to achieve arbitrary file overwrite...
> in many cases this vulnerability can lead to major security issues such as code execution."

**Official mitigation — exact code, both required parts (path-containment check + empty-destination
check):**
```kotlin
companion object {
    @Throws(IOException::class)
    fun newFile(targetPath: File, zipEntry: ZipEntry): File {
        val name: String = zipEntry.name
        val f = File(targetPath, name)
        val canonicalPath = f.canonicalPath
        if (!canonicalPath.startsWith(targetPath.canonicalPath + File.separator)) {
            throw ZipException("Illegal name: $name")
        }
        return f
    }
}
```
> "To mitigate this issue, before extracting each entry, you should always verify that the target
> path is a child of the destination directory. The code below assumes that the destination
> directory is safe — writable by your app only and not under attacker control — otherwise your
> app could be prone to other vulnerabilities such as symlink attacks."

Second required check (verbatim), with its own code sample:
> "To avoid accidentally overwriting existing files, you should also make sure that the destination
> directory is empty before starting the extraction process. Otherwise you risk potential app
> crashes, or in extreme cases, an application compromise."
```kotlin
fun unzip(inputStream: InputStream?, destinationDir: File) {
    if (!destinationDir.isDirectory) throw IOException("Destination is not a directory.")
    val files = destinationDir.list()
    if (files != null && files.isNotEmpty()) throw IOException("Destination directory is not empty.")
    ZipInputStream(inputStream).use { zipInputStream ->
        var zipEntry: ZipEntry
        while (zipInputStream.nextEntry.also { zipEntry = it } != null) {
            val targetFile = File(destinationDir, zipEntry.name)   // pass through newFile() above
            // ...
        }
    }
}
```
**Applied to §11e specifically:** both checks matter for import — `newFile()`'s canonical-path
check on every entry (`progress.db`, every `note_images/*` file) before extraction, AND the
empty-destination-directory check, since import targets a location this app fully controls
(`filesDir`) rather than merging into a live directory. For export (writing our own zip via
`ZipOutputStream` to a SAF `ACTION_CREATE_DOCUMENT` target), zip-slip does not apply — it is only a
risk when *reading* entry names from an externally-supplied (untrusted) archive.

Source: [developer.android.com/privacy-and-security/risks/zip-path-traversal](https://developer.android.com/privacy-and-security/risks/zip-path-traversal)
(raw HTML, "last updated September 24, 2024" per the page's own footer at last check, content
re-verified live 2026-07-17).

---

**5. `getFilesDir()` + free-space check before a ~370MB copy — `getAllocatableBytes`/`allocateBytes`
(API 26+) is Google's own recommended API over `File.getUsableSpace()`, and API 26 exactly matches
our minSdk floor.**

`getFilesDir()` semantics (`/training/data-storage/app-specific`, raw HTML, read 2026-07-17):
> "Other apps cannot access files stored within internal storage... Keep in mind, however, that
> these directories tend to be small. Before writing app-specific files to internal storage, your
> app should query the free space on the device."
> "When the user uninstalls your app, the files saved in app-specific storage are removed."
```kotlin
val file = File(context.filesDir, filename)
```

**Free-space check — exact recommended API, exact code, exact API-level floor (confirmed from the
raw `StorageManager` reference page — every method below is "Added in API level 26"):**
```kotlin
// App needs N bytes within internal storage.
val storageManager = applicationContext.getSystemService<StorageManager>()!!
val appSpecificInternalDirUuid: UUID = storageManager.getUuidForPath(filesDir)
val availableBytes: Long = storageManager.getAllocatableBytes(appSpecificInternalDirUuid)
if (availableBytes >= NUM_BYTES_NEEDED_FOR_MY_APP) {
    storageManager.allocateBytes(appSpecificInternalDirUuid, NUM_BYTES_NEEDED_FOR_MY_APP)
} else {
    // launch ACTION_MANAGE_STORAGE with EXTRA_UUID + EXTRA_REQUESTED_BYTES to involve the user
}
```
`getAllocatableBytes(UUID)` javadoc, verbatim, and this is the direct head-to-head comparison
against `File.getUsableSpace()` the task asked for:
> "Return the maximum number of new bytes that your app can allocate for itself on the given
> storage volume. **This value is typically larger than `File.getUsableSpace()`, since the system
> may be willing to delete cached files to satisfy an allocation request.** You can then allocate
> space for yourself using `allocateBytes(UUID,long)` or `allocateBytes(FileDescriptor,long)`."
> "This method is best used as a pre-flight check... Attempts to allocate disk space beyond the
> returned value will fail."
> "This method may take several seconds to complete, so it should only be called from a worker
> thread."
`allocateBytes(...)` (two overloads — `FileDescriptor` or `UUID` form) reserves the space so a
concurrent allocator can't steal it out from under you between the check and the write; both are
API 26 same as `getAllocatableBytes`.

**Neither is strictly required — official escape hatch documented too:**
> "Note: You aren't required to check the amount of available space before you save your file. You
> can instead try writing the file right away, then catch an `IOException` if one occurs."

**Recommendation for the ~370MB copy (content pack `.db` + supporting files from the SAF tree into
`filesDir`):** use `getAllocatableBytes()`/`allocateBytes()` (both exactly at our minSdk-26 floor,
no fallback needed for older devices) rather than `File.getUsableSpace()` — Google's own javadoc
states the StorageManager API returns a **larger, more accurate figure** because it accounts for
reclaimable cache space the raw filesystem free-space number doesn't see, and pairs the check with
an actual reservation (`allocateBytes`) that `File.getUsableSpace()` has no equivalent for.

Sources: [developer.android.com/training/data-storage/app-specific](https://developer.android.com/training/data-storage/app-specific)
(raw HTML, read 2026-07-17); [developer.android.com/reference/android/os/storage/StorageManager](https://developer.android.com/reference/android/os/storage/StorageManager)
(raw HTML, `getAllocatableBytes(UUID)` + `allocateBytes` overloads + "Added in API level 26", read
2026-07-17).

**6. Getting a real fixture file onto the device for an instrumented test WITHOUT it being wiped
or unreadable — verified empirically at A1 (2026-07-17), not from docs.**

Two separate, real Android behaviors defeat the naive `adb push` + `./gradlew
connectedDebugAndroidTest` approach:

- **(a) `adb push` into app-scoped external storage writes as the `shell` UID, which the app
  cannot read on Android 11+.** Pushing to
  `/sdcard/Android/data/<pkg>/files/...` leaves the file `-rw-rw-rw- shell ext_data_rw`; the
  app's own process gets `Permission denied` reading that same path. Verified:
  `adb shell run-as <pkg> ls <path>` on an adb-pushed file → "Permission denied". A test that
  lists the directory and finds nothing will silently assume-out rather than fail loudly.
- **(b) `./gradlew connectedDebugAndroidTest` uninstalls the APK before AND after the run, and
  uninstall wipes app-scoped external storage (`/sdcard/Android/data/<pkg>/`).** Even a
  correctly-owned fixture placed there is gone before the test executes. Verified: after a
  `connectedDebugAndroidTest` run, `run-as <pkg>` reports "unknown package" and the pushed
  directory is gone.

**Working procedure (verified end-to-end, fixture readable and test actually executes):**
```bash
./gradlew installDebug installDebugAndroidTest        # install BOTH; do NOT use connectedDebugAndroidTest
adb push <file> /data/local/tmp/pack.db                # /data/local/tmp is shell-writable
adb shell chmod 644 /data/local/tmp/pack.db             # o+r so the app UID can open it
adb shell "run-as <pkg> mkdir -p files/realpack"
adb shell "run-as <pkg> cp /data/local/tmp/pack.db files/realpack/pack_MRCP_PART1.db"
adb shell am instrument -w -r -e class <TestClass> <pkg>.test/androidx.test.runner.AndroidJUnitRunner
```
After the `run-as cp`, the file is owned by the app's own UID (e.g. `u0_a233`) and readable by
the app process. Driving `am instrument` directly (instead of the Gradle
`connectedDebugAndroidTest` task) avoids the install/uninstall cycle that wipes the fixture.
Always use `am instrument -w -r` (raw) and read `INSTRUMENTATION_STATUS` output, not just the
final `OK (N tests)` line — see the Mistakes ledger entry on skipped tests below for why the bare
summary line lies about skips.

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
  switched off.
  **2026-07-17 recurrence (stage A1):** repeated in a different language on the same file. After
  adding Room and stripping its injected `MultiInstanceInvalidationService`, I hand-verified the
  merged manifest with Python substring checks (`'permission.INTERNET' in src`,
  `src.count('<service')`) instead of trusting `verifyOfflineGuarantees` — it "found" INTERNET +
  a `<service>` + a `<receiver>` that didn't exist, matching the same DO-NOT-ADD warning comment
  again. Re-parsing with `ElementTree` (comments aren't elements) confirmed the guard was right
  and my check was wrong. It recurred because the original entry read as advice about how the
  *task's own code* should check the manifest, not about any ad-hoc/manual verification done
  afterwards — "grep" was treated narrowly (the shell tool) rather than as "any substring match
  over manifest text" (grep, rg, Python `in`/`.count()`, Select-String, all equally guilty).
  **Fix/prevention:** never substring-match manifest XML in ANY language or tool — parse it
  (`ElementTree`/`DocumentBuilder`/etc.), full stop, whether the check is committed task code or
  a one-off verification. Corollary: when a hand-rolled check contradicts the build-enforced
  guard, suspect the hand-rolled check first — the guard parses. Promoted to a one-line rule in
  `CLAUDE.md` (project) per the (seen 2x) rule. (seen 2x)

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

- **2026-07 · When pinning/bumping the Android toolchain, check Android Studio's AGP ceiling too
  — not just Gradle/JDK/Build-Tools.** A0 pinned the newest AGP (9.3.0), verified against
  Gradle/JDK/SDK-Build-Tools, but never checked AGP ↔ Studio; the installed IDE (Panda 1 |
  2025.3.1, ceiling AGP 9.0) refused to sync — "Latest supported version is AGP 9.0.0" — blocking
  code completion/previews/Run for an app this laptop builds via Studio (CLAUDE.md `build_env`).
  Only Studio Quail 2 (2026.1.2, current stable) reaches AGP 9.3 — "newest AGP" outran the
  installed IDE by 2 feature drops. The CLI build was NEVER broken (verified: `gradlew
  verifyOfflineGuarantees`/`assembleDebug` both succeeded under AGP 9.3.0 throughout) — a Studio
  AGP-ceiling dialog means "update Studio or downgrade AGP," not "the build is broken."
  **Fix/prevention:** before pinning, read the INSTALLED Studio's build off this machine
  (`C:\Program Files\Android\Android Studio\build.txt`) and look its row up on
  developer.android.com/studio/releases — the IDE is as binding a constraint as Gradle/JDK for an
  IDE-developed app, and newest-available ≠ newest-installed. No override flag exists (Google
  issuetracker #295373352, open/unshipped) — only fix is update Studio or downgrade AGP. On any
  IDE version complaint, confirm with `gradlew` from a terminal FIRST, before touching a pin. Full
  compat table: playbook "Android build toolchain" note, §8. (seen 1x)

- **2026-07 · Transcribe a docs-researcher's exact version string VERBATIM — never reformat it to
  match a remembered pattern.** `docs-researcher` correctly reported "KSP 2.3.10" for Kotlin
  2.4.10. Writing it into `libs.versions.toml`, I "knew" KSP versions are `<kotlin>-<ksp>` (true
  for KSP1, e.g. `2.0.21-1.0.28`) and silently rewrote it to `2.4.10-2.3.10` — a version that
  doesn't exist. Build failed: "Plugin ... version '2.4.10-2.3.10' was not found." Root cause: KSP
  changed its scheme at KSP2 (>=2.3.0) to plain standalone semver, decoupled from Kotlin — the
  research was correct and I overrode it with a stale mental model, defeating the entire point of
  having invoked the researcher.
  **Fix/prevention:** when a researcher/doc gives an exact version string, write it byte-for-byte;
  if it "looks like the wrong format," that's a signal the scheme changed, not a typo to correct.
  Before pinning any dependency version, confirm it exists in the repo's `maven-metadata.xml`
  (e.g. `curl .../maven-metadata.xml | grep '<version>2.3.10'`). (seen 1x)

- **2026-07 · A SKIPPED instrumented test is not a passing test, and both the JUnit-XML reader
  and `am instrument`'s summary line actively hide that.** At A1, `RealPackTest` assumed-out via
  `assumeTrue()` (the fixture wasn't readable — see the Android app A1 dependencies API-note for
  why) on all 3 real-pack tests. My own reporting script classified any `<testcase>` with no
  `<failure>`/`<error>` child as `[PASS]`, ignoring the `<skipped>` child and the suite's
  `skipped="3"` attribute; separately, `adb shell am instrument` printed `OK (3 tests)` for the
  same run. Both readings said "passed" when nothing had run — I nearly reported "the real pack
  is verified."
  **Fix/prevention:** any test-result reader must check for `<skipped>`/`skipped=` and report
  "ran" vs "skipped" separately — no `<failure>` is not the same as PASS. Never trust `am
  instrument`'s bare `OK (N tests)`; use `am instrument -r` (raw) and grep for
  `AssumptionViolatedException`/`INSTRUMENTATION_STATUS: stack=` before believing a suite
  verified anything. Same family as this ledger's `--rerun-tasks` rule: the question is never
  "did it say success," it is "did the assertion actually execute." A test that CAN skip must
  have its skip surfaced as loudly as a failure in whatever reads the results. (seen 1x)

- **2026-07 · A test suite that only exercises states it created itself will never see the state
  a fresh user starts in — test the ZERO state explicitly.** At A1, `FeedReader` ATTACHes
  `progress_<EXAM>.db` beside the pack DB and needs both files to exist, but Room creates the
  progress file LAZILY (first query/write, not `databaseBuilder().build()`). On a freshly
  imported pack with nothing studied yet, the file didn't exist, `openDatabase()` failed, and the
  feed silently reported "0 items" over a real 32,957-item pack — invisible to 44 green tests
  because every one of them called `upsertCard(...)` in setup, which created the progress file as
  a side effect and silently satisfied the precondition production code was missing. Only running
  the real APK found it.
  **Fix/prevention:** `ProgressDatabase.ensureExists(context, examCode)` forces the file into
  existence (open the RoomDatabase, touch `openHelper.writableDatabase`, close explicitly — Room
  is not `Closeable`, `use {}` won't compile) before `FeedReader` opens. For any feature, write a
  dedicated zero-state test (fresh install / nothing imported / nothing studied) that asserts the
  precondition does NOT hold before acting (e.g. `assertFalse(file.isFile)`), so it can't quietly
  start passing by inheriting setup from elsewhere. Lazy-initialised files (Room DBs, caches) are
  a trap for any code that reaches them by path (ATTACH, export, size check) — never assume they
  exist before first use. (seen 1x)

- **2026-07 · Never paste a real pack fragment into a tracked doc — quote the FORM, never the
  bytes.** At A2 planning, to justify "in-house markdown renderer, not a library" (pack markdown
  has medical notation a renderer would corrupt), I copied verbatim MCQ stem/explanation
  fragments from `mcqs.stem_md`/`explanation_md` straight into BUILD_PROMPT.md, CLAUDE.md,
  JOURNAL.md, and the A2 plan — tracked files, unlike the gitignored compiler dirs the whole
  design otherwise keeps copyrighted text out of. One fragment even carried a specific
  patient-vignette shape, squarely "question stem." `secret-scanner` caught it pre-push; I hadn't
  caught it while writing.
  **Fix/prevention:** the compiler's own bright line (counts/IDs/decisions only, never book
  content) applies to ALL tracked text — commit messages, JOURNAL.md, plans, PLAYBOOK, code
  comments, test fixtures. When an argument needs an example, invent a synthetic FORM (e.g.
  `<count> * 10^9/L`, `> <N>: <interpretation>`) that cannot be a substring of any real pack row —
  never a real excerpt, no matter how short or "obviously fair use." (seen 1x)

- **2026-07 · `git push` and `secret-scanner` are one inseparable step — no exception for "already
  scanned this session."** At A2, on a "push and continue" request, I ran `git push origin dev`
  straight after committing without re-running `secret-scanner`, because I'd run it before every
  other push that session and was in flow. This particular commit was higher-risk than most: minutes
  earlier a real copyrighted `text_blob` passage (HLA-DR4) had been on screen, and JOURNAL.md quoted
  a string from it (`DRB1*04:01`). A retroactive scan came back clean (standard WHO/IMGT-HLA
  nomenclature, not authored prose) — no harm, but that was luck, not process, and it repeated the
  same mistake class already caught pre-push earlier that session (the entry above).
  **Fix/prevention:** never type `git push` without the immediately-preceding `secret-scanner` run
  on THAT push's commits — each push carries its own new diff and its own new risk; "scanned
  earlier," "just docs," and "in flow" are not exceptions. (seen 1x)

- **2026-07 · An AVD's data directory is NOT `~/.android/avd/<avd-name>.avd` by name — read the
  `.ini` for the real path, and never force-kill the emulator.** At A7, `Medium_Phone_API_36.1`
  (per `emulator -list-avds`) would not boot across ~5 attempts: `qemu-system-x86_64` launched and
  stayed alive but sat at ~0.8–1.0 CPU-seconds total (idle, not booting) and no adb port 5554–5560
  ever opened, so `adb devices` stayed empty and `adb wait-for-device` blocked forever. Two causes
  compounded: (a) the AVD's actual data dir is `~/.android/avd/Medium_Phone.avd` — NOT
  `Medium_Phone_API_36.1.avd` — the real path only exists in
  `~/.android/avd/Medium_Phone_API_36.1.ini`'s `path=` line; (b) prior sessions had force-killed
  the emulator (`Stop-Process -Force`), which left stale `hardware-qemu.ini.lock` and
  `multiinstance.lock` files in that real `.avd` dir, wedging the next boot. WHPX acceleration was
  confirmed usable (`emulator -accel-check` → "installed and usable"), so accel was not the cause.
  The same emulator had worked fine for A3–A6, so this is environmental drift, not a project
  regression — and clearing the locks did NOT fully clear it this session, implying a deeper
  transient machine-level wedge; only a laptop reboot (or Android Studio cold-boot/wipe-data)
  reliably recovered it.
  **Fix/prevention:** before spawning the emulator, read `~/.android/avd/<name>.ini`'s `path=` line
  to get the REAL `.avd` dir — never assume the name-based default. Prefer graceful shutdown
  (`adb emu kill`) over force-kill. If a boot hangs with qemu idle (CPU time not climbing) and no
  adb port opens, clear `*.lock` in the REAL avd dir and relaunch once; if it still hangs, reboot
  the laptop rather than repeatedly force-killing and relaunching — force-killing compounds the
  lock problem instead of fixing it. (seen 1x)
  **RESOLUTION (same A7 session, 2026-07-18):** the deeper wedge was a **corrupted SAVED SNAPSHOT** —
  launching with **`emulator -avd <name> -no-snapshot-load`** (cold boot, ignore the snapshot) booted
  reliably every time for the rest of the session (dozens of installs). So the fast recovery order is:
  (1) clear stale `*.lock` in the real avd dir, (2) launch with **`-no-snapshot-load`** — NOT a reboot as
  the first resort. Also: after `am start` on a fresh install, the first tap often lands mid-load (Home's
  progress query is slower now) — wait for the screen to settle (or re-tap) before scripting taps.

- **2026-07-18 · Real pack content leaked into tracked files AGAIN (caught by secret-scanner pre-push).**
  A real flashcard fragment (a specific gene/disease association) slipped into a `Cloze.kt` doc-comment
  example, a JOURNAL line, and a plan file while writing up the A7 cloze fix. This is the **already-promoted** rule ("never paste
  real pack content into tracked files — use synthetic FORMS"), so it should not have recurred. The
  `secret-scanner` agent caught it on the pre-push scan and it was redacted to synthetic wording
  (`FOO-{{c1::BARBAZ}}`) before the push. **Why it recurred:** when documenting a *real bug reproduction*,
  it's tempting to quote the exact card you tested against — that is still pack content. **Prevention
  (reinforced):** when a doc/comment/journal needs an EXAMPLE, invent a synthetic one from the start
  (`FOO/BARBAZ`, `Alpha/Omega`); never quote the actual card you verified against, even in prose. A
  pre-existing real fragment also survives in `DossierMarkdownTest.kt` (from A4, already on origin). (seen 3x
  → the rule holds; the gap is *examples in prose/comments*, not just code fixtures.)

- **2026-07 · `assembleDebug` proves nothing about R8 — always test `assembleRelease` before a release.**
  The FIRST-ever `assembleRelease` (2026-07-21) failed at `minifyReleaseWithR8`: "Missing class
  lombok.Generated / lombok.NonNull (referenced from io.github.openspacedrepetition.Card ...)". Only
  debug APKs had been built before (no R8/minify in debug), so this was invisible until release. Root
  cause: `java-fsrs` (the FSRS-6 dep) was compiled with Lombok; `Card`/`ReviewLog` reference
  `lombok.Generated`/`lombok.NonNull`, compile-time-only annotations absent from the runtime classpath —
  R8 treats a missing referenced class as an error.
  **Fix/prevention:** add `-dontwarn lombok.**` to `app/proguard-rules.pro` (this is exactly what R8's
  own `build/outputs/mapping/release/missing_rules.txt` suggests) — never a `-keep` rule, never add
  Lombok as a real dependency. `-dontwarn` only silences the missing-reference check; keeps no code,
  doesn't weaken shrinking, and Lombok stays non-runtime so `verifyOfflineGuarantees` stays green. Fixed
  in commit `344260b`. Before any release, run `.\gradlew.bat :app:assembleRelease` — not just
  `assembleDebug` — and check `missing_rules.txt` if R8 fails. (seen 1x)
