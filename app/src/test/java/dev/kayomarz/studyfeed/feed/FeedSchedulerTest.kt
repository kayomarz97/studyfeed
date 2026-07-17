package dev.kayomarz.studyfeed.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The A3 ordering engine, proven on the JVM with in-memory pools (no device, no clock, deterministic).
 *
 * These are the acceptance checks for §8 A3's rules — variety, due-led blend, importance-first new,
 * the deadline-paced daily drip, the keyset cursor (no repeats across windows), and the delayed blob
 * quiz-back. Nothing here touches SQLite or Android, so it runs in `testDebugUnitTest`.
 */
class FeedSchedulerTest {

    private val NOW = "2026-07-17T12:00:00Z"
    private val TODAY = "2026-07-17"

    private fun ref(type: String, id: Long, imp: Int, score: Double) =
        FeedScheduler.Ref(type, itemId = id, refId = id, importance = imp, importanceScore = score, subjectId = null)

    /** Pools sorted the way the real SQL sorts them; keyset over (importanceScore desc, itemId asc). */
    private class FakePools(
        val dueItems: List<FeedScheduler.Ref> = emptyList(),
        val mcqItems: List<FeedScheduler.Ref> = emptyList(),
        val otherItems: List<FeedScheduler.Ref> = emptyList(),
        val fillerItems: List<FeedScheduler.Ref> = emptyList(),
        val blobs: Map<Long, FeedScheduler.Ref> = emptyMap(),
        val remainingMcqs: Int = mcqItems.size,
    ) : FeedScheduler.Pools {
        // Each pool stamps `source`, exactly as the real FeedRepository.refFrom does per query.
        override fun due(nowIso: String, exclude: Set<Long>, limit: Int) =
            dueItems.filter { it.itemId !in exclude }.take(limit).map { it.copy(source = FeedScheduler.Source.DUE) }
        override fun newMcqs(afterScore: Double?, afterId: Long?, limit: Int) =
            keyset(mcqItems, afterScore, afterId).take(limit).map { it.copy(source = FeedScheduler.Source.NEW_MCQ) }
        override fun newOthers(afterScore: Double?, afterId: Long?, limit: Int) =
            keyset(otherItems, afterScore, afterId).take(limit).map { it.copy(source = FeedScheduler.Source.NEW_OTHER) }
        override fun filler(nowIso: String, exclude: Set<Long>, limit: Int) =
            fillerItems.filter { it.itemId !in exclude }.take(limit).map { it.copy(source = FeedScheduler.Source.FILLER) }
        override fun remainingNewMcqs() = remainingMcqs
        override fun blobRef(blobId: Long) = blobs[blobId]
        private fun keyset(list: List<FeedScheduler.Ref>, s: Double?, id: Long?): List<FeedScheduler.Ref> {
            if (s == null || id == null) return list
            return list.filter { it.importanceScore < s || (it.importanceScore == s && it.itemId > id) }
        }
    }

    private class FakeStore(var s: FeedScheduler.FeedState = FeedScheduler.FeedState()) : FeedScheduler.StateStore {
        override fun load() = s
        override fun save(state: FeedScheduler.FeedState) { s = state }
    }

    private fun noThreeInARow(cards: List<FeedScheduler.Ref>): Boolean {
        for (i in 0..cards.size - 3) {
            if (cards[i].itemType == cards[i + 1].itemType && cards[i + 1].itemType == cards[i + 2].itemType) return false
        }
        return true
    }

    // ---- variety -----------------------------------------------------------------------------------

    @Test fun never_more_than_two_of_a_type_in_a_row_when_diversity_allows() {
        // Rich, diverse pools + a loose deadline (days=1 ⇒ big budget) so variety is always satisfiable.
        val due = (1L..40L).map { ref(if (it % 2 == 0L) "mcq" else "flashcard", it, 5, 0.9) }
        val mcqs = (100L..180L).map { ref("mcq", it, 4, 0.8 - it * 0.001) }
        val others = (300L..360L).map {
            ref(listOf("flashcard", "reel", "blob", "interlink")[(it % 4).toInt()], it, 3, 0.7 - it * 0.001)
        }
        val sch = FeedScheduler(FakePools(due, mcqs, others, remainingMcqs = 100), FakeStore())
        val out = sch.window(120, NOW, TODAY, daysUntilDeadline = 1)
        assertTrue("engine produced a full window", out.size >= 100)
        assertTrue("no type appears 3× consecutively", noThreeInARow(out))
    }

    // ---- due-led blend -----------------------------------------------------------------------------

    @Test fun the_blend_is_due_led_but_still_mixes_in_new() {
        val due = (1L..60L).map { ref(if (it % 3 == 0L) "flashcard" else "mcq", it, 5, 0.9) }
        val mcqs = (100L..160L).map { ref("mcq", it, 4, 0.8 - it * 0.001) }
        val others = (300L..330L).map { ref("reel", it, 3, 0.6) }
        val sch = FeedScheduler(FakePools(due, mcqs, others, remainingMcqs = 100), FakeStore())
        val out = sch.window(40, NOW, TODAY, daysUntilDeadline = 1)

        val dueCount = out.count { it.source == FeedScheduler.Source.DUE }
        val newCount = out.count { it.source == FeedScheduler.Source.NEW_MCQ || it.source == FeedScheduler.Source.NEW_OTHER }
        assertTrue("some new material is woven in", newCount > 0)
        assertTrue("but reviews lead the blend ($dueCount due vs $newCount new)", dueCount > newCount)
    }

    // ---- importance-first new + keyset (no repeats) -------------------------------------------------

    @Test fun new_items_come_out_importance_first_and_never_repeat_across_windows() {
        // Only new MCQs: importance strictly descending by id. (Variety must relax since it's all mcq —
        // that's fine; we're asserting importance order + no repeats, not variety here.)
        val mcqs = (1L..50L).map { ref("mcq", it, 5, 1.0 - it * 0.01) }
        val store = FakeStore()
        val sch = FeedScheduler(FakePools(mcqItems = mcqs, remainingMcqs = 50), store)

        val w1 = sch.window(20, NOW, TODAY, daysUntilDeadline = 1)
        // A second window (same session) must continue past the cursor, not restart.
        val w2 = sch.window(20, NOW, TODAY, daysUntilDeadline = 1)

        val ids1 = w1.map { it.itemId }
        val ids2 = w2.map { it.itemId }
        assertTrue("importance non-increasing in window 1",
            w1.zipWithNext().all { (a, b) -> a.importanceScore >= b.importanceScore })
        assertTrue("no id repeats across the two windows", (ids1 + ids2).toSet().size == ids1.size + ids2.size)
        assertTrue("window 2 continues to lower importance than window 1's tail",
            ids2.isEmpty() || w2.first().importanceScore <= w1.last().importanceScore)
    }

    // ---- deadline-paced daily drip -----------------------------------------------------------------

    @Test fun the_daily_new_mcq_budget_caps_introductions_and_a_hit_cap_ends_the_new_feed() {
        // 100 remaining MCQs, 10 days ⇒ budget = 10/day. No due, no others, no filler.
        val mcqs = (1L..100L).map { ref("mcq", it, 5, 1.0 - it * 0.001) }
        val sch = FeedScheduler(FakePools(mcqItems = mcqs, remainingMcqs = 100), FakeStore())
        val out = sch.window(40, NOW, TODAY, daysUntilDeadline = 10)
        assertEquals("only the daily budget of new MCQs is introduced, then the feed stops", 10, out.size)
        assertTrue(out.all { it.source == FeedScheduler.Source.NEW_MCQ })
    }

    @Test fun falling_behind_raises_the_budget_to_stay_on_pace() {
        val mcqs = (1L..100L).map { ref("mcq", it, 5, 1.0 - it * 0.001) }
        // Same 100 remaining, but only 4 days left ⇒ budget = 25/day (ceil(100/4)).
        val sch = FeedScheduler(FakePools(mcqItems = mcqs, remainingMcqs = 100), FakeStore())
        val out = sch.window(60, NOW, TODAY, daysUntilDeadline = 4)
        assertEquals("budget rises as the deadline nears", 25, out.size)
    }

    @Test fun the_drip_counter_rolls_over_on_a_new_day() {
        val mcqs = (1L..100L).map { ref("mcq", it, 5, 1.0 - it * 0.001) }
        val store = FakeStore()
        val sch = FeedScheduler(FakePools(mcqItems = mcqs, remainingMcqs = 100), store)
        sch.window(40, NOW, TODAY, daysUntilDeadline = 10)               // spends 10 today
        assertEquals(10, store.s.newMcqToday)
        // A fresh scheduler (new app-open) on the NEXT day resets the daily counter.
        val sch2 = FeedScheduler(FakePools(mcqItems = mcqs, remainingMcqs = 90), store)
        val next = sch2.window(40, "2026-07-18T09:00:00Z", "2026-07-18", daysUntilDeadline = 9)
        assertEquals("new day resets the counter", 10, next.size)
    }

    // ---- delayed blob quiz-back --------------------------------------------------------------------

    @Test fun a_read_blobs_quiz_back_lands_a_few_cards_later() {
        val mcqs = (1L..40L).map { ref("mcq", it, 4, 0.8 - it * 0.001) }
        val others = (300L..330L).map { ref("flashcard", it, 3, 0.6) }
        val blobId = 777L
        val blobs = mapOf(blobId to ref("blob", blobId, 5, 0.95))
        val store = FakeStore()
        val sch = FeedScheduler(FakePools(mcqItems = mcqs, otherItems = others, blobs = blobs, remainingMcqs = 100), store)

        sch.window(4, NOW, TODAY, daysUntilDeadline = 1)     // globalIndex advances to ~4
        sch.scheduleBlobQuiz(blobId)                         // land ≈ index+delay
        val after = sch.window(20, NOW, TODAY, daysUntilDeadline = 1)

        val quiz = after.firstOrNull { it.openAtQuiz }
        assertTrue("the blob quiz-back re-surfaces within a few cards", quiz != null)
        assertEquals(blobId, quiz!!.itemId)
        assertEquals(FeedScheduler.Source.BLOB_QUIZ, quiz.source)
    }

    // ---- never dead-ends / graceful empty ----------------------------------------------------------

    @Test fun filler_keeps_the_feed_alive_when_due_and_new_are_dry() {
        val filler = (1L..30L).map { ref(if (it % 2 == 0L) "mcq" else "reel", it, 2, 0.4) }
        val sch = FeedScheduler(FakePools(fillerItems = filler, remainingMcqs = 0), FakeStore())
        val out = sch.window(20, NOW, TODAY, daysUntilDeadline = 56)
        assertTrue("filler fills the window when nothing is due and no new remains", out.size >= 10)
        assertTrue(out.all { it.source == FeedScheduler.Source.FILLER })
    }

    @Test fun fully_exhausted_pools_return_an_empty_window_without_crashing() {
        val sch = FeedScheduler(FakePools(remainingMcqs = 0), FakeStore())
        val out = sch.window(20, NOW, TODAY, daysUntilDeadline = 56)
        assertTrue("nothing to show → empty, not a crash", out.isEmpty())
    }

    // ---- state persists (survives restart / export-import) -----------------------------------------

    @Test fun the_engine_state_is_saved_after_every_window() {
        val mcqs = (1L..50L).map { ref("mcq", it, 5, 1.0 - it * 0.01) }
        val store = FakeStore()
        val sch = FeedScheduler(FakePools(mcqItems = mcqs, remainingMcqs = 50), store)
        sch.window(15, NOW, TODAY, daysUntilDeadline = 1)
        assertEquals("dripDate recorded", TODAY, store.s.dripDate)
        assertTrue("globalIndex advanced", store.s.globalIndex >= 15)
        assertFalse("a keyset cursor was set", store.s.mcqCursorId == null)
    }
}
