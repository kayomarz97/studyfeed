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

    /** A card's topic dossier + its concept (A7 "Read full topic" / "Concept map" links). */
    private data class TopicRef(val topicPageId: Long, val conceptId: Long?)

    /**
     * Reverse index MCQ/flashcard id → its topic dossier, built once on first card load (A7). The pack
     * maps topic→items (`topic_pages.related_mcq_ids_json` / `related_flashcard_ids_json`); we invert it so
     * a card can link to its topic. First topic wins if an item is listed under several.
     */
    private val topicIndex: Pair<Map<Long, TopicRef>, Map<Long, TopicRef>> by lazy {
        val mcq = HashMap<Long, TopicRef>()
        val fc = HashMap<Long, TopicRef>()
        try {
            db.rawQuery(
                "SELECT id, concept_id, related_mcq_ids_json, related_flashcard_ids_json FROM $PACK.topic_pages",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val ref = TopicRef(c.getLong(0), if (c.isNull(1)) null else c.getLong(1))
                    parseIdArray(c.getString(2)).forEach { mcq.putIfAbsent(it, ref) }
                    parseIdArray(c.getString(3)).forEach { fc.putIfAbsent(it, ref) }
                }
            }
        } catch (e: SQLiteException) { /* pack without dossiers → empty index, links hidden */ }
        mcq to fc
    }

    private fun parseIdArray(json: String?): List<Long> = try {
        if (json.isNullOrBlank()) emptyList()
        else JSONArray(json).let { a -> (0 until a.length()).map { a.getLong(it) } }
    } catch (e: Exception) { emptyList() }

    /** Total playable items — A2's pager bound (unfiltered). */
    fun size(): Int = countOf("SELECT COUNT(*) FROM $PACK.feed_items")

    /** Count matching a query — the pager bound when a filter is active. */
    fun count(query: FeedQuery): Int {
        val (where, args) = whereClause(query.filter)
        return countOf("SELECT COUNT(*) FROM $PACK.feed_items f $where", args)
    }

    /** Convenience: a window of the default feed (Smart sort, no filter). */
    fun page(offset: Int, limit: Int): List<FeedCard> = page(FeedQuery(), offset, limit)

    /**
     * One window of the feed for [query], the filter/sort applied in SQL.
     *
     * Loads the matching feed_items page, then each item's payload + FSRS state. LEFT-join semantics for
     * progress: an unseen item still appears (a new user's feed cannot be empty). Weakness for the
     * Priority sort is computed from the row's own stability + lapses — no clock needed, so the sort is
     * deterministic (A3 may add a time-decay/retrievability term, which would need `now`).
     */
    fun page(query: FeedQuery, offset: Int, limit: Int): List<FeedCard> {
        val (where, whereArgs) = whereClause(query.filter)
        val (join, order) = sortSql(query.sort)
        val refs = mutableListOf<FeedRepositoryRef>()
        db.rawQuery(
            """
            SELECT f.item_type, f.id, f.ref_id, f.importance, f.importance_score, f.subject_id
            FROM $PACK.feed_items f
            $join
            $where
            $order
            LIMIT ? OFFSET ?
            """.trimIndent(),
            (whereArgs + listOf(limit.toString(), offset.toString())).toTypedArray(),
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

    // ---- A3 ordering-engine pools (BUILD_PROMPT §8 A3) ---------------------------------------------
    //
    // The engine ([FeedScheduler]) does the stateful composition; this only builds the candidate POOLS
    // in SQL (cheap, indexed) and loads payloads for the refs the engine picks. Content-safe: every
    // query returns ids + importance, never pack text (copyright rule).

    /**
     * The **due pool**: FSRS cards due now ∪ blobs whose re-read is due now, most-urgent first.
     *
     * Urgency = **most-overdue-first** (`due ASC` — ISO-8601 strings sort chronologically, so this needs
     * no `julianday` parsing of `Instant`'s `…T…Z` form, which SQLite parses unreliably), with
     * importance breaking ties. [exclude] (items already emitted this app-open) is filtered in Kotlin to
     * avoid a giant `NOT IN (…)`.
     */
    fun duePool(nowIso: String, exclude: Set<Long>, limit: Int): List<FeedScheduler.Ref> {
        val rows = ArrayList<FeedScheduler.Ref>(limit + exclude.size)
        db.rawQuery(
            """
            SELECT f.item_type, f.id, f.ref_id, f.importance, f.importance_score, f.subject_id, c.due AS due_at
            FROM main.fsrs_cards c
            JOIN $PACK.feed_items f ON f.item_type = c.item_type AND f.id = c.item_id
            WHERE c.due IS NOT NULL AND c.due <= ?
            UNION ALL
            SELECT f.item_type, f.id, f.ref_id, f.importance, f.importance_score, f.subject_id, b.reread_due AS due_at
            FROM main.blob_reads b
            JOIN $PACK.feed_items f ON f.item_type = 'blob' AND f.id = b.blob_id
            WHERE b.reread_due IS NOT NULL AND b.reread_due <= ?
            ORDER BY due_at ASC, importance DESC, id
            LIMIT ?
            """.trimIndent(),
            arrayOf(nowIso, nowIso, (limit + exclude.size).toString()),
        ).use { c -> while (c.moveToNext()) rows += refFrom(c, FeedScheduler.Source.DUE) }
        return rows.filter { it.itemId !in exclude }.take(limit)
    }

    /** Never-seen MCQs after the keyset cursor, importance-first (5★/PYQ earliest). */
    fun newMcqs(afterScore: Double?, afterId: Long?, limit: Int): List<FeedScheduler.Ref> =
        newPool(mcqOnly = true, afterScore, afterId, limit, FeedScheduler.Source.NEW_MCQ)

    /** Never-seen non-MCQ items after the keyset cursor, importance-first. */
    fun newOthers(afterScore: Double?, afterId: Long?, limit: Int): List<FeedScheduler.Ref> =
        newPool(mcqOnly = false, afterScore, afterId, limit, FeedScheduler.Source.NEW_OTHER)

    private fun newPool(
        mcqOnly: Boolean, afterScore: Double?, afterId: Long?, limit: Int, source: FeedScheduler.Source,
    ): List<FeedScheduler.Ref> {
        val typeClause = if (mcqOnly) "f.item_type = 'mcq'" else "f.item_type <> 'mcq'"
        val args = mutableListOf<String>()
        // Keyset: strictly after (importance_score DESC, id ASC). Null cursor = first page.
        val keyset = if (afterScore != null && afterId != null) {
            args += afterScore.toString(); args += afterScore.toString(); args += afterId.toString()
            "AND (f.importance_score < ? OR (f.importance_score = ? AND f.id > ?))"
        } else ""
        args += limit.toString()
        val rows = ArrayList<FeedScheduler.Ref>(limit)
        db.rawQuery(
            """
            SELECT f.item_type, f.id, f.ref_id, f.importance, f.importance_score, f.subject_id
            FROM $PACK.feed_items f
            WHERE $typeClause
              AND NOT EXISTS(SELECT 1 FROM main.fsrs_cards c WHERE c.item_type = f.item_type AND c.item_id = f.id)
              $keyset
            ORDER BY f.importance_score DESC, f.id
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray(),
        ).use { c -> while (c.moveToNext()) rows += refFrom(c, source) }
        return rows
    }

    /** Seen-but-not-yet-due items — filler so the feed never dead-ends. Emitted ids excluded in Kotlin. */
    fun fillerPool(nowIso: String, exclude: Set<Long>, limit: Int): List<FeedScheduler.Ref> {
        val rows = ArrayList<FeedScheduler.Ref>(limit + exclude.size)
        db.rawQuery(
            """
            SELECT f.item_type, f.id, f.ref_id, f.importance, f.importance_score, f.subject_id
            FROM main.fsrs_cards c
            JOIN $PACK.feed_items f ON f.item_type = c.item_type AND f.id = c.item_id
            WHERE c.due IS NULL OR c.due > ?
            ORDER BY f.importance DESC, f.importance_score DESC, f.id
            LIMIT ?
            """.trimIndent(),
            arrayOf(nowIso, (limit + exclude.size).toString()),
        ).use { c -> while (c.moveToNext()) rows += refFrom(c, FeedScheduler.Source.FILLER) }
        return rows.filter { it.itemId !in exclude }.take(limit)
    }

    /**
     * How many items are **due now** — FSRS cards due ∪ blobs whose re-read is due — for the dashboard's
     * "due today" figure (A7 home). Content-safe count; mirrors [duePool]'s membership, not its cap.
     */
    fun dueCount(nowIso: String): Int = countOf(
        """
        SELECT
          (SELECT COUNT(*) FROM main.fsrs_cards c WHERE c.due IS NOT NULL AND c.due <= ?)
        + (SELECT COUNT(*) FROM main.blob_reads b WHERE b.reread_due IS NOT NULL AND b.reread_due <= ?)
        """.trimIndent(),
        listOf(nowIso, nowIso),
    )

    /** Per-item-type (total in pack, seen = has an FSRS row) — the A7 Home progress tiles. */
    data class TypeProgress(val total: Int, val done: Int)

    fun progressByType(): Map<String, TypeProgress> {
        val total = HashMap<String, Int>()
        val done = HashMap<String, Int>()
        db.rawQuery("SELECT item_type, COUNT(*) FROM $PACK.feed_items GROUP BY item_type", null).use { c ->
            while (c.moveToNext()) total[c.getString(0).orEmpty()] = c.getInt(1)
        }
        db.rawQuery(
            """SELECT f.item_type, COUNT(*) FROM main.fsrs_cards crd
               JOIN $PACK.feed_items f ON f.item_type = crd.item_type AND f.id = crd.item_id
               GROUP BY f.item_type""".trimIndent(), null,
        ).use { c -> while (c.moveToNext()) done[c.getString(0).orEmpty()] = c.getInt(1) }
        return total.mapValues { (type, t) -> TypeProgress(t, done[type] ?: 0) }
    }

    /** Count of never-seen MCQs — the numerator of the deadline pace. */
    fun remainingNewMcqs(): Int = countOf(
        """
        SELECT COUNT(*) FROM $PACK.feed_items f
        WHERE f.item_type = 'mcq'
          AND NOT EXISTS(SELECT 1 FROM main.fsrs_cards c WHERE c.item_type = 'mcq' AND c.item_id = f.id)
        """.trimIndent()
    )

    /** Resolve a blob (keyed by its `feed_items.id`) to a Ref, to re-surface its quiz-back (§8 A3). */
    fun blobRef(feedItemId: Long): FeedScheduler.Ref? =
        db.rawQuery(
            """SELECT f.item_type, f.id, f.ref_id, f.importance, f.importance_score, f.subject_id
               FROM $PACK.feed_items f WHERE f.id = ? AND f.item_type = 'blob'""",
            arrayOf(feedItemId.toString()),
        ).use { c -> if (c.moveToNext()) refFrom(c, FeedScheduler.Source.BLOB_QUIZ) else null }

    private fun refFrom(c: android.database.Cursor, source: FeedScheduler.Source) = FeedScheduler.Ref(
        itemType = c.getString(0).orEmpty(), itemId = c.getLong(1), refId = c.getLong(2),
        importance = c.getInt(3), importanceScore = c.getDouble(4),
        subjectId = if (c.isNull(5)) null else c.getLong(5), source = source,
    )

    /** Load full payloads for an ordered list of engine-picked refs, preserving the engine's order. */
    fun loadCards(refs: List<FeedScheduler.Ref>): List<FeedCard> = refs.mapNotNull { ref ->
        val r = FeedRepositoryRef(ref.itemType, ref.itemId, ref.refId, ref.importance, ref.importanceScore, ref.subjectId)
        val fsrs = fsrsFor(ref.itemType, ref.itemId)
        when (ref.itemType) {
            "mcq" -> loadMcq(ref.refId, r, fsrs)
            "flashcard" -> loadFlashcard(ref.refId, r, fsrs)
            "reel" -> loadReel(ref.refId, r, fsrs)
            "blob" -> loadBlob(ref.refId, r, fsrs)?.copy(openAtQuiz = ref.openAtQuiz)
            "interlink" -> loadInterlink(ref.refId, r, fsrs)
            else -> null
        }
    }

    // ---- query → SQL --------------------------------------------------------------------------------

    private fun whereClause(filter: FeedFilter): Pair<String, List<String>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        filter.itemType?.let { clauses += "f.item_type = ?"; args += it }
        filter.itemTypesIn?.takeIf { it.isNotEmpty() }?.let { types ->
            clauses += "f.item_type IN (${types.joinToString(",") { "?" }})"
            args += types
        }
        filter.importanceExact?.let { clauses += "f.importance = ?"; args += it.toString() }
        filter.importanceMin?.let { clauses += "f.importance >= ?"; args += it.toString() }
        if (filter.pyqOnly) clauses += "f.is_pyq = 1"
        filter.subjectId?.let { clauses += "f.subject_id = ?"; args += it.toString() }
        // Starred / mistakes / tag are progress-side facts → EXISTS against the main (progress) DB,
        // keyed by (item_type,item_id). No FK across the ATTACH — the pack and progress are separate DBs.
        if (filter.starredOnly)
            clauses += "EXISTS(SELECT 1 FROM main.stars s WHERE s.item_type=f.item_type AND s.item_id=f.id)"
        if (filter.mistakesOnly)
            clauses += "EXISTS(SELECT 1 FROM main.mistakes m WHERE m.item_type=f.item_type AND m.item_id=f.id AND m.resolved=0)"
        filter.tagKey?.let {
            clauses += "EXISTS(SELECT 1 FROM main.item_tags t WHERE t.item_type=f.item_type AND t.item_id=f.id AND t.tag_key=?)"
            args += it
        }
        return if (clauses.isEmpty()) "" to emptyList()
        else "WHERE " + clauses.joinToString(" AND ") to args
    }

    /** @return (JOIN clause, ORDER BY clause) — the JOIN goes before WHERE, the ORDER after it. */
    private fun sortSql(sort: FeedQuery.Sort): Pair<String, String> = when (sort) {
        // SMART default = the pack's importance order. A3 replaces this with the real engine.
        FeedQuery.Sort.SMART,
        FeedQuery.Sort.IMPORTANCE_DESC ->
            "" to "ORDER BY f.importance DESC, f.importance_score DESC, f.id"
        FeedQuery.Sort.IMPORTANCE_ASC ->
            "" to "ORDER BY f.importance ASC, f.importance_score ASC, f.id"
        FeedQuery.Sort.RANDOM -> "" to "ORDER BY RANDOM()"
        // "By subject" study mode: a structured, subject-grouped sweep, importance-first within each
        // subject. subject_id is stable per pack; subject-less items (past-paper/qbank) sort last.
        FeedQuery.Sort.SUBJECT ->
            "" to "ORDER BY f.subject_id IS NULL, f.subject_id, f.importance DESC, f.importance_score DESC, f.id"
        // Priority = importance × weakness, composed HERE on the phone (§8 A2: "proving weakness is
        // folded in locally"). Weakness rises with lapses and low stability; an unseen item gets a
        // moderate baseline so it still surfaces. A2's implementation; A3 refines it. Needs the LEFT
        // JOIN to progress for the weakness terms.
        FeedQuery.Sort.PRIORITY ->
            "LEFT JOIN main.fsrs_cards c ON c.item_type = f.item_type AND c.item_id = f.id" to
            """
            ORDER BY (
                f.importance_score * (
                    CASE WHEN c.item_id IS NULL THEN 0.6
                    ELSE MIN(1.0, 0.2
                        + COALESCE(c.lapses,0) * 0.15
                        + CASE WHEN c.stability IS NOT NULL THEN 0.3 / (1.0 + c.stability) ELSE 0.2 END)
                    END)
            ) DESC, f.importance DESC, f.id
            """.trimIndent()
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
            val topic = topicIndex.first[id]
            FeedCard.Mcq(
                itemId = r.itemId, importance = r.importance, importanceScore = r.score,
                importanceSignals = parseSignals(c.getString(11)), subjectName = subjectNames[r.subjectId],
                pyqYears = parseYears(c.getString(10)), due = fsrs.due, stability = fsrs.stability,
                topicPageId = topic?.topicPageId, conceptId = topic?.conceptId,
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
            val topic = topicIndex.second[id]
            FeedCard.Flashcard(
                itemId = r.itemId, importance = r.importance, importanceScore = r.score,
                importanceSignals = parseSignals(c.getString(3)), subjectName = subjectNames[r.subjectId],
                due = fsrs.due, stability = fsrs.stability,
                topicPageId = topic?.topicPageId, conceptId = topic?.conceptId,
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
                quiz = blobQuiz(id),
            )
        }

    /** The passage's quiz-back, if the pack shipped one. `blob_quizzes.blob_id` = the text_blobs id. */
    private fun blobQuiz(blobId: Long): FeedCard.BlobQuiz? =
        db.rawQuery(
            "SELECT question_md, answer_md, opt_a, opt_b, opt_c, opt_d, correct_idx FROM $PACK.blob_quizzes WHERE blob_id = ? LIMIT 1",
            arrayOf(blobId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val opts = listOf(c.getString(2), c.getString(3), c.getString(4), c.getString(5))
                .filterNotNull().filter { it.isNotBlank() }
            // A7: the pack authors blob quiz-backs with the correct option at index 0 ~89% of the time,
            // so without this the answer is almost always "A". Shuffle deterministically by blob id — the
            // order is stable per card (no jumping on re-view) but varied across cards.
            val (shuffled, correct) = shuffleOptions(opts, c.getInt(6), blobId)
            FeedCard.BlobQuiz(
                questionMd = c.getString(0).orEmpty(), answerMd = c.getString(1).orEmpty(),
                options = shuffled, correctIdx = correct,
            )
        }

    /**
     * Deterministically shuffle [options] by [seed], returning the reordered list and the new index of
     * the item that was at [correctIdx]. Falls back to the original order if [correctIdx] is out of range
     * (a malformed row) so a bad quiz never mislabels its answer.
     */
    private fun shuffleOptions(options: List<String>, correctIdx: Int, seed: Long): Pair<List<String>, Int> {
        if (correctIdx !in options.indices) return options to correctIdx
        val order = options.indices.shuffled(kotlin.random.Random(seed))
        return order.map { options[it] } to order.indexOf(correctIdx)
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

    private fun countOf(sql: String, args: List<String> = emptyList()): Int =
        try {
            db.rawQuery(sql, if (args.isEmpty()) null else args.toTypedArray())
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: SQLiteException) { 0 }

    override fun close() { runCatching { db.close() } }
}

/** Internal carrier for a feed_items row before its payload is loaded. */
internal data class FeedRepositoryRef(
    val type: String, val itemId: Long, val refId: Long,
    val importance: Int, val score: Double, val subjectId: Long?,
)
