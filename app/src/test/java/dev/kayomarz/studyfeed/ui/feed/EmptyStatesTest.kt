package dev.kayomarz.studyfeed.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "which empty message" decision, tested as a pure function (no device). This is the guarantee
 * that a lean pack degrades HONESTLY rather than into a blank screen: an absent capability must read as
 * factual ("this pack doesn't include X"), a filtered-out section as "clear a filter", and a genuinely
 * finished section as encouraging.
 */
class EmptyStatesTest {

    @Test fun absent_capability_is_ABSENT_regardless_of_filter() {
        // The pack never had this content — a filter being on or off cannot change that.
        assertEquals(EmptyCase.ABSENT, EmptyStates.decide(capabilityPresent = false, filterActive = false))
        assertEquals(EmptyCase.ABSENT, EmptyStates.decide(capabilityPresent = false, filterActive = true))
    }

    @Test fun present_but_filtered_out_is_NOTHING_MATCHES() {
        assertEquals(EmptyCase.NOTHING_MATCHES,
            EmptyStates.decide(capabilityPresent = true, filterActive = true))
    }

    @Test fun present_and_unfiltered_is_NOTHING_DUE() {
        assertEquals(EmptyCase.NOTHING_DUE,
            EmptyStates.decide(capabilityPresent = true, filterActive = false))
    }

    @Test fun the_full_truth_table_is_covered() {
        // Every combination has exactly one answer — no blank-screen gap.
        val table = mapOf(
            (false to false) to EmptyCase.ABSENT,
            (false to true) to EmptyCase.ABSENT,
            (true to false) to EmptyCase.NOTHING_DUE,
            (true to true) to EmptyCase.NOTHING_MATCHES,
        )
        for ((input, expected) in table) {
            assertEquals("caps=${input.first} filter=${input.second}",
                expected, EmptyStates.decide(input.first, input.second))
        }
    }
}
