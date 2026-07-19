# Plan — feed tracking fix, control cleanup, shuffle, resume, settings (2026-07-19)

Authorised by the user ("yes go ahead") after reviewing the layout map. Every change below is a
long-term/root-cause fix, not a stopgap. All get justified in `JOURNAL.md` when they land.

## 0. The confirmed set (user answers + chat edits)

- **Q1 Tracking** = "Answer/reveal counts it" — the primary interaction records a *done* review.
- **Q2 Single-type feed** = refine by **Subject + Importance + Focus** (stay on the type).
- **Q3 Shuffle** = **shuffle within importance tiers**, varied per feed, stable within a feed.
- **Q4 Settings** = **Reader text size · granular Reset · Keep-screen-on + haptics**
  (+ user note: Reset must be *fine-grained* — pick exactly what to reset).
- **Chat edit:** remove **FEED-T2** (`Study: Mixed ▾`) and **FEED-T4** (`Clear all ✕`/token row)
  from the feed **entirely** (all feeds, not just single-type).
- **Resume + scroll-up:** every feed lands where you left off AND lets you swipe back up through
  what you've already seen.

## 1. Tracking fix — "done" on the primary interaction (THE bug)

**Root cause.** Every coverage/activity/streak/XP aggregate reads `main.fsrs_cards` (row exists) or
`main.reviews`. Both are written ONLY by `rate()` (a RatingBar tap). `onMcqAnswered` writes
`mcq_attempts` (accuracy) but not `fsrs_cards`/`reviews`. Answer-and-swipe = the natural flow =
never taps a rating ⇒ every "done" reads 0. (Also: blobs never get an `fsrs_cards` row at all, so
`progressByType['blob'].done` is structurally always 0.)

**Fix — a single `recordCompletion` path, auto-graded, one review per appearance:**
- MCQ answer → `onMcqAnswered` also records completion, auto-graded (correct→GOOD, wrong→AGAIN):
  upsert `fsrs_cards` + insert one `reviews` row. `mcq_attempts` + mistake handling unchanged.
- Flashcard/Reel/Interlink → new `onSeen` callback fired on reveal → completion, default GOOD.
- Blob read → completion writes a `reviews` row (schedule stays in `blob_reads`, NOT fsrs, so the
  engine doesn't double-schedule it). "done" for blobs = a `blob_reads` row.
- Explicit RatingBar tap = **refine**: recompute FSRS from the *pre-completion* state and UPDATE the
  same `reviews` row (no second review). In-memory `pending[(type,id)] = (priorCard, reviewId)`;
  a `writeMutex` serialises completion vs refine so they can't double-insert.
- `progressByType()` (Home tiles): blob `done` = count of `blob_reads` joined to blob feed_items;
  other types keep fsrs-row count.

**Files:** `progress/ProgressDatabase.kt` (`insertReview` returns Long; add `updateReviewRating`);
`ui/feed/FeedViewModel.kt` (`recordCompletion`/`refine`/`onSeen`, writeMutex, `pending`);
`ui/feed/FeedCards.kt` (add `onSeen` to Flashcard/Reel/Interlink; blob already has `onRead`);
`ui/feed/FeedScreen.kt` (wire `onSeen`); `feed/FeedRepository.kt` (`progressByType` blob done).

## 2. Remove FEED-T2 + FEED-T4

`ui/feed/FeedScreen.kt`: top row becomes `‹ Home … ⚙ Filter`. Delete the `ActiveFilterTokens` row.
`ui/feed/FeedControls.kt`: delete `StudyModeMenu`/`modeLabel`, `ActiveFilterTokens`/`clearOne`/
`typeKey` (dead once T4 is gone). `FeedViewModel`: drop `setStudyMode`/`toQuery` (dead); keep
`StudyMode`/`modeForQuery`/`studyMode` (still set on entry, harmless). Clearing filters still lives
inside the Filter sheet (FILT-1), so nothing is stranded.

## 3. Single-type feeds: refine by Subject+Importance+Focus, hide Content type

- `FeedViewModel.Ui` gains `lockedTypes: List<String>?` (null = mixed/endless). Set in
  `bindAndReload`: openMixed→null; MCQs→["mcq"]; cards→["flashcard","reel"]; passages→["blob"];
  review/leeches/decks/drills→null.
- `FilterSheet(…, lockedTypes)`: when `lockedTypes != null` **hide the Content-type group**, and
  "Clear all" resets to `FeedFilter(itemTypesIn = lockedTypes)` (keeps you on the type) instead of
  `FeedFilter()`.
- `FilterButton` badge = **refinement count** (exclude the locked `itemTypesIn`), so a locked MCQ
  feed doesn't show a permanent "Filter (1)".

## 4. Shuffle within importance tiers (Q3) — coherent with resume

Order becomes `importance DESC, prkey ASC` where `prkey = (id*A + seed) % M` — tier-preserving, so
5★ still precede 4★, but the order INSIDE a tier is a seeded permutation. Seed is **per feed key**,
stored in `feed_state` (`seed:<key>` for the filtered path; a `shuffleSeed` field in the engine
state), **stable within a feed** (so OFFSET/keyset windows and resume stay coherent) and **fresh
per feed / on an explicit "reset place"** (so different feeds/sessions differ and it never feels
like the same fixed list).

- **Filtered path** (`FeedRepository.page`, tiles + IMPORTANCE_DESC/SMART): add a `seed` arg;
  `sortSql` uses the tier+prkey order. OFFSET-based, so only stability-within-a-viewing is needed.
- **Engine path** (`newMcqs`/`newOthers` + `FeedScheduler`): keyset changes from `(score,id)` to
  `(importance, prkey)`. `FeedState` cursor fields `…CursorScore`→`…CursorImp`; add `shuffleSeed`.
  `Pools.newMcqs(afterImp, afterId, seed, limit)`. VM assigns a fresh seed when the loaded engine
  state has none. Update `FeedSchedulerTest` fakes + keyset assertions.

## 5. Resume + scroll-up

- **Filtered path:** load a **back-buffer** (BACK=40) before the resume offset plus the forward
  window: `page(query, max(0, pos-BACK), BACK+PAGE, seed)`; `Ui.startAt` = index of the resume item
  inside the window. `FeedScreen` scrolls the pager to `startAt` on (re)load (keyed by a load token),
  so you can swipe UP through the last ~40 seen and DOWN to load more.
- **Engine path:** already accumulates a forward list you can scroll up within a viewing; resume =
  continue after the cursor (unchanged). Scroll-up within the open works today.

## 6. Settings (Q4)

- **Reader text size** — `SettingsKeys.READER_TEXT_SCALE` (S/M/L/XL → 0.9/1.0/1.15/1.3). Provided at
  `Root` as `LocalReaderScale`; consumed by the **dossier** renderer (`DossierText`) and **passage
  (blob)** body only — leaves MCQ/flashcard layouts untouched. Settings: a chip row.
- **Granular Reset** — a Settings section, each action behind an AlertDialog confirm:
  1. *Feed positions & shuffle* — clear `feed_state` (fresh start + reshuffle; progress kept).
  2. *Study history & schedule* — clear `fsrs_cards`+`reviews`+`blob_reads`+`mcq_attempts`.
  3. *Mistakes* — clear `mistakes`.
  4. *Stars, tags & highlights* — clear `stars`+`item_tags`+`line_marks`.
  5. *Everything* — wipe all progress tables (biggest confirm; pack untouched).
  New DAO `@Query` deletes for each; a `ResetKind` enum in `SettingsViewModel`.
- **Keep-screen-on + haptics** — `SettingsKeys.KEEP_SCREEN_ON` (default off), `SettingsKeys.HAPTICS`
  (default on). Feed/MockExam apply keep-screen-on via a `DisposableEffect(view.keepScreenOn)`;
  card interactions fire `LocalHapticFeedback` when enabled (via `LocalHapticsEnabled`). Loaded in
  `FeedViewModel.loadSettings` → `Ui`. No new permissions/deps (guard stays green).

## 7. Verify

`.\gradlew.bat verifyOfflineGuarantees` · `assembleDebug` · `testDebugUnitTest --rerun-tasks`
(FSRS + scheduler + any new pure tests). Instrumented migration/session tests if an emulator is up.
Then hand over the debug APK. Journal + INDEX updated in the same pass.

## Round 2 (2026-07-19, after on-device use) — 5 more

1. **MCQ stays solved when you scroll back up (endless + filtered) + shows your pick vs the right answer.**
   Card `chosen` lives in `remember(card.itemId)`, so a scroll-back re-render (pager disposes/recomposes)
   resets it to unanswered. FIX: persist the chosen index in the VM (`answeredThisOpen: Map<Long,Int>`,
   surfaced as `Ui.answeredMcqs`); `McqCard` seeds `chosen` from it → the revealed state (your wrong pick +
   the correct option + explanation) comes back.
2. **A named session keeps its filter until you change it.** On `reload` inside a session (`sessionId != null`)
   persist the current query to the session's `config_json` (new DAO `updateSessionConfig`); `openSession`
   already restores it.
3. **Home "Decks" → Hard / Easy (the old tag data was stale).** Add `FeedFilter.hardOnly` (lapses ≥ 1 —
   you've missed it) + `easyOnly` (reviewed, no lapse, stability ≥ 7 d — you know it) → SQL in `whereClause`;
   Home shows **★ Starred · Hard (n) · Easy (n)** with counts (new DAO `hardCount`/`easyCount`).
4. **Library: star a SENTENCE, not the whole block.** The dossier renders one block per line (a bullet or a
   whole paragraph); a paragraph block = "the whole thing". FIX: split each block into sentences
   (`Sentences.split`, conservative — guards abbreviations / decimals / `10^9` / allele `*`), render one
   markable row per sentence, anchor the mark on the sentence hash. `buildAuto` also keys per sentence.
5. **5 star colours on long-press.** Long-press a sentence → a colour picker (5 swatches + Remove); the mark
   `kind` becomes `c1..c5` (was the single `"star"`); `colorForMark` maps them to 5 distinct hues; the auto
   PYQ/5★ highlight stays visually separate (coral/gold). DAO reset already clears `line_marks`.

**Files:** `ui/feed/FeedViewModel.kt` + `ui/feed/FeedCards.kt` + `ui/feed/FeedScreen.kt` (#1); `+progress/
ProgressDatabase.kt` (#2 `updateSessionConfig`, #3 counts); `feed/FeedQuery.kt` + `feed/FeedRepository.kt`
(#3 filter); `ui/home/HomeScreen.kt` + `ui/home/HomeViewModel.kt` + `MainActivity.kt` (#3 decks); new
`dossier/Sentences.kt` + test, `ui/dossier/DossierMarkdown.kt` + `DossierViewModel.kt` + `DossierScreen.kt`
(#4/#5). Verify: guard + `testDebugUnitTest` (+ `SentencesTest`) + `assembleDebug{,AndroidTest}`.

## Non-negotiables kept

No new runtime deps (guard denylist). No exam string. Additive only — NO schema-version bump needed
(all new tables/columns already exist at v3; the reset deletes rows, never drops schema). No markdown
library; reader scale multiplies existing in-house renderers. `allowBackup=false` unchanged.
