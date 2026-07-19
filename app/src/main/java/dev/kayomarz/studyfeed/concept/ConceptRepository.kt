package dev.kayomarz.studyfeed.concept

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import dev.kayomarz.studyfeed.feed.FeedCard
import java.io.Closeable
import java.io.File

/**
 * Reads the concept graph (BUILD_PROMPT §8 A5) from the pack — pack-only, READ-ONLY, like
 * [dev.kayomarz.studyfeed.dossier.DossierRepository]. **Nothing generated.**
 *
 * The full graph is 37,795 concepts / 67,795 edges — unrenderable on a phone — so every read here is
 * **ego-scoped**: a focus concept plus its top-K neighbours. `concepts.summary_md` ships EMPTY, so a
 * node's "summary" is its typed edges + a link to its dossier (`topic_pages`, 8,194 concepts have one).
 */
class ConceptRepository private constructor(
    private val db: SQLiteDatabase,
    private val subjectNames: Map<Long, String>,
) : Closeable {

    companion object {
        fun open(packDbFile: File): ConceptRepository? {
            if (!packDbFile.isFile) return null
            return try {
                val db = SQLiteDatabase.openDatabase(packDbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                val subjects = buildMap {
                    db.rawQuery("SELECT id, name FROM subjects", null).use { c ->
                        while (c.moveToNext()) put(c.getLong(0), c.getString(1).orEmpty())
                    }
                }
                ConceptRepository(db, subjects)
            } catch (e: SQLiteException) { null }
        }
    }

    /** The highest-centrality hubs — the default landing focus + "jump to" suggestions. */
    fun topConcepts(limit: Int = 40): List<ConceptRef> = list("", ConceptFilter(), limit)

    /** Search concepts by name/alias (importance ↓, then centrality). */
    fun search(q: String, limit: Int = 40): List<ConceptRef> = list(q, ConceptFilter(), limit)

    /** The subjects the pack's dossiers cover — for the concept-list subject filter (A7). */
    fun subjects(): List<String> = subjectNames.values.filter { it.isNotBlank() }.sorted()

    /**
     * A7 concept-list filters, mirroring the Library. Importance is direct on the concept; **subject** and
     * **PYQ** are derived through the concept's topic page (`topic_pages.concept_id`) — so those filters
     * narrow to concepts that HAVE a dossier (the meaningful ones; ~8,194 of 37,795).
     */
    data class ConceptFilter(
        val importanceExact: Int? = null,
        val importanceMin: Int? = null,
        val subject: String? = null,
        val pyqOnly: Boolean = false,
    ) {
        val isAll get() = importanceExact == null && importanceMin == null && subject == null && !pyqOnly
    }

    /** Search + filter concepts (name/alias · importance · subject · PYQ). PYQ filtered in Kotlin. */
    fun list(q: String, filter: ConceptFilter = ConceptFilter(), limit: Int = 40): List<ConceptRef> {
        val query = q.trim()
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        if (query.isNotBlank()) {
            val like = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
            clauses += "(c.name LIKE ? ESCAPE '\\' OR c.aliases_json LIKE ? ESCAPE '\\')"
            args += like; args += like
        }
        filter.importanceExact?.let { clauses += "c.importance = ?"; args += it.toString() }
        filter.importanceMin?.let { clauses += "c.importance >= ?"; args += it.toString() }
        filter.subject?.let {
            clauses += "EXISTS(SELECT 1 FROM topic_pages tp WHERE tp.concept_id = c.id AND tp.subjects_covered_json LIKE ?)"
            args += "%\"$it\"%"
        }
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        val order = if (query.isNotBlank()) "c.importance DESC, c.centrality DESC" else "c.centrality DESC, c.importance DESC"
        val sqlLimit = if (filter.pyqOnly) 1500 else limit
        val out = ArrayList<ConceptRef>()
        db.rawQuery(
            "SELECT c.id, c.name, c.importance, c.centrality FROM concepts c $where ORDER BY $order LIMIT ?",
            (args + listOf(sqlLimit.toString())).toTypedArray(),
        ).use { cur ->
            while (cur.moveToNext()) {
                if (filter.pyqOnly && cur.getLong(0) !in pyqConceptIds) continue
                out += ConceptRef(cur.getLong(0), cur.getString(1).orEmpty(), cur.getInt(2), cur.getDouble(3))
                if (out.size >= limit) break
            }
        }
        return out
    }

    /** Concept ids whose dossier has ≥1 PYQ related MCQ (A7 "PYQ" concept filter). Built once. */
    private val pyqConceptIds: Set<Long> by lazy {
        val pyqMcqs = HashSet<Long>()
        try {
            db.rawQuery("SELECT id FROM mcqs WHERE is_pyq = 1", null).use { c ->
                while (c.moveToNext()) pyqMcqs += c.getLong(0)
            }
        } catch (e: SQLiteException) { /* no mcqs */ }
        val out = HashSet<Long>()
        try {
            db.rawQuery("SELECT concept_id, related_mcq_ids_json FROM topic_pages WHERE concept_id IS NOT NULL", null).use { c ->
                while (c.moveToNext()) {
                    if (parseLongArray(c.getString(1)).any { it in pyqMcqs }) out += c.getLong(0)
                }
            }
        } catch (e: SQLiteException) { /* no dossiers */ }
        out
    }

    private fun parseLongArray(json: String?): List<Long> = try {
        if (json.isNullOrBlank()) emptyList()
        else org.json.JSONArray(json).let { a -> (0 until a.length()).map { a.getLong(it) } }
    } catch (e: Exception) { emptyList() }

    private fun query(sql: String, args: Array<String>): List<ConceptRef> {
        val out = ArrayList<ConceptRef>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext())
                out += ConceptRef(c.getLong(0), c.getString(1).orEmpty(), c.getInt(2), c.getDouble(3))
        }
        return out
    }

    /**
     * The ego-graph around [conceptId]: the focus node + up to [k] most-important **distinct**
     * neighbours (a hub can have 314 edges — cap them), plus how many were left off.
     */
    fun ego(conceptId: Long, k: Int = 12): EgoGraph? {
        val focus = node(conceptId) ?: return null
        val seen = LinkedHashMap<Long, Neighbour>()   // distinct by neighbour id; keeps the first (top) edge
        db.rawQuery(
            """
            SELECT e.id, e.relation, e.src_concept_id, e.dst_concept_id,
                   nc.id, nc.name, nc.importance, nc.centrality
            FROM concept_edges e
            JOIN concepts nc ON nc.id = (CASE WHEN e.src_concept_id = ? THEN e.dst_concept_id ELSE e.src_concept_id END)
            WHERE (e.src_concept_id = ? OR e.dst_concept_id = ?) AND nc.id <> ?
            ORDER BY nc.importance DESC, nc.centrality DESC, e.id
            """.trimIndent(),
            arrayOf(conceptId.toString(), conceptId.toString(), conceptId.toString(), conceptId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val nid = c.getLong(4)
                if (!seen.containsKey(nid)) {
                    seen[nid] = Neighbour(
                        concept = ConceptNode(nid, c.getString(5).orEmpty(), c.getInt(6), c.getDouble(7), hasDossier(nid)),
                        relation = c.getString(1).orEmpty(),
                        edgeId = c.getLong(0),
                        outgoing = c.getLong(2) == conceptId,
                    )
                }
            }
        }
        val all = seen.values.toList()
        return EgoGraph(focus, all.take(k), (all.size - k).coerceAtLeast(0))
    }

    private fun node(conceptId: Long): ConceptNode? =
        db.rawQuery("SELECT id, name, importance, centrality FROM concepts WHERE id = ?",
            arrayOf(conceptId.toString())).use { c ->
            if (c.moveToFirst())
                ConceptNode(c.getLong(0), c.getString(1).orEmpty(), c.getInt(2), c.getDouble(3), hasDossier(c.getLong(0)))
            else null
        }

    /** The dossier (topic_page) id for a concept, if it has one — enables "Open dossier" → A4. */
    private fun hasDossier(conceptId: Long): Long? =
        db.rawQuery("SELECT id FROM topic_pages WHERE concept_id = ? LIMIT 1", arrayOf(conceptId.toString())).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    /** The interlink-quiz refIds for edges touching [conceptId] (only ~2,998 edges are quizzable). */
    fun quizRefsForConcept(conceptId: Long): List<Long> {
        val out = ArrayList<Long>()
        db.rawQuery(
            """SELECT iq.id FROM interlink_quizzes iq
               JOIN concept_edges e ON e.id = iq.edge_id
               WHERE e.src_concept_id = ? OR e.dst_concept_id = ?""".trimIndent(),
            arrayOf(conceptId.toString(), conceptId.toString()),
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    /** Load one interlink quiz as a card for the read-only viewer (no FSRS). */
    fun loadInterlink(refId: Long): FeedCard.Interlink? =
        db.rawQuery(
            """SELECT iq.question_md, iq.answer_md, fi.importance, fi.importance_score, fi.subject_id
               FROM interlink_quizzes iq
               LEFT JOIN feed_items fi ON fi.item_type = 'interlink' AND fi.ref_id = iq.id
               WHERE iq.id = ?""".trimIndent(),
            arrayOf(refId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            FeedCard.Interlink(
                itemId = refId,
                importance = if (c.isNull(2)) 1 else c.getInt(2),
                importanceScore = if (c.isNull(3)) 0.0 else c.getDouble(3),
                importanceSignals = null,
                subjectName = if (c.isNull(4)) null else subjectNames[c.getLong(4)],
                due = null, stability = null,
                questionMd = c.getString(0).orEmpty(), answerMd = c.getString(1).orEmpty(),
            )
        }

    override fun close() { runCatching { db.close() } }
}

/** A concept search/list row. */
data class ConceptRef(val id: Long, val name: String, val importance: Int, val centrality: Double)

/** A graph node (concept) with its dossier link, if any. */
data class ConceptNode(
    val id: Long, val name: String, val importance: Int, val centrality: Double, val dossierPageId: Long?,
)

/** A focus concept's neighbour: the connected concept, the relation, its edge, and direction. */
data class Neighbour(val concept: ConceptNode, val relation: String, val edgeId: Long, val outgoing: Boolean)

/** The ego-graph: a focus + its top-K distinct neighbours (+ how many more were capped off). */
data class EgoGraph(val focus: ConceptNode, val neighbours: List<Neighbour>, val moreCount: Int)
