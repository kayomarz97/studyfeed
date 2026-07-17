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
    fun topConcepts(limit: Int = 40): List<ConceptRef> =
        query("SELECT id, name, importance, centrality FROM concepts ORDER BY centrality DESC, importance DESC LIMIT ?",
            arrayOf(limit.toString()))

    /** Search concepts by name/alias (importance ↓, then centrality). */
    fun search(q: String, limit: Int = 40): List<ConceptRef> {
        val query = q.trim()
        if (query.isBlank()) return topConcepts(limit)
        val like = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
        return query(
            """SELECT id, name, importance, centrality FROM concepts
               WHERE name LIKE ? ESCAPE '\' OR aliases_json LIKE ? ESCAPE '\'
               ORDER BY importance DESC, centrality DESC LIMIT ?""".trimIndent(),
            arrayOf(like, like, limit.toString()),
        )
    }

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
