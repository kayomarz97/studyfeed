# A7 — Cycle 1 implementation plan (the design pass, "everything in one APK")

**Date:** 2026-07-18 · **Branch:** `dev` · Base: `41f4ae4` (A6). Test target: **emulator-5554** (phone
not connected). Deliverable: **debug APK as a GitHub Release asset** (not committed — `app/build/**` is
gitignored, §11b), secret-scanned first.

## User's A7 choices (2026-07-18, AskUserQuestion)
1. **App shell = Dashboard home first** — a landing page ("what's left") → "Start studying" → feed.
2. **Card signals = Loud PYQ badge** — PYQ bold up top with importance stars; subject quiet at the bottom.
3. **Flashcard vs reel = Both** — distinct styling **and** a study-mode toggle (Mixed / By subject /
   Recall only / Skim).
4. **Scope = Everything in one APK** — full A7 this cycle.

Plus the already-confirmed safe wins and the A3–A6 polish carried into A7:
- **2.4 Tags → bottom, only after the answer is revealed** (star stays top).
- **2.6 Tap anywhere to reveal** (reel, interlink, flashcard, blob; MCQ keeps option-tap).
- Concept-map spread + label de-collision. Daily-goal wired to the A3 deadline pace. Re-drill N as a setting.

## Standing constraints (do not break)
Zero new runtime deps · no markdown library · no Coil/Glide · no exam-specific string (labels from
`pack_manifest`) · no `fallbackToDestructiveMigration` · `verifyOfflineGuarantees` green · never
substring-match the manifest · ask before push · secret-scan before push.

---

## Work items

### 1. Dashboard home (new start screen)
- `ui/home/HomeScreen.kt` + `HomeViewModel.kt` (new). Shows: exam name (pack manifest), **due today** count,
  **new remaining** + **today's target** (A3 pace), **streak**, per-subject progress bars, and nav:
  **Start studying** → Feed; buttons → Library (Search), Concept Map, Progress (Stats), Settings, Manage.
- Data: reuse `StatsRepository.subjectMastery()` + DAO `reviewTimestamps` + `FeedRepository.remainingNewMcqs()`
  + a new `dueCount(nowIso)` (content-safe count) + the `feed.deadline` setting for the daily target.
- `MainActivity`: start destination → `Screen.Home`; add `Screen.Home` + `Screen.Settings`; wire back-nav
  (feed/library/map/stats/settings → Home).

### 2. Card signal redesign — loud PYQ badge (`CardChrome.kt`, `FeedCards.kt`)
- `PyqBadge` → **loud variant**: filled primary-container pill, bold, "PYQ · 2019 · 2023". Prominent.
- New top row per card: **loud PYQ + importance stars** (left) · **star** (right). Subject leaves the header.
- New `SubjectFooter` (quiet uppercase label) rendered at the **bottom** of the card.
- `CardScaffold` gains `revealed: Boolean` + optional `onReveal: (() -> Unit)?`:
  - relocates the **tag row to the bottom**, shown **only when `revealed`** (2.4);
  - hosts **tap-anywhere-to-reveal**: while `!revealed && onReveal != null`, a no-indication full-card
    clickable calls `onReveal()` (2.6). MCQ passes `onReveal = null` (keeps option-tap).
- Each card computes `revealed` (mcq: chosen!=null; flashcard: flipped; reel/interlink: revealed; blob: read)
  and passes it + its reveal setter.

### 3. Flashcard vs reel distinct styling (`FeedCards.kt`)
- Reel: **"DID YOU KNOW?"** eyebrow, skim framing, keep got-it/shaky.
- Flashcard: **"RECALL THIS"** eyebrow, active-recall framing, keep 4-button FSRS.

### 4. Study-mode toggle (`FeedQuery.kt`, `FeedRepository.kt`, `FeedControls.kt`/`FeedScreen.kt`, `FeedViewModel.kt`)
- `FeedFilter += itemTypesIn: List<String>?` → compiled to `item_type IN (...)`.
- `FeedQuery.Sort += SUBJECT` → `ORDER BY subject_id, importance DESC`.
- A `StudyMode` mapping in the UI: Mixed → SMART+all (engine) · By subject → Sort.SUBJECT · Recall only →
  itemTypesIn=[mcq,flashcard] · Skim → itemTypesIn=[reel,blob]. Non-Mixed uses the A2 `page` path.
- Segmented row of modes above the feed.

### 5. Settings screen (`ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt`, new)
- **Deadline** date-picker → `feed.deadline` (existing setting; drives the pace immediately).
- **Re-drill N** (1/2/3/5) → new setting `redrill.n`; `FeedViewModel.REDRILL_N` reads it.
- **Feed intensity** presets (Gentle/Balanced/Intense) → `feed.dueBurst` + `feed.newRatioPct` settings;
  `FeedViewModel.engineConfig` built from them.
- **Export / Restore progress** → reuse `PackUiState.exportProgress/importProgress` + SAF launchers (§11e).
- **Manage packs** → route to the existing Manage screen.
- AMOLED: informational (true-black is a build guarantee; dynamic colour off by design). No fake toggle.

### 6. Daily-goal wired to A3 pace (`StatsViewModel.kt`, home)
- Replace `DAILY_GOAL=50` with `ceil(remainingNewMcqs / max(1, daysToDeadline))` from the repo + deadline.

### 7. Concept-map polish (`concept/ForceLayout.kt`, `ui/concept/ConceptMapScreen.kt`)
- More spread (repulsion/area/iterations); simple label de-collision (place labels, drop/oﬀset overlaps).

### 8. Settings-backed config plumbing
- Add DAO/`Setting` reads for `redrill.n`, `feed.dueBurst`, `feed.newRatioPct`; `FeedViewModel` consumes them.

---

## Verify (each a real run, reported honestly)
- `verifyOfflineGuarantees` green after every logical chunk.
- `testDebugUnitTest --rerun-tasks` (FSRS, PackMarkdown, DossierMarkdown, ForceLayout, FeedScheduler JVM).
- Instrumented smoke on **emulator-5554**: home renders real counts → Start → feed shows loud-PYQ cards,
  subject at bottom, tags appear after answering, tap-anywhere reveals; study modes switch the deck;
  Settings changes the deadline/redrill; export/restore round-trips.
- secret-scanner before push. Then `assembleDebug` → attach APK to a GitHub Release via `gh release create`.

## Commit plan (one logical change each, on `dev`)
Home shell · card redesign+quick-wins · flashcard/reel+study-modes · settings+config plumbing ·
daily-goal+concept-map polish · INDEX/JOURNAL/CLAUDE doc update. Push only on the user's ask.
