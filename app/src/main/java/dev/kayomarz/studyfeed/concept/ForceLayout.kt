package dev.kayomarz.studyfeed.concept

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A tiny **Fruchterman–Reingold** force-directed layout (BUILD_PROMPT §8 A5) for the concept map's
 * ego-graph — only ever ≤ ~15 nodes (a focus + its capped neighbours), so the O(n²) repulsion pass is
 * trivially cheap.
 *
 * **Pure and deterministic:** no Android, no Compose, and **no `Math.random`** — initial positions are
 * seeded on a circle by index, so the same graph always lays out the same way (reproducible + unit-
 * testable). Node 0 is the **focus** and is **pinned to the centre**; the neighbours relax around it.
 *
 * Coordinates come out in a virtual space centred on (0,0), roughly within [-1, 1]; the Canvas scales
 * them to the screen. Returns [Pt] (not Compose `Offset`) to stay dependency-free for tests.
 */
object ForceLayout {

    data class Pt(val x: Float, val y: Float)

    /**
     * @param n number of nodes (index 0 = focus, pinned centre).
     * @param edges undirected index pairs (i, j), 0 ≤ i,j < n.
     * @param iterations relaxation steps (100 is plenty for ≤15 nodes).
     */
    fun layout(n: Int, edges: List<Pair<Int, Int>>, iterations: Int = 160): List<Pt> {
        if (n <= 0) return emptyList()
        if (n == 1) return listOf(Pt(0f, 0f))

        // Ideal edge length k = sqrt(area / n). A7: a larger virtual area spreads the ego-graph out so
        // neighbours sit well clear of the focus and of each other (the "compact / labels collide near
        // the centre" polish). The Canvas rescales to fit regardless of magnitude.
        val k = sqrt(2.4 / n).toFloat().coerceAtLeast(0.34f)
        // Seed on a circle (deterministic). Focus (0) at centre; a wider seed ring speeds the spread.
        val pos = Array(n) { i ->
            if (i == 0) floatArrayOf(0f, 0f)
            else {
                val a = 2.0 * Math.PI * (i - 1) / (n - 1)
                floatArrayOf((0.8 * cos(a)).toFloat(), (0.8 * sin(a)).toFloat())
            }
        }
        var temp = 0.35f
        val cooling = temp / (iterations + 1)

        repeat(iterations) {
            val disp = Array(n) { floatArrayOf(0f, 0f) }
            // Repulsion between every pair.
            for (i in 0 until n) for (j in i + 1 until n) {
                var dx = pos[i][0] - pos[j][0]
                var dy = pos[i][1] - pos[j][1]
                var dist = sqrt(dx * dx + dy * dy)
                if (dist < 1e-4f) { dx = ((i - j) % 3) * 1e-3f + 1e-3f; dy = 1e-3f; dist = sqrt(dx * dx + dy * dy) }
                val rep = k * k / dist
                val ux = dx / dist; val uy = dy / dist
                disp[i][0] += ux * rep; disp[i][1] += uy * rep
                disp[j][0] -= ux * rep; disp[j][1] -= uy * rep
            }
            // Attraction along edges.
            for ((a, b) in edges) {
                if (a !in 0 until n || b !in 0 until n || a == b) continue
                var dx = pos[a][0] - pos[b][0]
                var dy = pos[a][1] - pos[b][1]
                var dist = sqrt(dx * dx + dy * dy)
                if (dist < 1e-4f) dist = 1e-4f
                val att = dist * dist / k
                val ux = dx / dist; val uy = dy / dist
                disp[a][0] -= ux * att; disp[a][1] -= uy * att
                disp[b][0] += ux * att; disp[b][1] += uy * att
            }
            // Apply, capped by temperature. Focus (0) stays pinned.
            for (i in 1 until n) {
                val d = sqrt(disp[i][0] * disp[i][0] + disp[i][1] * disp[i][1])
                if (d > 1e-6f) {
                    val cap = minOf(d, temp)
                    pos[i][0] += disp[i][0] / d * cap
                    pos[i][1] += disp[i][1] / d * cap
                }
            }
            temp -= cooling
        }
        return (0 until n).map { Pt(pos[it][0], pos[it][1]) }
    }
}
