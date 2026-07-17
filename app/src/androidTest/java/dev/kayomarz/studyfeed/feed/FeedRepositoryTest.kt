package dev.kayomarz.studyfeed.feed

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reads the REAL pack through [FeedRepository] on a real Android image.
 *
 * SKIPS when the pack isn't present (gitignored — see `RealPackTest`'s KDoc for the push procedure, and
 * remember: **a skip is not a pass**, check the skipped count). Assertions are about the SHAPE of the
 * loaded cards, never their content — no pack text is echoed (copyright rule).
 */
@RunWith(AndroidJUnit4::class)
class FeedRepositoryTest {

    private val exam = "MRCP_PART1"

    private fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun realPackDb(): File? {
        val dir = File(ctx().filesDir, "realpack")
        return dir.listFiles { f -> f.name.startsWith("pack_") && f.name.endsWith(".db") }?.firstOrNull()
    }

    private fun openRepo(): FeedRepository? {
        val packDb = realPackDb() ?: return null
        ProgressDatabase.ensureExists(ctx(), exam)
        return FeedRepository.open(ProgressDatabase.fileFor(ctx(), exam), packDb)
    }

    @Test
    fun the_feed_reports_its_full_size() {
        val repo = openRepo()
        assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { assertEquals("the whole pack is playable", 32957, it.size()) }
    }

    @Test
    fun the_first_page_loads_every_card_fully_and_importance_first() {
        val repo = openRepo()
        assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { r ->
            val page = r.page(offset = 0, limit = 60)
            assertEquals(60, page.size)

            // importance-first: page 1 of a 5★-topped order must be all high importance.
            assertTrue("first card is top importance", page.first().importance >= 4)
            assertTrue("non-increasing importance down the page",
                page.zipWithNext().all { (a, b) -> a.importance >= b.importance })

            // Every card must carry the shared chrome §8 A2 puts on all of them.
            page.forEach { card ->
                assertTrue("importance in 1..5", card.importance in 1..5)
                assertTrue("importance_score in 0..1", card.importanceScore in 0.0..1.0)
                assertNotNull("item type present", card.itemType)
            }
        }
    }

    @Test
    fun each_of_the_five_types_loads_with_its_payload() {
        val repo = openRepo()
        assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { r ->
            // Scan enough of the feed to encounter every type (importance-first mixes them).
            val seen = mutableMapOf<String, FeedCard>()
            var offset = 0
            while (seen.size < 5 && offset < 32957) {
                r.page(offset, 500).forEach { c -> seen.putIfAbsent(c.itemType, c) }
                offset += 500
            }
            assertEquals("all five types appear in the feed",
                setOf("mcq", "flashcard", "reel", "blob", "interlink"), seen.keys)

            (seen["mcq"] as FeedCard.Mcq).let { m ->
                assertTrue("MCQ has a stem", m.stemMd.isNotBlank())
                assertTrue("MCQ has 2..5 options", m.options.size in 2..5)
                assertTrue("correct index is within the options", m.correctIdx in m.options.indices)
                assertTrue("MCQ has an explanation", m.explanationMd.isNotBlank())
                assertEquals("one why-wrong slot per option", m.options.size, m.perOptionWhyWrong.size)
            }
            (seen["flashcard"] as FeedCard.Flashcard).let {
                assertTrue(it.frontMd.isNotBlank()); assertTrue(it.backMd.isNotBlank())
            }
            (seen["reel"] as FeedCard.Reel).let { assertTrue(it.hookMd.isNotBlank()) }
            (seen["blob"] as FeedCard.Blob).let { assertTrue(it.bodyMd.isNotBlank()) }
            (seen["interlink"] as FeedCard.Interlink).let {
                assertTrue(it.questionMd.isNotBlank()); assertTrue(it.answerMd.isNotBlank())
            }
        }
    }

    @Test
    fun a_pyq_mcq_carries_its_years_and_the_why_sheet_has_signals() {
        val repo = openRepo()
        assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { r ->
            var offset = 0
            var pyq: FeedCard.Mcq? = null
            var withSignals: FeedCard? = null
            while ((pyq == null || withSignals == null) && offset < 32957) {
                for (c in r.page(offset, 500)) {
                    if (pyq == null && c is FeedCard.Mcq && c.pyqYears.isNotEmpty()) pyq = c
                    if (withSignals == null && c.importanceSignals?.ranked()?.isNotEmpty() == true) withSignals = c
                }
                offset += 500
            }
            assertNotNull("some MCQ must carry PYQ years (4,306 are PYQs)", pyq)
            assertTrue("PYQ years look like years", pyq!!.pyqYears.all { it in 1990..2030 })
            assertNotNull("some card must expose importance signals for the why-sheet", withSignals)
            assertTrue(withSignals!!.importanceSignals!!.ranked().isNotEmpty())
        }
    }

    @Test
    fun the_five_star_filter_and_pyq_filter_narrow_the_feed() {
        // Acceptance 4: "5★ only" and "PYQ only" filters work.
        val repo = openRepo()
        assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { r ->
            val fiveStar = r.count(dev.kayomarz.studyfeed.feed.FeedQuery(
                filter = dev.kayomarz.studyfeed.feed.FeedFilter(importanceExact = 5)))
            val pyq = r.count(dev.kayomarz.studyfeed.feed.FeedQuery(
                filter = dev.kayomarz.studyfeed.feed.FeedFilter(pyqOnly = true)))
            val all = r.size()

            assertEquals("5★ filter selects exactly the 5★ items", 1046, fiveStar)
            assertEquals("PYQ filter selects exactly the PYQ items", 4306, pyq)
            assertTrue("both are proper subsets of the whole deck", fiveStar < all && pyq < all)

            // The page must actually honour the filter, not just the count.
            r.page(dev.kayomarz.studyfeed.feed.FeedQuery(
                filter = dev.kayomarz.studyfeed.feed.FeedFilter(importanceExact = 5)), 0, 50)
                .forEach { assertEquals("every card on a 5★ page is 5★", 5, it.importance) }
        }
    }

    @Test
    fun priority_sort_differs_from_importance_desc_once_progress_exists() {
        // Acceptance 4: Priority sort must DIFFER from Importance ↓ — the proof that weakness is folded
        // in locally. With no progress the two are near-identical; after marking some items well-known
        // (high stability, no lapses) they should sink under Priority relative to raw importance.
        val repo = openRepo()
        assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { r ->
            val byImportance = r.page(dev.kayomarz.studyfeed.feed.FeedQuery(
                sort = dev.kayomarz.studyfeed.feed.FeedQuery.Sort.IMPORTANCE_DESC), 0, 40).map { it.itemType to it.itemId }

            // Make the current top-of-importance items "well known": high stability, zero lapses.
            val db = ProgressDatabase.open(ctx(), exam)
            kotlinx.coroutines.runBlocking {
                byImportance.take(15).forEach { (type, id) ->
                    db.dao().upsertCard(dev.kayomarz.studyfeed.progress.FsrsCard(
                        item_type = type, item_id = id, stability = 500.0, difficulty = 1.0,
                        due = "2030-01-01", last_review = "2026-07-17", state = 2, reps = 9, lapses = 0))
                }
            }
            db.close()

            val byPriority = r.page(dev.kayomarz.studyfeed.feed.FeedQuery(
                sort = dev.kayomarz.studyfeed.feed.FeedQuery.Sort.PRIORITY), 0, 40).map { it.itemType to it.itemId }

            assertTrue("Priority order must differ from Importance ↓ once weakness is folded in",
                byImportance != byPriority)
        }
    }

    @Test
    fun blob_cards_carry_their_quiz_back() {
        // §8 A2 / Acceptance 7: a passage has a quiz-back. The pack ships 2,997 blob_quizzes for 2,997
        // blobs, so every blob card should carry one. Assert SHAPE, never content (copyright).
        val repo = openRepo()
        assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { r ->
            var offset = 0
            var blob: FeedCard.Blob? = null
            while (blob == null && offset < 32957) {
                blob = r.page(offset, 500).filterIsInstance<FeedCard.Blob>().firstOrNull()
                offset += 500
            }
            assertNotNull("the feed must contain blob passages", blob)
            val q = blob!!.quiz
            assertNotNull("a blob must carry its quiz-back", q)
            assertTrue("the quiz has a question", q!!.questionMd.isNotBlank())
            assertTrue("the quiz has 2..4 options", q.options.size in 2..4)
            assertTrue("the correct index is within the options", q.correctIdx in q.options.indices)
        }
    }

    @Test
    fun figures_that_are_reachable_resolve_to_real_files_on_disk() {
        val repo = openRepo()
        assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { r ->
            // ~564 cards carry a figure. Scan a chunk; any figure found must point at a file that exists
            // (figureById already verifies isFile, so a non-null Figure means the PNG is really there).
            var offset = 0
            var found = 0
            while (found == 0 && offset < 20000) {
                r.page(offset, 1000).forEach { c ->
                    val fig = when (c) {
                        is FeedCard.Flashcard -> c.figure
                        is FeedCard.Reel -> c.figure
                        is FeedCard.Mcq -> c.figure
                        else -> null
                    }
                    if (fig != null) { found++; assertTrue("figure path exists", File(fig.path).isFile) }
                }
                offset += 1000
            }
            assertTrue("at least one reachable figure resolved to a real file", found > 0)
        }
    }
}
