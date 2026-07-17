package dev.kayomarz.studyfeed.feed

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kayomarz.studyfeed.progress.FsrsCard
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Proves the A3 ordering-engine **SQL pools** ([FeedRepository] `duePool`/`newMcqs`/`newOthers`/
 * `remainingNewMcqs`/`blobRef`/`loadCards`) against the REAL 32,957-item pack on a real Android image —
 * the layer the JVM [FeedSchedulerTest] (in-memory pools) cannot reach.
 *
 * SKIPS when the pack fixture isn't pushed (see `RealPackTest`'s KDoc; **a skip is not a pass** — check
 * the skipped count). Assertions are about SHAPE, never pack text (copyright).
 */
@RunWith(AndroidJUnit4::class)
class FeedSchedulerRealPackTest {

    private val exam = "MRCP_PART1"
    private fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun realPackDb(): File? =
        File(ctx().filesDir, "realpack").listFiles { f -> f.name.startsWith("pack_") && f.name.endsWith(".db") }?.firstOrNull()

    private fun openRepo(): FeedRepository? {
        val db = realPackDb() ?: return null
        ProgressDatabase.ensureExists(ctx(), exam)
        return FeedRepository.open(ProgressDatabase.fileFor(ctx(), exam), db)
    }

    private fun pools(r: FeedRepository) = object : FeedScheduler.Pools {
        override fun due(nowIso: String, exclude: Set<Long>, limit: Int) = r.duePool(nowIso, exclude, limit)
        override fun newMcqs(afterScore: Double?, afterId: Long?, limit: Int) = r.newMcqs(afterScore, afterId, limit)
        override fun newOthers(afterScore: Double?, afterId: Long?, limit: Int) = r.newOthers(afterScore, afterId, limit)
        override fun filler(nowIso: String, exclude: Set<Long>, limit: Int) = r.fillerPool(nowIso, exclude, limit)
        override fun remainingNewMcqs() = r.remainingNewMcqs()
        override fun blobRef(blobId: Long) = r.blobRef(blobId)
    }

    private class MemStore(var s: FeedScheduler.FeedState = FeedScheduler.FeedState()) : FeedScheduler.StateStore {
        override fun load() = s
        override fun save(state: FeedScheduler.FeedState) { s = state }
    }

    @Test fun the_new_mcq_pool_is_mcq_only_importance_first_and_keyset_paginates_cleanly() {
        val repo = openRepo(); assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { r ->
            assertTrue("there are new MCQs to pace", r.remainingNewMcqs() in 1..14455)

            val page1 = r.newMcqs(null, null, 100)
            assertEquals("full first page", 100, page1.size)
            assertTrue("MCQs only", page1.all { it.itemType == "mcq" })
            assertTrue("importance non-increasing", page1.zipWithNext().all { (a, b) -> a.importanceScore >= b.importanceScore })

            val last = page1.last()
            val page2 = r.newMcqs(last.importanceScore, last.itemId, 100)
            val ids1 = page1.map { it.itemId }.toSet()
            assertTrue("keyset page 2 does not repeat page 1", page2.none { it.itemId in ids1 })
            assertTrue("page 2 continues at or below the cursor importance",
                page2.all { it.importanceScore <= last.importanceScore })
        }
    }

    @Test fun the_new_other_pool_excludes_mcqs() {
        val repo = openRepo(); assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { r ->
            val others = r.newOthers(null, null, 100)
            assertTrue("some non-MCQ new items exist", others.isNotEmpty())
            assertTrue("never an MCQ in the 'other' pool", others.none { it.itemType == "mcq" })
        }
    }

    @Test fun a_due_card_surfaces_in_the_due_pool_and_the_engine_leads_with_it() {
        // Acceptance 6 at the SQL+engine level: seed one MCQ as overdue → it must appear in the due pool
        // and the due-led engine must place it early.
        val repo = openRepo(); assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { r ->
            val victim = r.newMcqs(null, null, 1).first()   // a real, deterministic feed item
            val db = ProgressDatabase.open(ctx(), exam)
            runBlocking {
                db.dao().upsertCard(FsrsCard(
                    item_type = victim.itemType, item_id = victim.itemId, stability = 1.0, difficulty = 5.0,
                    due = "2000-01-01T00:00:00Z", last_review = "1999-12-01T00:00:00Z", state = 2, reps = 1, lapses = 1))
            }
            db.close()

            val now = Instant.now().toString()
            val due = r.duePool(now, emptySet(), 50)
            assertTrue("the overdue card is in the due pool", due.any { it.itemId == victim.itemId })

            val sch = FeedScheduler(pools(r), MemStore())
            val refs = sch.window(40, now, LocalDate.now().toString(), 56)
            val pos = refs.indexOfFirst { it.itemId == victim.itemId }
            assertTrue("the due card is emitted", pos >= 0)
            assertTrue("and it leads (due-first), not buried", pos < 15)
        }
    }

    @Test fun a_full_engine_window_loads_real_cards_and_holds_variety() {
        val repo = openRepo(); assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { r ->
            val sch = FeedScheduler(pools(r), MemStore())
            val refs = sch.window(60, Instant.now().toString(), LocalDate.now().toString(), 56)
            assertEquals("a full window", 60, refs.size)

            val cards = r.loadCards(refs)
            assertEquals("every ref loads a real payload", refs.size, cards.size)
            assertTrue("payloads carry importance 1..5", cards.all { it.importance in 1..5 })

            // Variety: with all five types richly available at day 1, never 3 of a type in a row.
            val types = refs.map { it.itemType }
            val threeRun = (0..types.size - 3).any { types[it] == types[it + 1] && types[it + 1] == types[it + 2] }
            assertTrue("no type appears 3× consecutively", !threeRun)
        }
    }

    @Test fun blobRef_resolves_a_real_passage_for_the_delayed_quiz_back() {
        val repo = openRepo(); assumeTrue("real pack not pushed — skipping", repo != null)
        repo!!.use { r ->
            // Find a blob feed item id, then resolve it back through the engine's blobRef path.
            var blobId: Long? = null
            var offset = 0
            while (blobId == null && offset < 32957) {
                r.page(offset, 500).filterIsInstance<FeedCard.Blob>().firstOrNull()?.let { blobId = it.itemId }
                offset += 500
            }
            assertNotNull("the pack has blob passages", blobId)
            val ref = r.blobRef(blobId!!)
            assertNotNull("blobRef resolves the passage", ref)
            assertEquals("blob", ref!!.itemType)
            assertEquals(blobId, ref.itemId)
        }
    }
}
