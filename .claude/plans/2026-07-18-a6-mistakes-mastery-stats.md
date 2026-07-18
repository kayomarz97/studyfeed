# A6 — Mistakes, Mastery, Stats (plan)

**Date:** 2026-07-18 · **Branch:** `dev` · **Stage:** Phase 2 / §8 **A6**
**Golden-loop:** plan-first → question → build → verify → log.

> Read-first: `BUILD_PROMPT.md` §8 A6, `progress/ProgressDatabase.kt` (schema + it's **v1, no
> migrations, NO destructive fallback**), the A7 resume note. Design polish deferred to A7.

## 1. Spec (§8 A6)

Mistakes review = feed filtered to unresolved `mistakes`, **filterable by star/tag, sortable by
importance**; **re-drill until correct N times → resolved**. Per-subject **mastery bars** (FSRS stability
+ accuracy) + **forgetting-curve** view + a **"high-importance gaps"** list (5★ items you keep failing).
Local gamification: **streaks, daily goal, XP** — tasteful.

## 2. Data reality (progress-DB heavy, unlike A4/A5)

- **`mistakes`** (id, item_type, item_id, subject_id, chosen_idx, ts, resolved) — A2 already inserts a
  row on a wrong MCQ. The feed's **`mistakesOnly` filter already exists** → "Mistakes review" is that
  filter; A6 adds the **resolution logic**.
- **`reviews`** (item_type, item_id, rating, ts) — every answer writes one → total attempts per item.
  Accuracy per subject ≈ `1 − mistakes / attempts` (MCQs: every answer rates, so reviews = attempts).
- **`fsrs_cards`** (stability, difficulty, due, …) → mastery (mean stability) + the forgetting curve
  (FSRS retrievability from stability). subject lives in the PACK (`feed_items.subject_id`) → cross-DB
  read via the ATTACH connection (like `FeedRepository`).
- **`sessions`** + `reviews.ts` → streak (consecutive calendar days with a review), daily goal, XP.

**Schema change (needed):** "correct **N** times" needs a per-mistake counter. Add
`resolved_count INTEGER NOT NULL DEFAULT 0` to `mistakes` via a **Room migration v1→v2**
(`ALTER TABLE mistakes ADD COLUMN …`) — **not** destructive fallback (forbidden). Bump `@Database`
version, `.addMigrations(MIGRATION_1_2)`, add a migration test (open v1 → migrate → column usable,
existing rows preserved). This is the durable, root-cause fix (rule #1).

## 3. Architecture

```
progress/ProgressDatabase.kt   +resolved_count column, version=2, MIGRATION_1_2, DAO:
   resolveProgress on correct answer (increment resolved_count; at N → resolved=1),
   reopenMistake on wrong, unresolvedMistakeCount, mistakes-by-subject, attempts-by-subject.
feed/StatsRepository.kt         cross-DB (ATTACH) read-only: per-subject {meanStability, attempts,
   mistakes} joined pack.feed_items ↔ progress; high-importance (5★) unresolved-mistake items;
   forgetting-curve buckets (count of cards retrievable now / +1d / +7d / +30d from stability).
ui/stats/
   StatsScreen.kt        one scroll: Today (streak · daily-goal ring · XP) · Mistakes (count →
     "Review mistakes" opens the feed with mistakesOnly) · per-subject Mastery bars · Forgetting curve
     (Canvas) · High-importance gaps list (tap → drill that item).
   StatsViewModel.kt     assembles it all off-thread from ProgressDatabase + StatsRepository.
```

**Re-drill wiring:** the feed's MCQ answer already reports `(chosen, correct)`. Extend `FeedViewModel`:
correct → `dao.resolveProgress(type,id, N)`; wrong → record/reopen a mistake. So answering a mistaken
item correctly N times in the (filtered) Mistakes feed resolves it and it drops out of the filter.

**Streak/goal/XP (derived, tasteful):** streak = longest run of consecutive days ending today with ≥1
review (from `reviews.ts`). Daily goal = today's reviews+new ≥ the A3 pace target (reuse `feed.deadline`
→ daily MCQ budget). XP = a simple deterministic function (e.g. reviews + resolved-mistakes bonus).

**Entry:** a "Progress" button in the feed top bar (global nav = A7; the A7 home-page backlog item 2.1
may later fold this in — noted, not built now).

## 4. Scope guard

- **Nothing generated; no exam strings** (subjects/importance from the pack).
- **No charting library** — the forgetting curve + bars are drawn on Compose Canvas (like A5).
- **No new runtime dependency.** No destructive migration — a real `MIGRATION_1_2`.
- Mistakes review REUSES the A2 feed (`mistakesOnly` filter) — don't build a second feed.

## 5. Open decision → ASK

**Re-drill N** — how many correct answers resolve a mistake (1 = quick, 2 = confirm, 3 = drill hard).
Affects the resolution logic + is a real pedagogical choice. (All else follows the spec.)

## 6. Build order

1. Ask N. 2. Migration v1→v2 (+ test). 3. DAO resolve/reopen/aggregate. 4. `StatsRepository` (cross-DB).
5. Wire feed re-drill resolution. 6. `StatsScreen`/`StatsViewModel` + Canvas curve/bars + feed entry.
7. Instrumented tests (resolution flow, per-subject aggregation, gaps, curve buckets) + `verifyOffline`
   + on-device smoke. 8. INDEX/JOURNAL; commit; secret-scan before any push.
```
