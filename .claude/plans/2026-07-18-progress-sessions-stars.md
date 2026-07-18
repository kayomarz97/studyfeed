# A7 cycle 3 — Progress overhaul · Sessions · Library stars · new learning modes

**Date:** 2026-07-18  ·  **Stage:** §8 A7 (design pass), cycle 3  ·  **Machine:** laptop (app)
**Status:** PLAN — awaiting per-part layout sign-off (user workflow: show layout → approve → build).
**Deliverable:** one APK at the end (user: "one APK later").

> Plain-language summary: the progress numbers were built thin and a few are genuinely wrong;
> the feed always restarts at the top; you can't keep several study runs going; and the Library
> can't be marked up. This cycle fixes the progress screen and makes it data-rich, adds resumable
> **named sessions** next to the existing quick tiles, lets you **star topics and lines** (with an
> approximate PYQ/5★ auto-highlight), and adds **mock exams, weak-spot drills, custom decks, and
> reader polish**. No exam string is hardcoded, no new runtime deps, no markdown/image library, and
> **your progress DB is only ever extended additively — a pack re-import still never wipes it.**

---

## Invariants this cycle must not break (checked every build)

- **No exam-specific string** — every label from `pack_manifest` (§0 multi_pack).
- **`verifyOfflineGuarantees` stays green** — no INTERNET, no background component, no networking dep.
- **No new runtime dependency** — charts/heatmaps/animations are hand-drawn Compose `Canvas` + the
  standard animation APIs already on the Compose BOM. No MPAndroidChart, no Coil, no markdown lib.
- **Progress DB grows only by additive migration** (`MIGRATION_2_3`), never `fallbackToDestructiveMigration`.
  Anything the user creates (stars, line marks, sessions, decks) anchors to a **pack `stable_key`**, so a
  rebuilt/re-imported pack keeps it attached (§5j philosophy).
- **No realtime generation** — everything renders pre-built pack content; approximate highlighting is a
  local *match*, not content generation.

---

## The diagnosis (evidence from this session's read-through)

| # | Symptom | Root cause (file) | Fix phase |
|---|---|---|---|
| 1 | Review tile bar always empty | `HomeScreen.ReviewTile` hardcodes `ProgressBar(0f)` | P1 |
| 2 | Subject-wise progress empty/wrong | `StatsRepository.subjectMastery()` returns only *studied* subjects, drops NULL `subject_id`, shows mastery not coverage | P1 |
| 3 | Mistakes can't group by subject | `FeedViewModel.onMcqAnswered` writes `subject_id = null` | P1 |
| 4 | Accuracy % feels wrong | `StatsViewModel` accuracy blends all card types ("Again" on a reel = a miss) | P1 |
| 5 | Feed "starts with the same MCQ" | `FeedViewModel.reload()` always rebuilds from offset 0; learning cards due in minutes lead | P2 |
| 6 | No multiple/named study runs | `sessions` table exists but unused; single global `feed_state` | P2 |

**Confirmed safe:** the write path keys FSRS/stars/tags/mistakes consistently on `feed_items.id`
(no feed-id vs asset-id mismatch) — so this is *surfacing + features*, not a data migration.

**Must verify against the real pack before P1 coding:** the fraction of `feed_items` carrying a
non-NULL `subject_id`. If past-paper/qbank MCQs are largely subject-less, subject coverage must be
**derived** from each item's linked atom/topic (or shown as an explicit "Unattributed" bucket).
→ First build task of P1: query `pack_MRCP_PART1.db` for `COUNT(*) GROUP BY subject_id IS NULL`.

---

## Phase 1 — Progress overhaul (fix the 4 faults + 4 data-rich views)

**Bug fixes:** ReviewTile real bar; write `mistakes.subject_id` (add `subjectId` to `FeedCard.Mcq`
or look up at write time); accuracy computed over **MCQs only** (correct vs answered), reels/blobs
excluded from the accuracy denominator.

**New `StatsRepository` aggregates (all pure cross-DB reads, content-safe — ids + counts, no text):**
- `subjectCoverage()` → per subject: `done`/`total` (+ derived-subject fallback) and MCQ accuracy.
- `activityByDay(days)` → reviews-per-calendar-day for the heatmap (from `reviews.ts`).
- `accuracyOverTime(bucket)` → weekly correct-rate; keep + fix the existing `forgettingCurve()`.
- `dueForecast(days)` → count of cards whose `due` falls on each of the next N days.
- `coverageByImportance()` → done/total within 5★, 4★, and PYQ bands.

**New Progress screen layout (one scroll, hand-drawn Canvas charts):**
1. Today strip (streak · today/goal · XP · **MCQ** accuracy).
2. **Subject coverage + accuracy** — every subject, `done/total` bar + % correct.
3. **Activity heatmap** — GitHub-style day grid + streak.
4. **Accuracy & retention over time** — trend line + forgetting curve.
5. **Due forecast + 5★/PYQ coverage** — next-N-days bars + high-yield core rings.
6. High-importance gaps (kept).

---

## Phase 2 — Sessions + resume (alongside the existing tiles)

**Schema (MIGRATION_2_3, additive ALTERs on the existing `sessions` table):**
add `name TEXT`, `config_json TEXT`, `cursor INTEGER`, `updated TEXT`, `archived INTEGER DEFAULT 0`.
Per-session engine memory → `feed_state` key `engine:<sessionId>` (key/value, no migration).

**Behaviour:**
- Home gains a **"Your sessions"** section (resume cards: name · progress · last-opened), above/below
  the quick tiles (which stay).
- **New session** = the existing `FilterSheet` reused as a config screen (content type, subject,
  5★/4★, PYQ, study mode) → auto-named from the picks ("Cardiology · 5★ · PYQ") → editable.
- **Resume** restores the session's `cursor` + scroll position; **Endless mix** = the default session
  that always remembers its place (fixes fault #5/#6).
- Rename inline; long-press to archive.

**FeedViewModel:** parameterise by `sessionId`; load/save that session's cursor + `feed_state`;
`reload()` seeds the pager at the saved cursor instead of 0.

---

## Phase 3 — Library stars + approximate PYQ/5★ highlights

**Topic star:** reuse `stars` with `item_type='topic'`, `item_id = topic_pages.id` — no migration.
Library rows + dossier header get a ★ toggle; a "Starred topics" filter chip.

**Line marks (new table, MIGRATION_2_3):**
`line_marks(topic_key TEXT, section_ord INTEGER, line_hash TEXT, kind TEXT, ts TEXT,
PRIMARY KEY(topic_key, section_ord, line_hash))` — `topic_key = topic_pages.stable_key` (survives
rebuild), `line_hash = sha1(normalized line text)` (survives small body reflow), `kind = star|highlight`.
Tap-and-hold a rendered line → star/highlight; marks reload by (key, section, line hash).

**Approximate auto-highlight (on-device, user-accepted as imperfect):**
on opening a dossier, off-thread, gather the topic's **5★ atoms' statements** + its **PYQ MCQ stems**
(via `related_mcq_ids_json` + the concept's atoms), then flag each rendered line whose salient-token
overlap (the same idf-ish overlap the compiler's grounding gate uses) clears a threshold → subtle left
border (coral = PYQ, gold = 5★). Bounded per topic; a miss/false-flag is acceptable and never blocks reading.

---

## Phase 4 — New learning modes

- **Mock exam:** timed, **blueprint-weighted** MCQ block (sample subjects by `subjects.blueprint_weight`,
  PYQ-weighted option), no immediate reveal; on submit → score report (overall · per-subject · vs a
  configurable pass mark) and optionally feed answers into FSRS/mistakes. New screen; run persisted in
  `sessions` with `mode='exam'`.
- **Weak-spot drills + leeches:** "Drill my weakest subject" = a session filtered to the lowest-coverage/
  lowest-accuracy subject, Priority sort. **Leech** = `fsrs_cards.lapses ≥ N` (or repeated mistakes) →
  surfaced in Review and as a one-tap drill.
- **Custom decks:** a deck = a named saved filter (subject/importance/PYQ/tag/starred), studyable as a
  session; quick-build from "Starred" or any tag. Stored in a small `decks(name, config_json, ts)` table
  (MIGRATION_2_3) — decks are templates, sessions are running instances.

---

## Phase 5 — Reader polish + motion + colour (cross-cutting)

- Reader: font-size + dim/AMOLED controls (`settings` keys); **jump from a question → its topic section**
  (deep-link into the dossier at a section index).
- Motion: `AnimatedContent`/`Crossfade`/slide on card advance, tile taps, sheet transitions —
  `prefers-reduced-motion`-respecting, no jank; standard Compose animation only.
- Colour: refine the clinical-night palette (less "AI slop"); tune accent saturation on true black.
- **Branding / credit (user request 2026-07-18):** a footer line in **Settings** —
  *"Made with love by Kayo (and clinically significant dose of Coca-Cola)"* — **tappable → opens
  `https://kayomarz.com`** via an implicit `ACTION_VIEW` intent that hands off to the system browser.
  **This adds NO `INTERNET` permission and NO networking dep, so `verifyOfflineGuarantees` stays green**
  (verified on build); wrapped in try/catch for `ActivityNotFoundException` (no browser). The same line
  also shows on a lightweight **branded opening screen** at launch — a first-frame Composable that
  crossfades to Home after the initial pack/registry load (a foreground `LaunchedEffect`, **never** a
  splash Service/timer/background component).

---

## Testing

- JVM: session cursor round-trip; line-hash anchoring stable across a simulated body reflow; accuracy =
  MCQ-only; subject-coverage math; blueprint-weighted exam sampling distribution.
- Instrumented (emulator, real pack): subject coverage joins on real data; approximate highlight flags a
  known 5★/PYQ line in a real dossier; a session resumes at its cursor after VM recreate.
- Every build: `verifyOfflineGuarantees` + `testDebugUnitTest --rerun-tasks`. Emulator smoke each phase.
- secret-scanner before push; stage files by name; push `dev` only after an explicit ask.

## Build order & APK
P1 → P2 → P3 → P4 → P5, each shown as a layout mockup for sign-off before its code. **One APK** built
and delivered after the approved parts land (debug; release-signing still owed from cycle 2, §11d).

## Open risks
- Subject attribution (see "must verify") could turn P1's coverage view into a derivation job.
- Approximate highlight precision — tune threshold on real dossiers; ship conservative (miss > false-flag).
- Scope is large; if any phase balloons, stop and re-confirm rather than silently cut.
