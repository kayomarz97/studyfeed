# Plan — Honest empty states for lean packs (2026-07-21)

> Public-release Workstream 1. Users load their own (possibly lean) packs. Every section a pack
> can't fill must **say so plainly** ("this pack doesn't include X") instead of a blank/broken
> screen. Capability detection already exists (`PackCapabilities`, rows-authoritative) — this work
> only wires it into every user-facing section.

## Plain-language summary
The app can play any study pack a user imports. A small pack might have no concept map, no topic
pages, no passages, etc. Right now those sections can render blank or dead. We'll make each one show
an honest message — either **"this pack doesn't include a concept map"** (the pack never had it) or
**"all caught up — nothing due"** (it has it, you've just finished it / filtered it out). Two very
different messages; never a blank screen.

## Technical design

### 1. One reusable composable + one pure decision
- **`ui/feed/CardChrome.kt`** — add `@Composable fun EmptyState(title, body, modifier)` (centered
  title + muted body). No new dependency.
- **NEW `ui/feed/EmptyStates.kt`** (pure Kotlin, no Compose) — the tested decision:
  ```
  enum class EmptyCase { ABSENT, NOTHING_MATCHES, NOTHING_DUE }
  object EmptyStates { fun decide(capabilityPresent: Boolean, filterActive: Boolean): EmptyCase }
  ```
  - `!capabilityPresent → ABSENT` (factual: "this pack doesn't include X")
  - `capabilityPresent && filterActive → NOTHING_MATCHES` (clear a filter)
  - `capabilityPresent && !filterActive → NOTHING_DUE` (encouraging: "all caught up")

### 2. Thread `capabilities` to every section
`PackUiState.ActiveExam.capabilities` already exists and is the single source.
- **MainActivity.Root** — add `val packVm: PackUiState = viewModel()` (same activity-scoped instance
  PackScreen uses; import updates it). Pass `packUi.active?.capabilities` to `LibraryScreen` and
  `ConceptMapScreen`.
- **FeedViewModel.Ui.capabilities** — populate in `loadPack()` from the opened pack; FeedScreen reads
  it and passes to `FilterSheet` for the content-type chips.
- **HomeViewModel.Ui.capabilities** — populate from the already-opened pack; HomeScreen tiles read it.
- **StatsViewModel.Ui.packHasContent** — `feedRepo.size() > 0`, for the stats absent/empty split.
  (Stats is progress-driven, not a `PackCapabilities` flag.)

`capabilities == null` (still loading) ⇒ never show ABSENT; fall through to the normal screen.

### 3. Section gates (top of each screen)
- **ConceptMapScreen** (`!hasConceptMap`) → header + `EmptyState("Not in this pack", "This pack
  doesn't include a concept map.")`.
- **LibraryScreen** (`!hasDossiers`) → header + `EmptyState(…, "This pack doesn't include topic
  pages.")`.
- **StatsScreen** — `!hasPack` (import prompt) · `!packHasContent` → ABSENT ("no scorable items") ·
  `!hasData` → NOTHING_DUE ("answer some questions…"). Rendered via `EmptyState`.
- **FeedScreen** empty state → `EmptyStates.decide(present, filterActive)` where
  `present = filterActive || total > 0`; ABSENT/NOTHING_MATCHES/NOTHING_DUE copy.
- **FeedControls.FilterSheet** — content-type chips disabled for absent types: Passages(`hasBlobs`),
  Reels(`hasReels`), Links(`hasInterlink`), Flashcards(`hasFlashcards`); MCQ always enabled. A muted
  caption "Greyed types aren't in this pack." when any is absent.

### 4. Home tiles — disabled, not hidden
`ui/home/HomeScreen.kt` (+ `HomeViewModel.kt`): show ABSENT-capability tiles **disabled** with a
"not in this pack" caption (keep MCQ + Review core primary):
- Flashcards & reels tile — `hasFlashcards || hasReels`
- Passages tile — `hasBlobs`
- Library card — `hasDossiers`
- Map button — `hasConceptMap`

### 5. Keep surfacing `declarationMismatches` (already on the Manage screen — unchanged).

## Hard rules honored
- No exam-specific string (use "this pack", never "MRCP").
- No new dependency (offline guard sacred); no markdown library.

## Tests + verify
- **JVM:** `EmptyStatesTest` — `decide()` across all (present × filter) combinations.
- **Instrumented:** `EmptyStateGatingTest` — build a FULL synthetic pack and a TRIMMED one on the
  same binary; assert real `PackCapabilities` feed `decide()` → NOTHING_DUE (full) vs ABSENT
  (trimmed) for concept map / passages / dossiers. Reuses PackDatabaseTest's synthetic-pack pattern.
- Run: `verifyOfflineGuarantees` · `testDebugUnitTest --rerun-tasks` · build + emulator/device pass
  Home → every section on a lean pack; screenshots.
- Update `.claude/INDEX.md` for the new files. Commit to `dev` with the trailer convention; push.
