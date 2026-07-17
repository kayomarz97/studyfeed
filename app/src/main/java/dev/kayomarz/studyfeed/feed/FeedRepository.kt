package dev.kayomarz.studyfeed.feed

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/**
 * Reads fully-loaded [FeedCard]s across the pack↔progress ATTACH seam (BUILD_PROMPT §8 A2; plan D4/D11).
 *
 * ## Why this is raw SQLite on a dedicated read-only connection, not Room
 *
 * Same reasoning as A1's `FeedReader`, which this supersedes for A2's richer needs: `ATTACH` is
 * per-connection and Room POOLS connections, so a pooled ATTACH would vanish (proven in
 * `AttachSpikeTest`). This owns **one** connection, opened READ-ONLY, that never writes — so it cannot
 * contend with Room's writer, and its ATTACH cannot be lost to the pool. Room owns every write.
 *
 * ## Why windowing, not Paging 3 (D11)
 *
 * A feed is consumed strictly forwards. `LIMIT/OFFSET` on the pack's indexed importance order is a
 * simple, dependency-free window, and **A3 will rewrite the ordering engine anyway** — pulling in a
 * paging library now would shape A3 around a choice made before its requirements exist.
 *
 * ⚠ **Throughput is unproven on hardware.** If `LIMIT/OFFSET` at 32,957 items stutters on the real
 * phone, cache the window — do not reach for a library reflexively.
 */
class FeedRepository private constructor(
    private val db: SQLiteDatabase,
    private val packDir: File,
    private val subjectNames: Map<Long, String>,
) : Closeable {

    companion object {
        private const val PACK = "pack"

        /**
         * Open progress read-only with the pack attached. Returns null if either DB is missing — a
         * fresh install with no pack is a normal state, and the caller shows an empty feed.
         *
         * NOTE: the caller must ensure the progress file EXISTS first
         * (`ProgressDatabase.ensureExists`) — Room creates it lazily, and a missing file made A1's feed
         * report 0 items (see that bug in ProgressDatabase.ensureExists's KDoc).
         */
        fun open(progressDb: File, packDbFile: File): FeedRepository? {
            if (!progressDb.isFile || !packDbFile.isFile) return null
            return try {
                val db = SQLiteDatabase.openDatabase(progressDb.path, null, SQLiteDatabase.OPEN_READONLY)
                db.execSQL("ATTACH DATABASE '${packDbFile.path.replace("'", "''")}' AS $PACK")
                val subjects = buildMap {
                    db.rawQuery("SELECT id, name FROM $PACK.subjects", null).use { c ->
                        while (c.moveToNext()) put(c.getLong(0), c.getString(1).orEmpty())
                    }
                }
                FeedRepository(db, packDbFile.parentFile ?: packDbFile, subjects)
            } catch (e: SQLiteException) {
                null
            }
        }
    }

    /** Total playable items — A2's pager bound. */
    fun size(): Int = countOf("SELECT COUNT(*) FROM $PACK.feed_items")

    /**
     * One window of the feed, importance-first (the pack's own order; A3 will make this smarter).
     *
     * Loads the feed_items page, then fetches each item's payload from its type table and its FSRS
     * state from progress. LEFT-join semantics for progress: an unseen item still appears (a new user's
     * feed cannot be empty).
     */
    fun page(offset: Int, limit: Int): List<FeedCard> {
        val refs = mutableListOf<FeedRepositoryRef>()
        db.rawQuery(
            """
            SELECT item_type, id, ref_id, importance, importance_score, subject_id
            FROM $PACK.feed_items
            ORDER BY importance DESC, importance_score DESC, id
            LIMIT ? OFFSET ?
            """.trimIndent(),
            arrayOf(limit.toString(), offset.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                refs += FeedRepositoryRef(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2),
                    c.getInt(3), c.getDouble(4), if (c.isNull(5)) null else c.getLong(5))
            }
        }

        return refs.mapNotNull { r ->
            val fsrs = fsrsFor(r.type, r.itemId)
            when (r.type) {
                "mcq" -> loadMcq(r.refId, r, fsrs)
                "flashcard" -> loadFlashcard(r.refId, r, fsrs)
                "reel" -> loadReel(r.refId, r, fsrs)
                "blob" -> loadBlob(r.refId, r, fsrs)
                "interlink" -> loadInterlink(r.refId, r, fsrs)
                else -> null // an unknown future type is skipped, not crashed on (types are open, D5)
            }
        }
    }

    // ---- per-type loaders --------------------------------------------------------------------------

    private data class Fsrs(val due: String?, val stability: Double?)

    private fun fsrsFor(type: String, itemId: Long): Fsrs =
        db.rawQuery(
            "SELECT due, stability FROM main.fsrs_cards WHERE item_type = ? AND item_id = ?",
            arrayOf(type, itemId.toString()),
        ).use { c ->
            if (c.moveToFirst()) Fsrs(c.getString(0), if (c.isNull(1)) null else c.getDouble(1))
            else Fsrs(null, null)
        }

    private fun loadMcq(id: Long, r: FeedRepositoryRef, fsrs: Fsrs): FeedCard.Mcq? {
        return db.rawQuery(
            """
            SELECT stem_md, opt_a, opt_b, opt_c, opt_d, opt_e, correct_idx, explanation_md,
                   key_conflict, key_conflict_note, pyq_years_json, importance_signals_json, figure_id
            FROM $PACK.mcqs WHERE id = ?
            """.trimIndent(), arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val opts = listOf(c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
                .filterNotNull().filter { it.isNotBlank() }
            FeedCard.Mcq(
                itemId = r.itemId, importance = r.importance, importanceScore = r.score,
                importanceSignals = parseSignals(c.getString(11)), subjectName = subjectNames[r.subjectId],
                pyqYears = parseYears(c.getString(10)), due = fsrs.due, stability = fsrs.stability,
                stemMd = c.getString(0).orEmpty(), options = opts, correctIdx = c.getInt(6),
                explanationMd = c.getString(7).orEmpty(),
                perOptionWhyWrong = whyWrong(id, opts.size),
                keyConflict = c.getInt(8) == 1, keyConflictNote = c.getString(9),
                figure = figureById(if (c.isNull(12)) null else c.getLong(12)),
            )
        }
    }

    private fun loadFlashcard(id: Long, r: FeedRepositoryRef, fsrs: Fsrs): FeedCard.Flashcard? =
        db.rawQuery(
            """SELECT f.front_md, f.back_md, f.is_cloze, f.importance_signals_json, a.figure_id
               FROM $PACK.flashcards f LEFT JOIN $PACK.atoms a ON a.id = f.atom_id WHERE f.id = ?""",
            arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            FeedCard.Flashcard(
                itemId = r.itemId, importance = r.importance, importanceScore = r.score,
                importanceSignals = parseSignals(c.getString(3)), subjectName = subjectNames[r.subjectId],
                due = fsrs.due, stability = fsrs.stability,
                frontMd = c.getString(0).orEmpty(), backMd = c.getString(1).orEmpty(),
                isCloze = c.getInt(2) == 1, figure = figureById(if (c.isNull(4)) null else c.getLong(4)),
            )
        }

    private fun loadReel(id: Long, r: FeedRepositoryRef, fsrs: Fsrs): FeedCard.Reel? =
        db.rawQuery(
            """SELECT rl.hook_md, rl.detail_md, rl.importance_signals_json, a.figure_id
               FROM $PACK.reels rl LEFT JOIN $PACK.atoms a ON a.id = rl.atom_id WHERE rl.id = ?""",
            arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            FeedCard.Reel(
                itemId = r.itemId, importance = r.importance, importanceScore = r.score,
                importanceSignals = parseSignals(c.getString(2)), subjectName = subjectNames[r.subjectId],
                due = fsrs.due, stability = fsrs.stability,
                hookMd = c.getString(0).orEmpty(), detailMd = c.getString(1).orEmpty(),
                figure = figureById(if (c.isNull(3)) null else c.getLong(3)),
            )
        }

    private fun loadBlob(id: Long, r: FeedRepositoryRef, fsrs: Fsrs): FeedCard.Blob? =
        db.rawQuery(
            "SELECT title, body_md, est_read_seconds, importance_signals_json FROM $PACK.text_blobs WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            FeedCard.Blob(
                itemId = r.itemId, importance = r.importance, importanceScore = r.score,
                importanceSignals = parseSignals(c.getString(3)), subjectName = subjectNames[r.subjectId],
                due = fsrs.due, stability = fsrs.stability,
                title = c.getString(0), bodyMd = c.getString(1).orEmpty(),
                estReadSeconds = if (c.isNull(2)) null else c.getInt(2),
            )
        }

    private fun loadInterlink(id: Long, r: FeedRepositoryRef, fsrs: Fsrs): FeedCard.Interlink? =
        db.rawQuery(
            "SELECT question_md, answer_md, importance_score FROM $PACK.interlink_quizzes WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            FeedCard.Interlink(
                itemId = r.itemId, importance = r.importance, importanceScore = r.score,
                importanceSignals = null, subjectName = subjectNames[r.subjectId],
                due = fsrs.due, stability = fsrs.stability,
                questionMd = c.getString(0).orEmpty(), answerMd = c.getString(1).orEmpty(),
            )
        }

    // ---- shared bits -------------------------------------------------------------------------------

    private fun whyWrong(mcqId: Long, optionCount: Int): List<String?> {
        val byIdx = HashMap<Int, String>()
        db.rawQuery("SELECT option_idx, why_wrong FROM $PACK.mcq_distractors WHERE mcq_id = ?",
            arrayOf(mcqId.toString())).use { c ->
            while (c.moveToNext()) byIdx[c.getInt(0)] = c.getString(1).orEmpty()
        }
        return (0 until optionCount).map { byIdx[it] }
    }

    /**
     * Resolve a figure to an absolute file path in the imported pack directory.
     *
     * `figures.path` is RELATIVE (`figures/<hash>.png`), so it is resolved against the pack dir. The
     * file is verified present here — a manifest that references a missing image should degrade to "no
     * figure", not a broken image slot.
     */
    private fun figureById(figureId: Long?): FeedCard.Figure? {
        if (figureId == null) return null
        return db.rawQuery("SELECT path, caption_md FROM $PACK.figures WHERE id = ?",
            arrayOf(figureId.toString())).use { c ->
            if (!c.moveToFirst()) return null
            val rel = c.getString(0) ?: return null
            val f = File(packDir, rel)
            if (f.isFile) FeedCard.Figure(f.path, c.getString(1)) else null
        }
    }

    private fun parseYears(json: String?): List<Int> = try {
        if (json.isNullOrBlank()) emptyList() else JSONArray(json).let { a ->
            (0 until a.length()).map { a.getInt(it) }.sorted()
        }
    } catch (e: Exception) { emptyList() }

    private fun parseSignals(json: String?): ImportanceSignals? = try {
        if (json.isNullOrBlank()) null else JSONObject(json).let { o ->
            ImportanceSignals(
                pyq = o.optDouble("pyq", 0.0), corrob = o.optDouble("corrob", 0.0),
                freq = o.optDouble("freq", 0.0), emphasis = o.optDouble("emphasis", 0.0),
                blueprint = o.optDouble("blueprint", 0.0), centrality = o.optDouble("centrality", 0.0),
                tier = o.optDouble("tier", 0.0), trend = o.optDouble("trend", 0.0),
            )
        }
    } catch (e: Exception) { null }

    private fun countOf(sql: String): Int =
        try { db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 } }
        catch (e: SQLiteException) { 0 }

    override fun close() { runCatching { db.close() } }
}

/** Internal carrier for a feed_items row before its payload is loaded. */
internal data class FeedRepositoryRef(
    val type: String, val itemId: Long, val refId: Long,
    val importance: Int, val score: Double, val subjectId: Long?,
)
