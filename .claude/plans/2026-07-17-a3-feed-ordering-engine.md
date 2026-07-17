# A3 — Feed Ordering Engine (plan)

**Date:** 2026-07-17 · **Branch:** `dev` · **Stage:** Phase 2 / §8 **A3**
**Golden-loop status:** plan-first (this file) → questions to user → build → verify → log.

> Read-first context: `.claude/plans/2026-07-17-a7-design-backlog-and-resume.md` (resume note),
> `BUILD_PROMPT.md` §8 A3 (line 1124), `JOURNAL.md` ⛔ standing orders. **Design/layout polish is
> deferred to A7** — A3 builds the *engine* (correct ordering), not new card visuals.

---

## 1. What the spec asks (BUILD_PROMPT §8 A3, verbatim intent)

Compose the next card by:
1. **FSRS due pool**, cross-type, most urgent first.
2. **Importance weighting** for new / among-equal items (5★ and PYQ surface earliest).
3. **Variety rules**: never >2 of the same type in a row; land blob quiz-backs at the right delay;
   sprinkle reels for pacing.
4. **Daily new-item drip + burst length.**
5. When an **explicit sort is chosen it overrides (2)** — i.e. the engine drives only the default feed.
6. **Persist a recent-type ring buffer in `feed_state`.**

Acceptance 6 (MCQ wrong → resurfaces via FSRS) and 7 (blob quiz-back "a few cards later", re-scheduled
next session) both land here — A2 wrote `reread_due`; **A3 is what consumes it.**

## 2. Why this is not a SQL `ORDER BY` (the core design fact)

A2's `FeedRepository.page()` is a stateless `ORDER BY … LIMIT/OFFSET`. A3's rules are **stateful**:
"never >2 same type in a row" depends on what was *already emitted*; "land the blob quiz-back a few
cards later" depends on *when the blob was read*; "daily new drip" depends on *how many new items went
out today*. None of that is expressible as a single ordered SQL scan. So:

- **SQL still builds the candidate POOLS** (cheap, indexed): a *due pool* and a *new pool*.
- **A Kotlin composer INTERLEAVES them** card-by-card, enforcing variety + drip, tracking recent types.

## 3. Architecture

New file `feed/FeedScheduler.kt` — pure, deterministic given its inputs (so it unit-tests with **no
device/clock**). `FeedRepository` gains pool queries; `FeedViewModel` calls the scheduler for the
default feed and falls back to the existing A2 SQL path when an explicit sort/filter is active.

```
                       ┌─────────────────────── FeedViewModel ───────────────────────┐
                       │  query.sort == SMART & no filter?                            │
                       │        │ yes                         │ no (explicit)         │
                       │        ▼                             ▼                       │
                       │  FeedScheduler.window(now, state)   FeedRepository.page()    │
                       │        │                             (A2 path, unchanged)    │
                       └────────┼─────────────────────────────────────────────────────┘
                                ▼
   FeedRepository pools (SQL, indexed):
     • duePool(now)   = fsrs_cards.due <= now  ∪  blob_reads.reread_due <= now
                         ORDER BY urgency = f(overdue-ness, importance)   ── cross-type
     • newPool()      = feed_items with NO fsrs_cards row, never reviewed
                         ORDER BY importance_score DESC  (5★ / PYQ first)
     • fillerPool()   = seen but not-yet-due  (only if both above run dry — feed never dead-ends)
                                ▼
   Composer (Kotlin, stateful): for each next card —
     0. a blob read K cards ago whose quiz-back is now landed?  → emit the quiz-back
     1. else top of duePool, IF variety allows (≤2 same type in a row) — else next type down
     2. else, if today's new-drip budget remains → top of newPool (importance-weighted, reels sprinkled)
     3. else fillerPool  (keeps an endless feed; nothing is ever a dead end)
     → push emitted type into recent-type ring buffer; bump today's new counter
                                ▼
   Persist to feed_state (progress DB, survives export/import §11e):
     recent_types = JSON ring buffer (last K types)   ·   new_drip = {date, count}
     blob_quiz_pending = [{blob_id, land_at_index}]   ·   intro_cursor (new-pool position)
```

**Explicit-sort override (rule 5):** SMART → engine. IMPORTANCE_DESC/ASC, RANDOM, PRIORITY, or ANY
active filter → the A2 `FeedRepository.page()` path, untouched. This keeps A2's proven filter/sort
(Acceptance 4) exactly as verified, and cleanly satisfies "explicit sort overrides importance
weighting."

**Windowing:** the pager still appends windows via `onReachedIndex`. The scheduler yields a window
(batch of N) by calling its next-card step N times; state is threaded across windows via `feed_state`,
so paging forward is consistent and resumable across app restarts.

## 4. Determinism & testing (no false green)

The composer is a pure function `(now, duePool, newPool, fillerPool, state) → (cards, newState)`.
- **Unit tests (JVM, no device):** feed it synthetic pools → assert ≤2-same-type-in-a-row holds; due
  before new; 5★ new before 1★ new; blob quiz-back lands within the promised window; new-drip caps per
  day; explicit sort bypasses the engine. Deterministic, fast, run in `testDebugUnitTest`.
- **Instrumented test (real pack, on device):** the engine over 32,957 real items produces a valid
  stream (variety invariant holds across a long window; a rated-Again item reappears; a read blob's
  quiz-back surfaces). SHAPE assertions only — **no pack content echoed** (copyright rule).
- **On device:** confirm the feed *feels* right (due items lead, variety, new drip) — but tuning is A7.

## 5. Persistence keys (feed_state — all export/import-safe, §11e)

| key | value | purpose |
|---|---|---|
| `recent_types` | JSON `["mcq","reel",…]` (last K) | variety ring buffer (rule 6) |
| `new_drip` | JSON `{"date":"2026-07-17","count":N}` | daily new-item budget (rule 4) |
| `blob_quiz_pending` | JSON `[{"blob_id":…,"after":idx}]` | land quiz-back at the right delay (rule 3) |
| `intro_cursor` | int | position in the new pool (stable new-item drip) |

## 6. Scope guard (what A3 does NOT do)

- **No new card VISUALS / layout / UX changes** — those are the A7 design backlog (home page, tags-to-
  bottom, tap-anywhere, subject/PYQ redesign, flashcard/reel differentiation). A3 only orders.
- **No background/notifications** — "re-scheduled next session" means *next app-open*, in-app only.
  The offline guard forbids background components; the engine reads `now` at open, never wakes.
- **No new runtime dependency** — pure Kotlin + existing SQL, like all of A2.
- **No exam-specific strings, no invented items** — the engine only *orders* pack rows.

## 7. LOCKED POLICY (user decisions, 2026-07-17)

1. **Feed mix = Blended, due-led.** Target ~**70% due / 30% new** in the emitted stream; adaptive —
   more new when the due pool is small, more due when it's large. Deterministic ratio accumulator (no
   RNG), so it's testable.
2. **New-item drip = MCQ-paced to an editable deadline.**
   - `daily_new_mcq_budget = ceil(remaining_new_mcqs / max(1, days_until_deadline))`.
   - `remaining_new_mcqs` = 14,455 total MCQs − MCQs already introduced (have an `fsrs_cards` row).
   - **Deadline is a stored setting** `feed.deadline` (ISO date). On first open, default =
     `first_open + 56 days` (8 weeks). **User can change it** — A7 surfaces a date-picker; the value is
     read from `settings` now, so changing it re-paces immediately. Recomputes daily → miss days ⇒ budget
     rises to stay on pace; past the deadline ⇒ days-left clamps to 1 ⇒ serve all remaining.
   - **Non-MCQ new types are NOT deadline-bound** — lighter daily budget
     `other_new_budget = ceil(mcq_budget × VARIETY_RATIO)` (VARIETY_RATIO≈0.4, tunable at A7), pooled
     across flashcard/reel/blob/interlink, importance-first, reels sprinkled for pacing.
   - Budgets reset when the calendar date changes (`new_drip = {date, mcq_count, other_count}`).
3. **Overdue avalanche = cap per app-open.** Emit at most `DUE_BURST` (default 60, tunable) due items per
   app-open, most-urgent first (urgency = importance × overdue-ness); the rest roll into future opens. A
   daily user rarely hits it; a returning-after-a-gap user gets a manageable burst, not a wall.

**Cursoring (stability):** new pools page by **keyset** on `(importance_score DESC, id)` — the cursor is
the last-emitted key, stored in `feed_state`. Robust as items leave the never-seen set on rating (no
offset drift, no double-show, no skip). Due pool re-queried per window; an in-memory per-open `emitted`
set dedupes across windows within one session.

## 8. Build order

1. Ask the 3 policy questions (§7); lock defaults.
2. `FeedRepository`: add `duePool/newPool/fillerPool` window queries (indexed, content-safe).
3. `feed/FeedScheduler.kt`: the pure composer + `feed_state` (de)serialization.
4. DAO: `feed_state` get/put; due-pool helpers over `fsrs_cards`/`blob_reads`.
5. `FeedViewModel`: route SMART → scheduler, explicit → A2 path; thread state across windows.
6. Unit tests (§4) + instrumented test; `verifyOfflineGuarantees`; on-device smoke.
7. Update `.claude/INDEX.md`, `JOURNAL.md` (Phase 2 tail), commit; **secret-scan before any push**.
```
