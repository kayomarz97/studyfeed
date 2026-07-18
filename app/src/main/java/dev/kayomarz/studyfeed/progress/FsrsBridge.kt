package dev.kayomarz.studyfeed.progress

import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.State
import java.time.Instant

/**
 * Maps a user rating to an updated [FsrsCard] row, using java-fsrs (BUILD_PROMPT §8 A2; FSRS-6 pinned at
 * A0, oracle-tested in `FsrsSchedulerTest`).
 *
 * ## The schema constraint this works within (and its one documented limitation)
 *
 * `spec/SPEC.md` §2's `fsrs_cards` stores exactly: `stability, difficulty, due, last_review, state,
 * reps, lapses`. The schema is a contract with the user's own historical data (D1) — this bridge maps
 * onto those columns and **must not invent new ones** without a `schema_version` bump.
 *
 * Two consequences of that fixed schema, both handled here:
 *  - **java-fsrs does not expose `reps`/`lapses` on its `Card`** — so we maintain them: `reps` counts
 *    reviews; `lapses` increments when a card that had graduated (REVIEW/RELEARNING) is rated AGAIN.
 *  - **The schema has no `step` column**, and java-fsrs's `Card.getStep()` tracks the learning-step
 *    index. It is therefore **not persisted**. In practice this is negligible: a card still in LEARNING
 *    is due within minutes, so on app restart it is due immediately either way — losing the step index
 *    at most costs one extra review before it graduates. Flagged, not silently dropped. If it ever
 *    matters, it is a `schema_version` decision, not a quiet column add.
 */
class FsrsBridge(
    private val scheduler: Scheduler = Scheduler.builder().enableFuzzing(true).build(),
) {

    /**
     * Apply [rating] to an item's current state (null = never studied) and return the row to persist.
     *
     * @param now the review instant. Passed in (not `Instant.now()` internally) so this stays
     *   deterministically testable — the same trick the A0 oracle test uses.
     */
    fun review(current: FsrsCard?, itemType: String, itemId: Long, rating: Rating, now: Instant): FsrsCard {
        val card = current?.let { restore(it) } ?: Card.builder().build()
        val wasGraduated = card.state == State.REVIEW || card.state == State.RELEARNING

        val updated = scheduler.reviewCard(card, rating, now).card()

        val priorReps = current?.reps ?: 0
        val priorLapses = current?.lapses ?: 0
        val lapsed = wasGraduated && rating == Rating.AGAIN

        return FsrsCard(
            item_type = itemType,
            item_id = itemId,
            stability = updated.stability,
            difficulty = updated.difficulty,
            due = updated.due?.toString(),
            last_review = now.toString(),
            state = stateToInt(updated.state),
            reps = priorReps + 1,
            lapses = priorLapses + if (lapsed) 1 else 0,
        )
    }

    /** Rebuild a java-fsrs [Card] from a stored row so the scheduler can advance it. */
    private fun restore(row: FsrsCard): Card = Card.builder().build().apply {
        stability = row.stability
        difficulty = row.difficulty
        row.due?.let { due = runCatching { Instant.parse(it) }.getOrNull() }
        row.last_review?.let { lastReview = runCatching { Instant.parse(it) }.getOrNull() }
        state = intToState(row.state)
        // step is intentionally not restored — see the class KDoc.
    }

    private companion object {
        // LEARNING=1, REVIEW=2, RELEARNING=3. java-fsrs has no NEW state (fresh cards start LEARNING).
        fun stateToInt(s: State?): Int = when (s) {
            State.LEARNING -> 1
            State.REVIEW -> 2
            State.RELEARNING -> 3
            null -> 1
        }

        fun intToState(i: Int?): State = when (i) {
            2 -> State.REVIEW
            3 -> State.RELEARNING
            else -> State.LEARNING
        }
    }
}
