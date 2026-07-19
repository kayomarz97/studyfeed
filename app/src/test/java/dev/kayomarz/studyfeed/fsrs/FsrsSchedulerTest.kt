package dev.kayomarz.studyfeed.fsrs

import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * BUILD_PROMPT §8 A0: "Pin the version; one unit test scheduling Again/Hard/Good/Easy."
 *
 * This is a JVM test — no device, no emulator. It exists to prove three things before any UI is
 * built on top of FSRS:
 *  1. the pinned artifact resolves and its API is what the playbook says it is;
 *  2. Kotlin↔Java interop works, including the record-accessor gotcha ([CardAndReviewLog.card] is
 *     a method, not a property);
 *  3. our FSRS-6 produces the SAME numbers as upstream's own test — see [matchesUpstreamOracle].
 *
 * Fuzzing is disabled throughout. FSRS deliberately randomises intervals slightly in production to
 * stop cards clumping; that is right for studying and fatal for a test.
 */
class FsrsSchedulerTest {

    private val scheduler: Scheduler = Scheduler.builder()
        .enableFuzzing(false)
        .build()

    /** A fixed instant — `Instant.now()` would make this test a coin flip on interval boundaries. */
    private val reviewedAt: Instant = Instant.parse("2026-07-16T09:00:00Z")

    @Test
    fun `each rating schedules a fresh card, and harder ratings come back sooner`() {
        val dueByRating = listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY)
            .associateWith { rating ->
                val result = scheduler.reviewCard(Card.builder().build(), rating, reviewedAt)
                // Record accessor: card() as a METHOD. Kotlin's property sugar only covers
                // JavaBean getX(), not record components — see PLAYBOOK.
                val reviewed = result.card()

                assertNotNull("$rating must produce a due date", reviewed.due)
                assertTrue(
                    "$rating must schedule the card into the future, not the past",
                    !reviewed.due.isBefore(reviewedAt),
                )
                assertNotNull("$rating must produce a review log", result.reviewLog())
                reviewed.due
            }

        // The ordering IS the algorithm's contract: the worse you did, the sooner you see it again.
        // A regression here (e.g. a bad weight vector) would silently wreck study scheduling while
        // every "does it return a date" assertion still passed.
        assertTrue(
            "AGAIN must not be scheduled later than HARD (got ${dueByRating[Rating.AGAIN]} vs ${dueByRating[Rating.HARD]})",
            !dueByRating.getValue(Rating.AGAIN).isAfter(dueByRating.getValue(Rating.HARD)),
        )
        assertTrue(
            "HARD must not be scheduled later than GOOD",
            !dueByRating.getValue(Rating.HARD).isAfter(dueByRating.getValue(Rating.GOOD)),
        )
        assertTrue(
            "GOOD must not be scheduled later than EASY",
            !dueByRating.getValue(Rating.GOOD).isAfter(dueByRating.getValue(Rating.EASY)),
        )
        assertTrue(
            "EASY must graduate a fresh card out of the same-day learning steps",
            Duration.between(reviewedAt, dueByRating.getValue(Rating.EASY)).toDays() >= 1,
        )
    }

    @Test
    fun `a fresh card starts in LEARNING and EASY graduates it to REVIEW`() {
        val fresh = Card.builder().build()
        assertEquals("java-fsrs has no NEW state; fresh cards start LEARNING", State.LEARNING, fresh.state)

        val graduated = scheduler.reviewCard(fresh, Rating.EASY, reviewedAt).card()
        assertEquals(State.REVIEW, graduated.state)
        assertNotNull("a reviewed card must carry stability", graduated.stability)
        assertNotNull("a reviewed card must carry difficulty", graduated.difficulty)
    }

    /**
     * Upstream's own regression oracle (`FSRSTest.testReviewCard` in open-spaced-repetition/java-fsrs,
     * recorded in PLAYBOOK): a fresh card reviewed GOOD×6, AGAIN×2, GOOD×5 with fuzzing off yields
     * this exact interval-in-days sequence.
     *
     * This is the test that would actually catch a wrong FSRS-6 weight vector or a silently changed
     * algorithm on a version bump — the kind of break that shows up as "my scheduling feels off"
     * six weeks before an exam and is undebuggable by then.
     */
    @Test
    fun matchesUpstreamOracle() {
        val ratings = List(6) { Rating.GOOD } + List(2) { Rating.AGAIN } + List(5) { Rating.GOOD }
        val expectedIntervalDays = listOf(0L, 4L, 14L, 45L, 135L, 372L, 0L, 0L, 2L, 5L, 10L, 20L, 40L)

        var card = Card.builder().build()
        var now = reviewedAt
        val actualIntervalDays = ratings.map { rating ->
            card = scheduler.reviewCard(card, rating, now).card()
            val intervalDays = Duration.between(now, card.due).toDays()
            now = card.due // next review happens exactly when it falls due
            intervalDays
        }

        assertEquals(expectedIntervalDays, actualIntervalDays)
    }
}
