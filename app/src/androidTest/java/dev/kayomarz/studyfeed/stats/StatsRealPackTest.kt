package dev.kayomarz.studyfeed.stats

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kayomarz.studyfeed.progress.FsrsCard
import dev.kayomarz.studyfeed.progress.McqAttempt
import dev.kayomarz.studyfeed.progress.Mistake
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import dev.kayomarz.studyfeed.progress.Review
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Cross-DB stats on the REAL pack (A6, rebuilt A7 cycle 3): seed a fresh progress DB with FSRS / reviews /
 * mistakes / MCQ attempts for real pack items, then prove [StatsRepository] aggregates MCQ **coverage +
 * accuracy by subject**, overall accuracy, the forgetting curve, high-importance gaps, activity, the due
 * forecast, and 5★/PYQ core coverage. SKIPS without the fixture (**a skip is not a pass**). SHAPE only.
 */
@RunWith(AndroidJUnit4::class)
class StatsRealPackTest {

    private fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun realPackDb(): File? =
        File(ctx().filesDir, "realpack").listFiles { f -> f.name.startsWith("pack_") && f.name.endsWith(".db") }?.firstOrNull()

    private data class Item(val type: String, val id: Long, val subjectId: Long, val importance: Int)

    /** Grab a few real feed items (with subjects) + one 5★ MCQ, straight from the pack. */
    private fun sampleItems(pack: File): Pair<List<Item>, Item?> {
        val db = SQLiteDatabase.openDatabase(pack.path, null, SQLiteDatabase.OPEN_READONLY)
        val items = ArrayList<Item>()
        var fiveStar: Item? = null
        db.use {
            it.rawQuery("SELECT item_type, id, subject_id, importance FROM feed_items WHERE subject_id IS NOT NULL AND item_type='mcq' ORDER BY importance DESC LIMIT 20", null).use { c ->
                while (c.moveToNext()) {
                    val item = Item(c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3))
                    items += item
                    if (fiveStar == null && item.importance == 5) fiveStar = item
                }
            }
        }
        return items to fiveStar
    }

    @Test fun coverage_accuracy_gaps_curve_and_forecast_aggregate_from_seeded_progress() {
        val pack = realPackDb(); assumeTrue("real pack not pushed — skipping", pack != null)
        val (items, fiveStar) = sampleItems(pack!!)
        assumeTrue("pack has subject-bearing MCQs", items.isNotEmpty())
        assumeTrue("pack has a 5★ MCQ", fiveStar != null)
        val gap: Item = fiveStar!!

        val exam = "A6_STATS_TEST"
        val progressFile = ProgressDatabase.fileFor(ctx(), exam)
        File(progressFile.path).delete()
        val pdb = ProgressDatabase.open(ctx(), exam)
        runBlocking {
            val dao = pdb.dao()
            items.forEachIndexed { i, it ->
                dao.upsertCard(FsrsCard(item_type = it.type, item_id = it.id, stability = 5.0 + i,
                    difficulty = 5.0, due = "2030-01-01T00:00:00Z", last_review = "2026-07-18T00:00:00Z",
                    state = 2, reps = 3, lapses = if (i % 4 == 0) 1 else 0))
                dao.insertReview(Review(item_type = it.type, item_id = it.id,
                    rating = if (i % 4 == 0) 1 else 3, ts = "2026-07-18T00:00:0${i % 10}Z", elapsed_ms = null))
                // Every 4th attempt wrong → known accuracy denominator.
                dao.insertMcqAttempt(McqAttempt(item_id = it.id, subject_id = it.subjectId,
                    correct = if (i % 4 == 0) 0 else 1, ts = "2026-07-18T00:00:0${i % 10}Z"))
            }
            // A 5★ gap the user keeps failing.
            dao.insertMistake(Mistake(item_type = gap.type, item_id = gap.id,
                subject_id = gap.subjectId, chosen_idx = 1, ts = "2026-07-18T00:00:00Z", resolved = 0))
        }
        pdb.close()

        val stats = StatsRepository.open(progressFile, pack)
        assertNotNull(stats)
        try {
            val subjects = stats!!.subjectCoverage()
            assertTrue("coverage has subjects", subjects.isNotEmpty())
            assertTrue("coverage fractions 0..1", subjects.all { it.coverage in 0.0..1.0 })
            assertTrue("done never exceeds total", subjects.all { it.done <= it.total })
            assertTrue("named", subjects.all { it.name.isNotBlank() })
            assertTrue("the seeded done cards register as coverage", subjects.sumOf { it.done } >= 1)

            val totals = stats.mcqTotals()
            assertEquals("every attempt logged", items.size, totals.answered)
            assertTrue("accuracy in 0..1", totals.accuracy!! in 0.0..1.0)

            val gaps = stats.highImportanceGaps()
            assertTrue("the seeded 5★ gap shows up", gaps.any { it.itemId == gap.id })
            assertTrue("gaps are all 5★", gaps.all { it.importance == 5 })

            val curve = stats.forgettingCurve()
            assertEquals("six horizons", 6, curve.size)
            assertEquals("starts at now", 0, curve.first().days)
            assertTrue("retention now is high", curve.first().retention > 0.9)
            assertTrue("retention decays over time", curve.first().retention >= curve.last().retention)
            assertTrue("all retentions in 0..1", curve.all { it.retention in 0.0..1.0 })

            val core = stats.coreCoverage()
            assertTrue("pack has 5★ items", core.star5Total > 0)
            assertTrue("pack has PYQ items", core.pyqTotal > 0)
            assertTrue("core done never exceeds total",
                core.star5Done <= core.star5Total && core.pyqDone <= core.pyqTotal)

            assertTrue("activity recorded", stats.activityCounts("2026-07-01").values.sum() >= 1)
            assertTrue("due buckets populated", stats.dueBuckets().values.sum() >= 1)
            assertTrue("daily accuracy points exist", stats.accuracyDaily("2026-07-01").isNotEmpty())
        } finally {
            stats?.close(); File(progressFile.path).delete()
        }
    }
}
