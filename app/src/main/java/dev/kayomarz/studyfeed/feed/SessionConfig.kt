package dev.kayomarz.studyfeed.feed

import org.json.JSONArray
import org.json.JSONObject

/**
 * (De)serialises a [FeedQuery] to/from the JSON stored in a named study session's `config_json`
 * (A7 cycle 3, P2), plus the auto-name derived from the picks. Dependency-free (org.json is on the
 * platform). A saved session = its picked filters/mode ([FeedQuery]) + a resume cursor; this handles the
 * config half so a session reconstructs the exact feed it configured.
 */
object SessionConfig {

    fun toJson(q: FeedQuery): String = JSONObject().apply {
        put("sort", q.sort.name)
        val f = q.filter
        f.itemType?.let { put("itemType", it) }
        f.itemTypesIn?.takeIf { it.isNotEmpty() }?.let { put("itemTypesIn", JSONArray(it)) }
        f.importanceExact?.let { put("importanceExact", it) }
        f.importanceMin?.let { put("importanceMin", it) }
        if (f.pyqOnly) put("pyqOnly", true)
        if (f.starredOnly) put("starredOnly", true)
        if (f.mistakesOnly) put("mistakesOnly", true)
        if (f.leechesOnly) put("leechesOnly", true)
        f.subjectId?.let { put("subjectId", it) }
        f.tagKey?.let { put("tagKey", it) }
    }.toString()

    fun fromJson(json: String?): FeedQuery {
        if (json.isNullOrBlank()) return FeedQuery()
        return try {
            val o = JSONObject(json)
            val sort = runCatching { FeedQuery.Sort.valueOf(o.optString("sort", "SMART")) }.getOrDefault(FeedQuery.Sort.SMART)
            val types = o.optJSONArray("itemTypesIn")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            FeedQuery(
                sort = sort,
                filter = FeedFilter(
                    itemType = o.optStringOrNull("itemType"),
                    itemTypesIn = types,
                    importanceExact = if (o.has("importanceExact")) o.getInt("importanceExact") else null,
                    importanceMin = if (o.has("importanceMin")) o.getInt("importanceMin") else null,
                    pyqOnly = o.optBoolean("pyqOnly", false),
                    starredOnly = o.optBoolean("starredOnly", false),
                    mistakesOnly = o.optBoolean("mistakesOnly", false),
                    leechesOnly = o.optBoolean("leechesOnly", false),
                    subjectId = if (o.has("subjectId")) o.getLong("subjectId") else null,
                    tagKey = o.optStringOrNull("tagKey"),
                ),
            )
        } catch (e: Exception) {
            FeedQuery()
        }
    }

    /** A human name from the picks, e.g. "Cardiology · MCQs · 5★ · PYQ", or "Mixed — everything". */
    fun autoName(q: FeedQuery, subjectName: (Long) -> String?): String {
        val parts = mutableListOf<String>()
        q.filter.subjectId?.let { subjectName(it)?.let(parts::add) }
        contentLabel(q.filter)?.let(parts::add)
        when {
            q.filter.importanceExact == 5 -> parts += "5★"
            q.filter.importanceMin == 4 -> parts += "4★+"
            q.filter.importanceExact != null -> parts += "${q.filter.importanceExact}★"
            q.filter.importanceMin != null -> parts += "${q.filter.importanceMin}★+"
        }
        if (q.filter.pyqOnly) parts += "PYQ"
        if (q.filter.starredOnly) parts += "Starred"
        if (q.filter.mistakesOnly) parts += "Mistakes"
        if (q.filter.leechesOnly) parts += "Leeches"
        if (parts.isEmpty()) {
            return when (q.sort) {
                FeedQuery.Sort.SUBJECT -> "By subject"
                FeedQuery.Sort.PRIORITY -> "Weakest first"
                FeedQuery.Sort.RANDOM -> "Shuffle"
                else -> "Mixed — everything"
            }
        }
        return parts.joinToString(" · ")
    }

    private fun contentLabel(f: FeedFilter): String? {
        f.itemType?.let { return it.replaceFirstChar(Char::uppercase) + "s" }
        val t = f.itemTypesIn?.toSet() ?: return null
        return when {
            t.isEmpty() -> null
            t == setOf("mcq") -> "MCQs"
            t == setOf("flashcard", "reel") -> "Cards & reels"
            t == setOf("mcq", "flashcard") -> "Recall"
            t == setOf("reel", "blob") -> "Skim"
            t == setOf("blob") -> "Passages"
            t == setOf("flashcard") -> "Flashcards"
            t == setOf("reel") -> "Reels"
            else -> t.joinToString("+") { it.replaceFirstChar(Char::uppercase) }
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null
}
