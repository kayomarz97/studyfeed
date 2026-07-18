package dev.kayomarz.studyfeed.stats

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.Closeable
import java.io.File
import kotlin.math.pow

/**
 * Cross-DB analytics for the Progress screen (A6, rebuilt data-rich in A7 cycle 3). Coverage, accuracy,
 * activity, the forgetting curve and the due forecast need the user's own progress joined to each item's
 * subject/importance/PYQ (pack), so this opens progress READ-ONLY with the pack `ATTACH`ed — the same
 * seam as [dev.kayomarz.studyfeed.feed.FeedRepository] (per-connection ATTACH, never Room's pool; D4).
 * Pure reads; Room still owns every write.
 *
 * **Subject attribution reality (measured on the real pack):** only MCQs carry a subject, and only ~57%
 * of them. Flashcards/reels/passages/interlinks are subject-less in this pack. So "coverage & accuracy
 * by subject" is measured on **MCQs**, with a clearly-labelled "Unattributed" bucket for subject-less
 * questions. Other types are counted by TYPE on Home, never mis-attributed to a subject.
 */
class StatsRepository private constructor(
    private val db: SQLiteDatabase,
    private val subjectNames: Map<Long, String>,
) : Closeable {

    companion object {
        private const val PACK = "pack"

        fun open(progressDb: File, packDbFile: File): StatsRepository? {
            if (!progressDb.isFile || !packDbFile.isFile) return null
            return try {
                val db = SQLiteDatabase.openDatabase(progressDb.path, null, SQLiteDatabase.OPEN_READONLY)
                db.execSQL("ATTACH DATABASE '${packDbFile.path.replace("'", "''")}' AS $PACK")
                val subjects = buildMap {
                    db.rawQuery("SELECT id, name FROM $PACK.subjects", null).use { c ->
                        while (c.moveToNext()) put(c.getLong(0), c.getString(1).orEmpty())
                    }
                }
                StatsRepository(db, subjects)
            } catch (e: SQLiteException) { null }
        }

        // FSRS forgetting curve R(t) = (1 + FACTOR · t/S)^DECAY  (FSRS-4.5/6 constants).
        private const val FACTOR = 19.0 / 81.0
        private const val DECAY = -0.5
    }

    /** Overall MCQ accuracy: (answered, correct) across all logged attempts. */
    fun mcqTotals(): AccTotals =
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(correct),0) FROM main.mcq_attempts", null).use { c ->
            if (c.moveToFirst()) AccTotals(c.getInt(0), c.getInt(1)) else AccTotals(0, 0)
        }

    /**
     * Per-subject **coverage + accuracy over MCQs**: for every subject, how many of its questions have
     * been seen (`done`/`total`) and the % answered correctly. Subject-less questions collapse into one
     * "Unattributed" row (kept, never dropped — the old code silently discarded them).
     */
    fun subjectCoverage(): List<SubjectCoverage> {
        val total = LinkedHashMap<Long?, Int>()
        db.rawQuery(
            "SELECT subject_id, COUNT(*) FROM $PACK.feed_items WHERE item_type='mcq' GROUP BY subject_id",
            null,
        ).use { c -> while (c.moveToNext()) total[if (c.isNull(0)) null else c.getLong(0)] = c.getInt(1) }

        val done = HashMap<Long?, Int>()
        db.rawQuery(
            """SELECT f.subject_id, COUNT(*)
               FROM main.fsrs_cards cr
               JOIN $PACK.feed_items f ON f.item_type = cr.item_type AND f.id = cr.item_id
               WHERE f.item_type = 'mcq' GROUP BY f.subject_id""".trimIndent(), null,
        ).use { c -> while (c.moveToNext()) done[if (c.isNull(0)) null else c.getLong(0)] = c.getInt(1) }

        val att = HashMap<Long?, Pair<Int, Int>>() // subject -> (answered, correct)
        db.rawQuery(
            "SELECT subject_id, COUNT(*), COALESCE(SUM(correct),0) FROM main.mcq_attempts GROUP BY subject_id",
            null,
        ).use { c -> while (c.moveToNext()) att[if (c.isNull(0)) null else c.getLong(0)] = c.getInt(1) to c.getInt(2) }

        return total.map { (sid, tot) ->
            val (answered, correct) = att[sid] ?: (0 to 0)
            SubjectCoverage(sid, sid?.let { subjectNames[it] } ?: "Unattributed",
                tot, done[sid] ?: 0, answered, correct)
        }.sortedWith(compareBy<SubjectCoverage> { it.subjectId == null }.thenByDescending { it.done }
            .thenByDescending { it.total })
    }

    /** Reviews per calendar day since [sinceIso] (date → count) — the activity heatmap source. */
    fun activityCounts(sinceIso: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        db.rawQuery("SELECT substr(ts,1,10) AS d, COUNT(*) FROM main.reviews WHERE ts >= ? GROUP BY d",
            arrayOf(sinceIso)).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getInt(1) }
        return m
    }

    /** Daily MCQ attempts + corrects since [sinceIso] — the VM buckets these into weekly accuracy. */
    fun accuracyDaily(sinceIso: String): List<DayAcc> {
        val out = ArrayList<DayAcc>()
        db.rawQuery(
            """SELECT substr(ts,1,10) AS d, COUNT(*), COALESCE(SUM(correct),0)
               FROM main.mcq_attempts WHERE ts >= ? GROUP BY d ORDER BY d""".trimIndent(),
            arrayOf(sinceIso),
        ).use { c -> while (c.moveToNext()) out += DayAcc(c.getString(0), c.getInt(1), c.getInt(2)) }
        return out
    }

    /** Every FSRS due-date bucket (date → count). The VM slices this into overdue + the next N days. */
    fun dueBuckets(): Map<String, Int> {
        val m = HashMap<String, Int>()
        db.rawQuery("SELECT substr(due,1,10) AS d, COUNT(*) FROM main.fsrs_cards WHERE due IS NOT NULL GROUP BY d",
            null).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getInt(1) }
        return m
    }

    /** High-yield core coverage: 5★ items done/total and PYQ items done/total (the two rings). */
    fun coreCoverage(): CoreCoverage = CoreCoverage(
        star5Done = countOf(
            """SELECT COUNT(*) FROM main.fsrs_cards cr
               JOIN $PACK.feed_items f ON f.item_type=cr.item_type AND f.id=cr.item_id WHERE f.importance=5"""),
        star5Total = countOf("SELECT COUNT(*) FROM $PACK.feed_items WHERE importance=5"),
        pyqDone = countOf(
            """SELECT COUNT(*) FROM main.fsrs_cards cr
               JOIN $PACK.feed_items f ON f.item_type=cr.item_type AND f.id=cr.item_id WHERE f.is_pyq=1"""),
        pyqTotal = countOf("SELECT COUNT(*) FROM $PACK.feed_items WHERE is_pyq=1"),
    )

    /** 5★ items with an unresolved mistake — "high-importance gaps you keep failing". */
    fun highImportanceGaps(limit: Int = 30): List<GapItem> {
        val out = ArrayList<GapItem>(limit)
        db.rawQuery(
            """SELECT f.item_type, f.id, f.importance, f.subject_id
               FROM $PACK.feed_items f
               WHERE f.importance = 5
                 AND EXISTS(SELECT 1 FROM main.mistakes m WHERE m.item_type = f.item_type AND m.item_id = f.id AND m.resolved = 0)
               ORDER BY f.importance_score DESC, f.id
               LIMIT ?""".trimIndent(),
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext())
                out += GapItem(c.getString(0).orEmpty(), c.getLong(1), c.getInt(2),
                    if (c.isNull(3)) null else subjectNames[c.getLong(3)])
        }
        return out
    }

    /** The forgetting curve: mean predicted retention across all studied cards at several horizons. */
    fun forgettingCurve(): List<CurvePoint> {
        val stabilities = ArrayList<Double>()
        db.rawQuery("SELECT stability FROM main.fsrs_cards WHERE stability IS NOT NULL AND stability > 0", null)
            .use { c -> while (c.moveToNext()) stabilities += c.getDouble(0) }
        if (stabilities.isEmpty()) return emptyList()
        val horizons = listOf(0, 1, 3, 7, 14, 30)
        return horizons.map { t ->
            val meanR = stabilities.map { s -> (1.0 + FACTOR * t / s).pow(DECAY) }.average()
            CurvePoint(t, meanR)
        }
    }

    private fun countOf(sql: String): Int =
        try { db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 } }
        catch (e: SQLiteException) { 0 }

    override fun close() { runCatching { db.close() } }
}

/** Overall MCQ tallies. */
data class AccTotals(val answered: Int, val correct: Int) {
    val accuracy: Double? get() = if (answered > 0) correct.toDouble() / answered else null
}

/** Per-subject coverage + accuracy over MCQs. `subjectId == null` ⇒ the "Unattributed" bucket. */
data class SubjectCoverage(
    val subjectId: Long?, val name: String,
    val total: Int, val done: Int, val answered: Int, val correct: Int,
) {
    val coverage: Double get() = if (total > 0) done.toDouble() / total else 0.0
    val accuracy: Double? get() = if (answered > 0) correct.toDouble() / answered else null
}

/** One day's MCQ attempts (for weekly-accuracy bucketing). */
data class DayAcc(val date: String, val attempts: Int, val correct: Int)

/** High-yield core coverage: 5★ and PYQ, each done/total. */
data class CoreCoverage(val star5Done: Int, val star5Total: Int, val pyqDone: Int, val pyqTotal: Int)

/** A 5★ item the user keeps failing. */
data class GapItem(val itemType: String, val itemId: Long, val importance: Int, val subjectName: String?)

/** One point on the forgetting curve (days-from-now, mean predicted retention 0..1). */
data class CurvePoint(val days: Int, val retention: Double)
