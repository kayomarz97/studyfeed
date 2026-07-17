package dev.kayomarz.studyfeed.dossier

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import dev.kayomarz.studyfeed.feed.FeedCard
import dev.kayomarz.studyfeed.feed.ImportanceSignals
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/**
 * Reads the pre-compiled **topic dossiers** (BUILD_PROMPT §8 A4) from the pack. Pure render — every byte
 * comes from `topic_pages`/`topic_sections`/`figures`; **nothing is generated** (Acceptance 8).
 *
 * ## Pack-only, read-only, self-contained
 *
 * A dossier needs no progress state to render, so this opens **only the pack**, READ-ONLY — no ATTACH,
 * no Room contention (cf. [dev.kayomarz.studyfeed.feed.FeedRepository], which needs the progress↔pack
 * seam for FSRS). The in-place related-item viewer ([loadCardByRef]) is a **reference view** (no FSRS
 * write, per the A4 decision "separate from your main feed progress"), so it loads payloads with a null
 * FSRS state and needs no progress DB either. The small MCQ/flashcard loaders here are deliberately
 * independent of FeedRepository's (which are FSRS-coupled) to keep the dossier fully decoupled.
 */
class DossierRepository private constructor(
    private val db: SQLiteDatabase,
    private val packDir: File,
    private val subjectNames: Map<Long, String>,
) : Closeable {

    companion object {
        fun open(packDbFile: File): DossierRepository? {
            if (!packDbFile.isFile) return null
            return try {
                val db = SQLiteDatabase.openDatabase(packDbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                val subjects = buildMap {
                    db.rawQuery("SELECT id, name FROM subjects", null).use { c ->
                        while (c.moveToNext()) put(c.getLong(0), c.getString(1).orEmpty())
                    }
                }
                DossierRepository(db, packDbFile.parentFile ?: packDbFile, subjects)
            } catch (e: SQLiteException) {
                null
            }
        }
    }

    /** How many dossiers the pack ships — a hint for the Library's empty/loaded states. */
    fun size(): Int = countOf("SELECT COUNT(*) FROM topic_pages")

    /**
     * Search dossiers by name/alias + full body text (A4 decision). Title/alias hits rank above
     * body-only hits, then importance ↓. LIKE over 8,194 rows is well under 100 ms — no FTS index needed.
     */
    fun search(query: String, limit: Int = 60): List<DossierListItem> {
        val q = query.trim()
        if (q.isBlank()) return topDossiers(limit)
        val like = "%${q.replace("%", "\\%").replace("_", "\\_")}%"
        val rows = ArrayList<DossierListItem>(limit)
        db.rawQuery(
            """
            SELECT id, title, subjects_covered_json, importance, importance_score,
                   CASE WHEN title LIKE ? ESCAPE '\' OR aliases_json LIKE ? ESCAPE '\' THEN 1 ELSE 0 END AS name_hit
            FROM topic_pages
            WHERE title LIKE ? ESCAPE '\' OR aliases_json LIKE ? ESCAPE '\' OR body_md LIKE ? ESCAPE '\'
            ORDER BY name_hit DESC, importance DESC, importance_score DESC, id
            LIMIT ?
            """.trimIndent(),
            arrayOf(like, like, like, like, like, limit.toString()),
        ).use { c -> while (c.moveToNext()) rows += listItem(c) }
        return rows
    }

    /** The default Library list when the search box is empty: highest-yield dossiers first. */
    fun topDossiers(limit: Int = 60): List<DossierListItem> {
        val rows = ArrayList<DossierListItem>(limit)
        db.rawQuery(
            """
            SELECT id, title, subjects_covered_json, importance, importance_score
            FROM topic_pages ORDER BY importance DESC, importance_score DESC, id LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { c -> while (c.moveToNext()) rows += listItem(c) }
        return rows
    }

    private fun listItem(c: android.database.Cursor) = DossierListItem(
        topicPageId = c.getLong(0),
        title = c.getString(1).orEmpty(),
        subjects = parseStringArray(c.getString(2)),
        importance = c.getInt(3),
        importanceScore = c.getDouble(4),
    )

    /** Load one full dossier: body, ordered collapsible sections, figures, and related-item links. */
    fun loadDossier(topicPageId: Long): Dossier? {
        return db.rawQuery(
            """
            SELECT title, body_md, importance, importance_score, importance_signals_json,
                   subjects_covered_json, figure_ids_json, related_mcq_ids_json, related_flashcard_ids_json
            FROM topic_pages WHERE id = ?
            """.trimIndent(), arrayOf(topicPageId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            Dossier(
                topicPageId = topicPageId,
                title = c.getString(0).orEmpty(),
                // body_md IS the full page = preamble + the same `## ` sections that topic_sections holds
                // verbatim (proven: section body ⊂ page body). Render the sections from topic_sections
                // (collapsible), so here we keep ONLY the preamble — the part before the first `## ` — and
                // drop its leading `# title` (shown in the header). Otherwise every fact renders twice.
                bodyMd = preambleOf(c.getString(1).orEmpty()),
                importance = c.getInt(2),
                importanceScore = c.getDouble(3),
                importanceSignals = parseSignals(c.getString(4)),
                subjectsCovered = parseStringArray(c.getString(5)),
                figures = figuresFor(c.getString(6)),
                sections = sectionsFor(topicPageId),
                relatedMcqs = relatedFor("mcq", c.getString(7)),
                relatedFlashcards = relatedFor("flashcard", c.getString(8)),
            )
        }
    }

    private fun sectionsFor(topicPageId: Long): List<DossierSection> {
        val out = ArrayList<DossierSection>()
        db.rawQuery(
            "SELECT heading, body_md, subject_id, figure_ids_json FROM topic_sections WHERE topic_page_id = ? ORDER BY ord, id",
            arrayOf(topicPageId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += DossierSection(
                    heading = c.getString(0).orEmpty(),
                    // Each section body_md STARTS with its own `## heading` (proven) — drop it, the
                    // heading is already the tappable section row.
                    bodyMd = stripLeadingSectionHeading(c.getString(1).orEmpty()),
                    subjectName = if (c.isNull(2)) null else subjectNames[c.getLong(2)],
                    figures = figuresFor(c.getString(3)),
                )
            }
        }
        return out
    }

    /** Resolve the related items (MCQs/flashcards) to lightweight, tappable references (no content). */
    private fun relatedFor(type: String, idsJson: String?): List<RelatedItem> {
        val refIds = parseLongArray(idsJson)
        if (refIds.isEmpty()) return emptyList()
        val placeholders = refIds.joinToString(",") { "?" }
        val meta = HashMap<Long, Pair<Int, Long?>>() // ref_id -> (importance, subject_id)
        db.rawQuery(
            "SELECT ref_id, importance, subject_id FROM feed_items WHERE item_type = ? AND ref_id IN ($placeholders)",
            (listOf(type) + refIds.map { it.toString() }).toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) meta[c.getLong(0)] = c.getInt(1) to (if (c.isNull(2)) null else c.getLong(2))
        }
        // Preserve the pack's authored order; keep only items that are actually playable in this pack.
        return refIds.mapNotNull { rid ->
            val m = meta[rid] ?: return@mapNotNull null
            RelatedItem(type, rid, m.first, m.second?.let { subjectNames[it] })
        }
    }

    // ---- in-place item viewer (mcq / flashcard only — the related types) ---------------------------

    /** Load one MCQ or flashcard for the in-place reference viewer. No FSRS (read-only, per A4 decision). */
    fun loadCardByRef(type: String, refId: Long): FeedCard? {
        val (importance, score, subjectId) = feedItemMeta(type, refId) ?: return null
        val subject = subjectId?.let { subjectNames[it] }
        return when (type) {
            "mcq" -> loadMcq(refId, importance, score, subject)
            "flashcard" -> loadFlashcard(refId, importance, score, subject)
            else -> null
        }
    }

    private fun feedItemMeta(type: String, refId: Long): Triple<Int, Double, Long?>? =
        db.rawQuery(
            "SELECT importance, importance_score, subject_id FROM feed_items WHERE item_type = ? AND ref_id = ? LIMIT 1",
            arrayOf(type, refId.toString()),
        ).use { c ->
            if (c.moveToFirst()) Triple(c.getInt(0), c.getDouble(1), if (c.isNull(2)) null else c.getLong(2)) else null
        }

    private fun loadMcq(refId: Long, importance: Int, score: Double, subject: String?): FeedCard.Mcq? =
        db.rawQuery(
            """
            SELECT id, stem_md, opt_a, opt_b, opt_c, opt_d, opt_e, correct_idx, explanation_md,
                   key_conflict, key_conflict_note, pyq_years_json, importance_signals_json, figure_id
            FROM mcqs WHERE id = ?
            """.trimIndent(), arrayOf(refId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val opts = listOf(c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6))
                .filterNotNull().filter { it.isNotBlank() }
            FeedCard.Mcq(
                itemId = c.getLong(0), importance = importance, importanceScore = score,
                importanceSignals = parseSignals(c.getString(12)), subjectName = subject,
                pyqYears = parseIntArray(c.getString(11)), due = null, stability = null,
                stemMd = c.getString(1).orEmpty(), options = opts, correctIdx = c.getInt(7),
                explanationMd = c.getString(8).orEmpty(),
                perOptionWhyWrong = whyWrong(refId, opts.size),
                keyConflict = c.getInt(9) == 1, keyConflictNote = c.getString(10),
                figure = figureById(if (c.isNull(13)) null else c.getLong(13)),
            )
        }

    private fun loadFlashcard(refId: Long, importance: Int, score: Double, subject: String?): FeedCard.Flashcard? =
        db.rawQuery(
            """SELECT f.front_md, f.back_md, f.is_cloze, f.importance_signals_json, a.figure_id
               FROM flashcards f LEFT JOIN atoms a ON a.id = f.atom_id WHERE f.id = ?""",
            arrayOf(refId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            FeedCard.Flashcard(
                itemId = refId, importance = importance, importanceScore = score,
                importanceSignals = parseSignals(c.getString(3)), subjectName = subject,
                due = null, stability = null,
                frontMd = c.getString(0).orEmpty(), backMd = c.getString(1).orEmpty(),
                isCloze = c.getInt(2) == 1, figure = figureById(if (c.isNull(4)) null else c.getLong(4)),
            )
        }

    private fun whyWrong(mcqId: Long, optionCount: Int): List<String?> {
        val byIdx = HashMap<Int, String>()
        db.rawQuery("SELECT option_idx, why_wrong FROM mcq_distractors WHERE mcq_id = ?",
            arrayOf(mcqId.toString())).use { c ->
            while (c.moveToNext()) byIdx[c.getInt(0)] = c.getString(1).orEmpty()
        }
        return (0 until optionCount).map { byIdx[it] }
    }

    // ---- body_md structuring (avoid double-rendering the page + its sections) ----------------------

    /** The page preamble: lines before the first `## ` section, with the leading `# title` dropped. */
    private fun preambleOf(bodyMd: String): String {
        val lines = bodyMd.split("\n")
        val firstSection = lines.indexOfFirst { it.trimStart().startsWith("## ") }
        val head = if (firstSection >= 0) lines.subList(0, firstSection) else lines
        return head.filterNot {
            val t = it.trimStart()
            t.startsWith("# ") && !t.startsWith("## ")   // the H1 page title (shown in the header)
        }.joinToString("\n").trim()
    }

    /** Drop a section body's own leading `## heading` line (the heading is the tappable row). */
    private fun stripLeadingSectionHeading(bodyMd: String): String {
        val lines = bodyMd.split("\n").toMutableList()
        var i = 0
        while (i < lines.size && lines[i].isBlank()) i++
        if (i < lines.size && lines[i].trimStart().startsWith("## ")) lines.removeAt(i)
        return lines.joinToString("\n").trim()
    }

    // ---- figures + json helpers --------------------------------------------------------------------

    private fun figuresFor(idsJson: String?): List<FeedCard.Figure> =
        parseLongArray(idsJson).mapNotNull { figureById(it) }

    /** Resolve a figure id to an on-disk file (verified present), mirroring FeedRepository (D12/D5). */
    private fun figureById(figureId: Long?): FeedCard.Figure? {
        if (figureId == null) return null
        return db.rawQuery("SELECT path, caption_md FROM figures WHERE id = ?",
            arrayOf(figureId.toString())).use { c ->
            if (!c.moveToFirst()) return null
            val rel = c.getString(0) ?: return null
            val f = File(packDir, rel)
            if (f.isFile) FeedCard.Figure(f.path, c.getString(1)) else null
        }
    }

    private fun parseStringArray(json: String?): List<String> = try {
        if (json.isNullOrBlank()) emptyList() else JSONArray(json).let { a ->
            (0 until a.length()).map { a.getString(it) }
        }
    } catch (e: Exception) { emptyList() }

    private fun parseLongArray(json: String?): List<Long> = try {
        if (json.isNullOrBlank()) emptyList() else JSONArray(json).let { a ->
            (0 until a.length()).map { a.getLong(it) }
        }
    } catch (e: Exception) { emptyList() }

    private fun parseIntArray(json: String?): List<Int> = try {
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

/** A Library row — enough to list + rank, no body. */
data class DossierListItem(
    val topicPageId: Long,
    val title: String,
    val subjects: List<String>,
    val importance: Int,
    val importanceScore: Double,
)

/** A fully-loaded dossier (§8 A4). */
data class Dossier(
    val topicPageId: Long,
    val title: String,
    val bodyMd: String,
    val importance: Int,
    val importanceScore: Double,
    val importanceSignals: ImportanceSignals?,
    val subjectsCovered: List<String>,
    val figures: List<FeedCard.Figure>,
    val sections: List<DossierSection>,
    val relatedMcqs: List<RelatedItem>,
    val relatedFlashcards: List<RelatedItem>,
)

/** One collapsible per-subject section of a dossier (`topic_sections`). */
data class DossierSection(
    val heading: String,
    val bodyMd: String,
    val subjectName: String?,
    val figures: List<FeedCard.Figure>,
)

/** A tappable link to a related MCQ/flashcard — metadata only, opened in the in-place viewer. */
data class RelatedItem(
    val type: String,
    val refId: Long,
    val importance: Int,
    val subjectName: String?,
)
