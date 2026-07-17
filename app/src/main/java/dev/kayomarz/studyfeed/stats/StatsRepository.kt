package dev.kayomarz.studyfeed.stats

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.Closeable
import java.io.File
import kotlin.math.min
import kotlin.math.pow

/**
 * Cross-DB analytics for A6 (BUILD_PROMPT §8 A6). Mastery and the forgetting curve need the user's FSRS
 * state (progress) joined to each item's **subject + importance** (pack), so this opens progress
 * READ-ONLY with the pack `ATTACH`ed — the same seam as [dev.kayomarz.studyfeed.feed.FeedRepository]
 * (per-connection ATTACH, never Room's pool; D4). Pure reads; Room still owns every write.
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

    /** Per-subject mastery: mean FSRS stability + accuracy, over items the user has actually studied. */
    fun subjectMastery(): List<SubjectStat> {
        val stab = HashMap<Long, Pair<Double, Int>>()   // subject -> (meanStability, cardCount)
        db.rawQuery(
            """SELECT f.subject_id, AVG(c.stability), COUNT(c.item_id)
               FROM main.fsrs_cards c
               JOIN $PACK.feed_items f ON f.item_type = c.item_type AND f.id = c.item_id
               WHERE c.stability IS NOT NULL AND f.subject_id IS NOT NULL
               GROUP BY f.subject_id""".trimIndent(), null,
        ).use { c -> while (c.moveToNext()) stab[c.getLong(0)] = c.getDouble(1) to c.getInt(2) }

        val acc = HashMap<Long, Pair<Int, Int>>()       // subject -> (attempts, lapses)
        db.rawQuery(
            """SELECT f.subject_id, COUNT(*), SUM(CASE WHEN r.rating = 1 THEN 1 ELSE 0 END)
               FROM main.reviews r
               JOIN $PACK.feed_items f ON f.item_type = r.item_type AND f.id = r.item_id
               WHERE f.subject_id IS NOT NULL
               GROUP BY f.subject_id""".trimIndent(), null,
        ).use { c -> while (c.moveToNext()) acc[c.getLong(0)] = c.getInt(1) to c.getInt(2) }

        val subjects = (stab.keys + acc.keys).toSet()
        return subjects.map { sid ->
            val (meanStab, cards) = stab[sid] ?: (0.0 to 0)
            val (attempts, lapses) = acc[sid] ?: (0 to 0)
            SubjectStat(sid, subjectNames[sid] ?: "—", meanStab, cards, attempts, lapses)
        }.sortedByDescending { it.mastery }
    }

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

    override fun close() { runCatching { db.close() } }
}

/** Per-subject mastery — stability + accuracy blended into a 0..1 bar. */
data class SubjectStat(
    val subjectId: Long, val name: String, val meanStability: Double,
    val cards: Int, val attempts: Int, val lapses: Int,
) {
    val accuracy: Double get() = if (attempts > 0) 1.0 - lapses.toDouble() / attempts else 0.0
    /** 0..1: half accuracy, half FSRS maturity (stability normalised to ~30 days = "mature"). */
    val mastery: Double get() = 0.5 * accuracy + 0.5 * min(1.0, meanStability / 30.0)
}

/** A 5★ item the user keeps failing. */
data class GapItem(val itemType: String, val itemId: Long, val importance: Int, val subjectName: String?)

/** One point on the forgetting curve (days-from-now, mean predicted retention 0..1). */
data class CurvePoint(val days: Int, val retention: Double)
