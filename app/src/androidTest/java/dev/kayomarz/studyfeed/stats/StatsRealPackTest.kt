package dev.kayomarz.studyfeed.stats

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kayomarz.studyfeed.progress.FsrsCard
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
 * A6 cross-DB stats on the REAL pack: seed a fresh progress DB with FSRS/reviews/mistakes for real pack
 * items, then prove [StatsRepository] aggregates per-subject mastery, the forgetting curve, and the
 * high-importance gaps. SKIPS without the fixture (**a skip is not a pass**). SHAPE only, no pack text.
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

    @Test fun mastery_gaps_and_the_forgetting_curve_aggregate_from_seeded_progress() {
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
            }
            // A 5★ gap the user keeps failing.
            dao.insertMistake(Mistake(item_type = gap.type, item_id = gap.id,
                subject_id = gap.subjectId, chosen_idx = 1, ts = "2026-07-18T00:00:00Z", resolved = 0))
        }
        pdb.close()

        val stats = StatsRepository.open(progressFile, pack)
        assertNotNull(stats)
        try {
            val subjects = stats!!.subjectMastery()
            assertTrue("mastery has subjects", subjects.isNotEmpty())
            assertTrue("every mastery is 0..1", subjects.all { it.mastery in 0.0..1.0 })
            assertTrue("named", subjects.all { it.name.isNotBlank() })

            val gaps = stats.highImportanceGaps()
            assertTrue("the seeded 5★ gap shows up", gaps.any { it.itemId == gap.id })
            assertTrue("gaps are all 5★", gaps.all { it.importance == 5 })

            val curve = stats.forgettingCurve()
            assertEquals("six horizons", 6, curve.size)
            assertEquals("starts at now", 0, curve.first().days)
            assertTrue("retention now is high", curve.first().retention > 0.9)
            assertTrue("retention decays over time", curve.first().retention >= curve.last().retention)
            assertTrue("all retentions in 0..1", curve.all { it.retention in 0.0..1.0 })
        } finally {
            stats?.close(); File(progressFile.path).delete()
        }
    }
}
