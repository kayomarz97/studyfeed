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
