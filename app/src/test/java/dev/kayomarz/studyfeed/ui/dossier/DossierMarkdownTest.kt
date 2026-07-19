package dev.kayomarz.studyfeed.ui.dossier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dossier renderer's safety net (JVM). The dossier corpus adds `**bold**`, `## ` headings and
 * backtick citations — but it STILL contains the PATCH 04 hazards (multiplication `N * 10^9`, alleles
 * `DRB1*04:01`, thresholds `<`/`>`). These tests prove those are rendered LITERALLY and never mistaken
 * for emphasis — a corrupted lab value on a physician's revision page is the exact failure to prevent.
 */
class DossierMarkdownTest {

    private val gray = Color.Unspecified
    private fun bolded(s: androidx.compose.ui.text.AnnotatedString) =
        s.spanStyles.any { it.item.fontWeight == FontWeight.Bold }

    @Test fun double_asterisk_is_bold() {
        val a = DossierMarkdown.inline("**Rheumatoid arthritis is associated with HLA-DR4.**", gray)
        assertEquals("Rheumatoid arthritis is associated with HLA-DR4.", a.text) // markers stripped
        assertTrue("bold applied", bolded(a))
    }

    @Test fun single_asterisk_multiplication_stays_literal_and_unbolded() {
        val a = DossierMarkdown.inline("Platelets < 50 * 10^9/L in severe ITP", gray)
        assertEquals("Platelets < 50 * 10^9/L in severe ITP", a.text) // '*' '<' kept verbatim
        assertFalse("a lone multiplication asterisk is NEVER emphasis", bolded(a))
    }

    @Test fun hla_allele_single_stars_stay_literal() {
        val a = DossierMarkdown.inline("DRB1*04:01 and DRB1*04:04 drive the HLA-DR4 link", gray)
        assertEquals("DRB1*04:01 and DRB1*04:04 drive the HLA-DR4 link", a.text)
        assertFalse(bolded(a))
    }

    @Test fun greater_and_less_than_are_literal_thresholds() {
        val a = DossierMarkdown.inline("eGFR > 60 and urate < 0.36 mmol/L", gray)
        assertEquals("eGFR > 60 and urate < 0.36 mmol/L", a.text)
    }

    @Test fun backtick_citations_are_stripped_of_backticks() {
        val a = DossierMarkdown.inline("A fact `[Clinical Science p.128; +10]`", gray)
        assertEquals("A fact [Clinical Science p.128; +10]", a.text) // backticks gone, content kept
    }

    @Test fun bold_wrapping_a_multiplication_keeps_the_interior_literal() {
        val a = DossierMarkdown.inline("**platelets < 50 * 10^9/L is severe**", gray)
        assertEquals("platelets < 50 * 10^9/L is severe", a.text) // outer ** stripped, inner * < kept
        assertTrue("the whole statement is bold", bolded(a))
    }

    @Test fun unmatched_double_asterisk_stays_literal() {
        val a = DossierMarkdown.inline("citalopram ** dose note", gray)
        assertEquals("citalopram ** dose note", a.text)
        assertFalse(bolded(a))
    }

    @Test fun headings_bullets_and_italic_notes_parse() {
        assertEquals(
            "Clinical sciences",
            (DossierMarkdown.blocks("## Clinical sciences", gray).single() as DossierMarkdown.Block.Heading).text,
        )
        val bullet = DossierMarkdown.blocks("- **Fact.** `[cite]`", gray).single() as DossierMarkdown.Block.Bullet
        assertEquals("Fact. [cite]", bullet.text.text)
        assertTrue("the bulleted statement is bold", bolded(bullet.text))
        val note = DossierMarkdown.blocks("*333 facts across 13 subjects*", gray).single() as DossierMarkdown.Block.Note
        assertEquals("333 facts across 13 subjects", note.text.text)
    }

    @Test fun a_page_body_splits_into_heading_and_bullet_blocks() {
        val md = "## Clinical sciences\n- **A is B.** `[p.1]`\n- **C is D.** `[p.2]`"
        val blocks = DossierMarkdown.blocks(md, gray)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is DossierMarkdown.Block.Heading)
        assertTrue(blocks[1] is DossierMarkdown.Block.Bullet)
        assertTrue(blocks[2] is DossierMarkdown.Block.Bullet)
    }
}
