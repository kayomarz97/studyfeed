package dev.kayomarz.studyfeed.feed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A7 cycle 3 (P2): the PURE parts of the session config — auto-name + the null/blank fallbacks. The
 * JSON round-trip uses `org.json` (an Android stub that throws in a plain JVM test), so it lives in
 * `SessionConfigRoundTripTest` (instrumented), like [FeedStateJson]'s serialisation.
 */
class SessionConfigTest {

    @Test fun blank_or_null_json_is_the_default_query() {
        assertEquals(FeedQuery(), SessionConfig.fromJson(null))
        assertEquals(FeedQuery(), SessionConfig.fromJson(""))
    }

    @Test fun auto_name_describes_the_picks() {
        val q = FeedQuery(filter = FeedFilter(subjectId = 1L, importanceExact = 5, pyqOnly = true))
        assertEquals("Cardiology · 5★ · PYQ", SessionConfig.autoName(q) { if (it == 1L) "Cardiology" else null })
        assertEquals("Mixed — everything", SessionConfig.autoName(FeedQuery()) { null })
    }

    @Test fun auto_name_labels_content_and_focus_sets() {
        val recall = FeedQuery(filter = FeedFilter(itemTypesIn = listOf("mcq", "flashcard"), leechesOnly = true))
        assertEquals("Recall · Leeches", SessionConfig.autoName(recall) { null })
    }
}
