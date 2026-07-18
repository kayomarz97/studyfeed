package dev.kayomarz.studyfeed.exam

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.Closeable
import java.io.File
import kotlin.random.Random

/**
 * Samples a **mock exam** from the pack (A7 cycle 3, P4) — a timed, **blueprint-weighted** block of MCQs
 * that mimics the real paper. Pack-only, read-only (no progress); the [MockExamViewModel] writes results.
 * Options are shuffled deterministically per item so the answer isn't always in the same position.
 */
class MockExamRepository private constructor(
    private val db: SQLiteDatabase,
    private val subjectNames: Map<Long, String>,
    private val weights: List<Pair<Long, Double>>, // (subjectId, blueprint_weight)
) : Closeable {

    companion object {
        fun open(packDbFile: File): MockExamRepository? {
            if (!packDbFile.isFile) return null
            return try {
                val db = SQLiteDatabase.openDatabase(packDbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                val names = HashMap<Long, String>()
                val w = ArrayList<Pair<Long, Double>>()
                db.rawQuery("SELECT id, name, blueprint_weight FROM subjects ORDER BY ord", null).use { c ->
                    while (c.moveToNext()) {
                        names[c.getLong(0)] = c.getString(1).orEmpty()
                        w += c.getLong(0) to (if (c.isNull(2)) 0.0 else c.getDouble(2))
                    }
                }
                MockExamRepository(db, names, w)
            } catch (e: SQLiteException) { null }
        }
    }

    /** Sample [n] MCQs, blueprint-weighted across subjects, filling any shortfall from the whole pool. */
    fun sample(n: Int): List<ExamQuestion> {
        val total = weights.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
        val picked = LinkedHashSet<Long>()
        val out = ArrayList<ExamQuestion>()
        for ((sid, w) in weights) {
            val quota = Math.round(n * w / total).toInt()
            loadInto(out, picked, "AND f.subject_id = $sid", quota)
        }
        if (out.size < n) loadInto(out, picked, "", n - out.size) // fill (incl. subject-less)
        return out.take(n)
    }

    private fun loadInto(out: MutableList<ExamQuestion>, picked: MutableSet<Long>, subjClause: String, want: Int) {
        if (want <= 0) return
        var added = 0
        db.rawQuery(
            "SELECT f.id, f.ref_id, f.subject_id FROM feed_items f WHERE f.item_type='mcq' $subjClause ORDER BY RANDOM() LIMIT ?",
            arrayOf((want * 2 + 10).toString()),
        ).use { c ->
            while (c.moveToNext() && added < want) {
                val fid = c.getLong(0)
                if (fid in picked) continue
                val q = loadQuestion(fid, c.getLong(1), if (c.isNull(2)) null else c.getLong(2)) ?: continue
                picked += fid; out += q; added++
            }
        }
    }

    private fun loadQuestion(feedItemId: Long, mcqId: Long, subjectId: Long?): ExamQuestion? =
        db.rawQuery(
            "SELECT stem_md, opt_a, opt_b, opt_c, opt_d, opt_e, correct_idx FROM mcqs WHERE id = ?",
            arrayOf(mcqId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val opts = listOf(c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
                .filterNotNull().filter { it.isNotBlank() }
            val correct = c.getInt(6)
            if (opts.size < 2 || correct !in opts.indices) return null
            val order = opts.indices.shuffled(Random(feedItemId))
            ExamQuestion(
                feedItemId = feedItemId, subjectId = subjectId,
                subjectName = subjectId?.let { subjectNames[it] },
                stemMd = c.getString(0).orEmpty(),
                options = order.map { opts[it] }, correctIdx = order.indexOf(correct),
            )
        }

    override fun close() { runCatching { db.close() } }
}

/** One mock-exam question. [feedItemId] keys progress (attempts / FSRS) on submit. */
data class ExamQuestion(
    val feedItemId: Long,
    val subjectId: Long?,
    val subjectName: String?,
    val stemMd: String,
    val options: List<String>,
    val correctIdx: Int,
)
