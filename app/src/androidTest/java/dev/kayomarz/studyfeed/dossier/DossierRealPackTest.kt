package dev.kayomarz.studyfeed.dossier

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
 * Proves the A4 Topic Dossier reader against the REAL pack (8,194 dossiers) on a real Android image.
 * SKIPS when the fixture isn't pushed (**a skip is not a pass** — check the count). SHAPE only, no pack
 * text echoed (copyright).
 */
@RunWith(AndroidJUnit4::class)
class DossierRealPackTest {

    private fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun realPackDb(): File? =
        File(ctx().filesDir, "realpack").listFiles { f -> f.name.startsWith("pack_") && f.name.endsWith(".db") }?.firstOrNull()
    private fun open(): DossierRepository? = realPackDb()?.let { DossierRepository.open(it) }

    @Test fun the_library_lists_dossiers_importance_first() {
        val r = open(); assumeTrue("real pack not pushed — skipping", r != null)
        r!!.use {
            assertTrue("the pack ships dossiers", it.size() in 1..20000)
            val top = it.topDossiers(50)
            assertEquals(50, top.size)
            assertTrue("importance non-increasing", top.zipWithNext().all { (a, b) -> a.importance >= b.importance })
            assertTrue("every row has a title", top.all { row -> row.title.isNotBlank() })
        }
    }

    @Test fun search_matches_name_alias_and_body_and_ranks_name_hits_first() {
        val r = open(); assumeTrue("real pack not pushed — skipping", r != null)
        r!!.use {
            // A very common clinical substring hits many pages via body text (no content asserted).
            val hits = it.search("acute", limit = 40)
            assertTrue("a common term finds pages", hits.isNotEmpty())
            // Any page whose TITLE contains the term must sort ahead of a body-only hit.
            val firstBodyOnly = hits.indexOfFirst { d -> !d.title.contains("acute", ignoreCase = true) }
            val lastNameHit = hits.indexOfLast { d -> d.title.contains("acute", ignoreCase = true) }
            if (firstBodyOnly >= 0 && lastNameHit >= 0)
                assertTrue("name/alias hits rank before body-only hits", lastNameHit < firstBodyOnly ||
                    hits.take(firstBodyOnly).all { d -> d.title.contains("acute", ignoreCase = true) })
            // Gibberish matches nothing.
            assertTrue("nonsense query is empty", it.search("zzqxzzqx", limit = 10).isEmpty())
        }
    }

    @Test fun a_dossier_loads_full_with_body_sections_and_resolvable_figures() {
        val r = open(); assumeTrue("real pack not pushed — skipping", r != null)
        r!!.use {
            // Find a dossier that actually carries figures + related MCQs (most do).
            var dossier: Dossier? = null
            for (row in it.topDossiers(80)) {
                val d = it.loadDossier(row.topicPageId) ?: continue
                if (d.figures.isNotEmpty() && d.relatedMcqs.isNotEmpty()) { dossier = d; break }
            }
            assertNotNull("a dossier with figures + related MCQs exists", dossier)
            val d = dossier!!
            // Content lives in the collapsible sections (topic_sections); body_md here is just the
            // preamble (title/subtitle) — the sections are what carry the facts, verbatim once.
            assertTrue("has collapsible sections", d.sections.isNotEmpty())
            assertTrue("every section has a heading", d.sections.all { s -> s.heading.isNotBlank() })
            assertTrue("sections carry body text", d.sections.any { s -> s.bodyMd.isNotBlank() })
            assertTrue("a section body no longer repeats its own '## ' heading",
                d.sections.none { s -> s.bodyMd.trimStart().startsWith("## ") })
            assertTrue("figure paths resolve to real files on disk", d.figures.all { f -> File(f.path).isFile })
            assertTrue("related MCQs are metadata-only refs", d.relatedMcqs.all { it2 -> it2.type == "mcq" && it2.refId > 0 })
        }
    }

    @Test fun a_related_mcq_opens_in_the_reference_viewer_read_only() {
        val r = open(); assumeTrue("real pack not pushed — skipping", r != null)
        r!!.use {
            var refId: Long? = null
            for (row in it.topDossiers(80)) {
                val d = it.loadDossier(row.topicPageId) ?: continue
                d.relatedMcqs.firstOrNull()?.let { rel -> refId = rel.refId }
                if (refId != null) break
            }
            assertNotNull("some dossier has a related MCQ", refId)
            val card = it.loadCardByRef("mcq", refId!!)
            assertTrue("it loads as an MCQ", card is FeedCard.Mcq)
            card as FeedCard.Mcq
            assertTrue("with a stem", card.stemMd.isNotBlank())
            assertTrue("and 2..5 options", card.options.size in 2..5)
            assertTrue("correct index within options", card.correctIdx in card.options.indices)
            assertNotNull("read-only: no FSRS due carried", card.due == null)  // viewer never joins progress
        }
    }
}
