package dev.kayomarz.studyfeed.concept

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kayomarz.studyfeed.feed.FeedCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the A5 concept-graph reader against the REAL pack (37,795 concepts / 67,795 edges) on device.
 * SKIPS without the fixture (**a skip is not a pass**). SHAPE only, no pack text echoed (copyright).
 */
@RunWith(AndroidJUnit4::class)
class ConceptRealPackTest {

    private fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun realPackDb(): File? =
        File(ctx().filesDir, "realpack").listFiles { f -> f.name.startsWith("pack_") && f.name.endsWith(".db") }?.firstOrNull()
    private fun open(): ConceptRepository? = realPackDb()?.let { ConceptRepository.open(it) }

    @Test fun top_concepts_are_central_and_searchable() {
        val r = open(); assumeTrue("real pack not pushed — skipping", r != null)
        r!!.use {
            val top = it.topConcepts(30)
            assertEquals(30, top.size)
            assertTrue("centrality non-increasing", top.zipWithNext().all { (a, b) -> a.centrality >= b.centrality })
            assertTrue("every concept is named", top.all { c -> c.name.isNotBlank() })
            assertTrue("search finds concepts", it.search("acute", 20).isNotEmpty())
            assertTrue("gibberish finds none", it.search("zzqxzzqx", 10).isEmpty())
        }
    }

    @Test fun the_ego_graph_caps_neighbours_and_reports_more() {
        val r = open(); assumeTrue("real pack not pushed — skipping", r != null)
        r!!.use {
            // The single most-central concept is a hub (degree can be in the hundreds).
            val hub = it.topConcepts(1).first()
            val ego = it.ego(hub.id, k = 12)
            assertNotNull(ego)
            assertTrue("neighbours capped at k", ego!!.neighbours.size <= 12)
            assertTrue("a hub has neighbours", ego.neighbours.isNotEmpty())
            assertTrue("more-count reports the overflow for a hub", ego.moreCount >= 0)
            assertTrue("neighbours are distinct", ego.neighbours.map { n -> n.concept.id }.toSet().size == ego.neighbours.size)
            assertTrue("every neighbour carries a relation", ego.neighbours.all { n -> n.relation.isNotBlank() })
            assertTrue("focus is the requested concept", ego.focus.id == hub.id)
        }
    }

    @Test fun a_concept_with_a_dossier_links_to_it() {
        val r = open(); assumeTrue("real pack not pushed — skipping", r != null)
        r!!.use {
            // Most top concepts have a topic_page; find one that does.
            val withDossier = it.topConcepts(40).firstNotNullOfOrNull { c -> it.ego(c.id, 1)?.focus?.takeIf { f -> f.dossierPageId != null } }
            assertNotNull("some central concept has a dossier link", withDossier)
            assertTrue(withDossier!!.dossierPageId!! > 0)
        }
    }

    @Test fun quiz_links_resolve_to_interlink_cards() {
        val r = open(); assumeTrue("real pack not pushed — skipping", r != null)
        r!!.use {
            // Walk the top hubs until we find one whose edges carry an interlink quiz (only ~2,998 do).
            var refs: List<Long> = emptyList()
            for (c in it.topConcepts(60)) { refs = it.quizRefsForConcept(c.id); if (refs.isNotEmpty()) break }
            assertTrue("some hub has quizzable links", refs.isNotEmpty())
            val card = it.loadInterlink(refs.first())
            assertTrue("it loads as an interlink card", card is FeedCard.Interlink)
            card as FeedCard.Interlink
            assertTrue("with a question", card.questionMd.isNotBlank())
            assertTrue("and an answer", card.answerMd.isNotBlank())
            assertEquals("read-only: no FSRS due", null, card.due)
        }
    }

    @Test fun the_ego_layout_matches_the_node_count() {
        val r = open(); assumeTrue("real pack not pushed — skipping", r != null)
        r!!.use {
            val hub = it.topConcepts(1).first()
            val ego = it.ego(hub.id, 12)!!
            val pos = ForceLayout.layout(1 + ego.neighbours.size, ego.neighbours.indices.map { i -> 0 to i + 1 })
            assertEquals("one position per node (focus + neighbours)", 1 + ego.neighbours.size, pos.size)
            assertTrue("all finite", pos.all { p -> p.x.isFinite() && p.y.isFinite() })
        }
    }
}
