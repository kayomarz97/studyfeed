package dev.kayomarz.studyfeed.feed

import org.json.JSONArray
import org.json.JSONObject

/**
 * (De)serialises [FeedScheduler.FeedState] to a single JSON string stored in the progress DB's
 * `feed_state` table (under one key). Kept tiny and dependency-free (org.json is in the platform).
 *
 * Living in the progress DB means the engine's memory — the recent-type ring buffer, the daily drip
 * counters, the keyset cursors and pending blob quiz-backs — **rides along in the §11e export/import**,
 * so a user who restores their progress resumes the feed exactly where they left it.
 */
object FeedStateJson {

    fun toJson(s: FeedScheduler.FeedState): String = JSONObject().apply {
        put("recentTypes", JSONArray(s.recentTypes))
        put("dripDate", s.dripDate ?: JSONObject.NULL)
        put("newMcqToday", s.newMcqToday)
        put("newOtherToday", s.newOtherToday)
        putOpt("mcqCursorImp", s.mcqCursorImp)
        putOpt("mcqCursorId", s.mcqCursorId)
        putOpt("otherCursorImp", s.otherCursorImp)
        putOpt("otherCursorId", s.otherCursorId)
        put("shuffleSeed", s.shuffleSeed)
        put("globalIndex", s.globalIndex)
        put("pendingBlobQuizzes", JSONArray().apply {
            s.pendingBlobQuizzes.forEach { p ->
                put(JSONObject().put("blobId", p.blobId).put("landAt", p.landAt))
            }
        })
    }.toString()

    fun fromJson(json: String?): FeedScheduler.FeedState {
        if (json.isNullOrBlank()) return FeedScheduler.FeedState()
        return try {
            val o = JSONObject(json)
            FeedScheduler.FeedState(
                recentTypes = o.optJSONArray("recentTypes").toStringList(),
                dripDate = o.optStringOrNull("dripDate"),
                newMcqToday = o.optInt("newMcqToday", 0),
                newOtherToday = o.optInt("newOtherToday", 0),
                mcqCursorImp = o.optIntOrNull("mcqCursorImp"),
                mcqCursorId = o.optLongOrNull("mcqCursorId"),
                otherCursorImp = o.optIntOrNull("otherCursorImp"),
                otherCursorId = o.optLongOrNull("otherCursorId"),
                shuffleSeed = o.optInt("shuffleSeed", 0),
                globalIndex = o.optInt("globalIndex", 0),
                pendingBlobQuizzes = o.optJSONArray("pendingBlobQuizzes").toPendingList(),
            )
        } catch (e: Exception) {
            // A corrupt state row must never brick the feed — start clean.
            FeedScheduler.FeedState()
        }
    }

    private fun JSONObject.putOpt(key: String, v: Int?) = put(key, v ?: JSONObject.NULL)
    private fun JSONObject.putOpt(key: String, v: Long?) = put(key, v ?: JSONObject.NULL)

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }
    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }

    private fun JSONArray?.toPendingList(): List<FeedScheduler.FeedState.Pending> {
        if (this == null) return emptyList()
        return (0 until length()).map {
            val p = getJSONObject(it)
            FeedScheduler.FeedState.Pending(p.getLong("blobId"), p.getInt("landAt"))
        }
    }
}
