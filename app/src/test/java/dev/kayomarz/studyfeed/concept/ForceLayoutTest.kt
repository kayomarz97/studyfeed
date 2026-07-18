package dev.kayomarz.studyfeed.concept

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The concept-map force layout, proven on the JVM. Deterministic (no RNG), finite, and it actually
 * spreads the ego-graph's nodes out with the focus pinned at the centre.
 */
class ForceLayoutTest {

    private fun star(n: Int) = (1 until n).map { 0 to it }   // focus 0 connected to each neighbour

    private fun dist(a: ForceLayout.Pt, b: ForceLayout.Pt) =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    @Test fun trivial_sizes() {
        assertTrue(ForceLayout.layout(0, emptyList()).isEmpty())
        assertEquals(listOf(ForceLayout.Pt(0f, 0f)), ForceLayout.layout(1, emptyList()))
    }

    @Test fun the_focus_is_pinned_at_the_centre() {
        val pos = ForceLayout.layout(8, star(8))
        assertEquals(ForceLayout.Pt(0f, 0f), pos[0])
    }

    @Test fun it_is_deterministic() {
        val a = ForceLayout.layout(10, star(10))
        val b = ForceLayout.layout(10, star(10))
        assertEquals("same graph lays out identically (no RNG)", a, b)
    }

    @Test fun all_positions_are_finite() {
        val pos = ForceLayout.layout(13, star(13))
        assertTrue(pos.all { it.x.isFinite() && it.y.isFinite() })
    }

    @Test fun neighbours_spread_out_and_sit_away_from_the_focus() {
        val n = 8
        val pos = ForceLayout.layout(n, star(n))
        // Every neighbour is pushed off the centre.
        for (i in 1 until n) assertTrue("neighbour $i not on top of focus", dist(pos[0], pos[i]) > 0.05f)
        // No two neighbours collapse onto the same point.
        for (i in 1 until n) for (j in i + 1 until n)
            assertTrue("neighbours $i,$j are distinct", dist(pos[i], pos[j]) > 1e-3f)
    }

    @Test fun positions_stay_within_a_sane_bound() {
        val pos = ForceLayout.layout(13, star(13))
        assertTrue("no runaway coordinates", pos.all { kotlin.math.abs(it.x) < 5f && kotlin.math.abs(it.y) < 5f })
    }
}
