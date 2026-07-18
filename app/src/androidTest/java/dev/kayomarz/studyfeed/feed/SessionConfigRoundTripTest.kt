package dev.kayomarz.studyfeed.feed

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A7 cycle 3 (P2): the session config JSON round-trip — on-device so `org.json` is real (it's an Android
 * stub that throws in a plain JVM test). If a filter field doesn't survive the trip, resume restores the
 * wrong feed.
 */
@RunWith(AndroidJUnit4::class)
class SessionConfigRoundTripTest {

    @Test fun round_trips_every_filter_field() {
        val q = FeedQuery(
            sort = FeedQuery.Sort.PRIORITY,
            filter = FeedFilter(
                itemTypesIn = listOf("mcq", "flashcard"), importanceMin = 4, pyqOnly = true,
                starredOnly = true, mistakesOnly = true, leechesOnly = true, subjectId = 7L, tagKey = "cardio",
            ),
        )
        val back = SessionConfig.fromJson(SessionConfig.toJson(q))
        assertEquals(q.sort, back.sort)
        assertEquals(q.filter, back.filter)
    }

    @Test fun corrupt_json_falls_back_to_the_default_query() {
        assertEquals(FeedQuery(), SessionConfig.fromJson("{not valid json"))
    }
}
