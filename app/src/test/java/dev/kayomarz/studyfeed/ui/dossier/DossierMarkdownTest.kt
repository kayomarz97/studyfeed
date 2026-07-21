package dev.kayomarz.studyfeed.ui.dossier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dossier renderer's safety net (JVM). The dossier corpus adds `**bold**`, `## ` headings and
 * backtick citations — but it STILL contains the PATCH 04 hazards: multiplication (`N * 10^9`), allele-
 * style stars (`X*04:01`), and `<`/`>` thresholds. These tests prove those are rendered LITERALLY and
 * never mistaken for emphasis — a corrupted lab value on a physician's revision page is the exact failure
 * to prevent. **Fixtures are SYNTHETIC** (the notation is the point, not any real medical fact) — no pack
 * content in a tracked file.
 */
class DossierMarkdownTest {

    private val gray = Color.Unspecified
    private fun bolded(s: androidx.compose.ui.text.AnnotatedString) =
        s.spanStyles.any { it.item.fontWeight == FontWeight.Bold }

    @Test fun double_asterisk_is_bold() {
        val a = DossierMarkdown.inline("**Condition A links to Marker-X.**", gray)
        assertEquals("Condition A links to Marker-X.", a.text) // markers stripped
        assertTrue("bold applied", bolded(a))
    }

    @Test fun single_asterisk_multiplication_stays_literal_and_unbolded() {
        val a = DossierMarkdown.inline("Count < 50 * 10^9/L in condition Q", gray)
        assertEquals("Count < 50 * 10^9/L in condition Q", a.text) // '*' '<' kept verbatim
        assertFalse("a lone multiplication asterisk is NEVER emphasis", bolded(a))
    }

    @Test fun allele_style_single_stars_stay_literal() {
        val a = DossierMarkdown.inline("Marker*04:01 and Marker*04:04 mark the group-X link", gray)
        assertEquals("Marker*04:01 and Marker*04:04 mark the group-X link", a.text)
        assertFalse(bolded(a))
    }

    @Test fun greater_and_less_than_are_literal_thresholds() {
        val a = DossierMarkdown.inline("Test > 60 and level < 0.36 unit/L", gray)
        assertEquals("Test > 60 and level < 0.36 unit/L", a.text)
    }

    @Test fun backtick_citations_are_stripped_of_backticks() {
        val a = DossierMarkdown.inline("A fact `[Reference p.128; +10]`", gray)
        assertEquals("A fact [Reference p.128; +10]", a.text) // backticks gone, content kept
    }

    @Test fun bold_wrapping_a_multiplication_keeps_the_interior_literal() {
        val a = DossierMarkdown.inline("**value < 50 * 10^9/L is high**", gray)
        assertEquals("value < 50 * 10^9/L is high", a.text) // outer ** stripped, inner * < kept
        assertTrue("the whole statement is bold", bolded(a))
    }

    @Test fun unmatched_double_asterisk_stays_literal() {
        val a = DossierMarkdown.inline("example ** note", gray)
        assertEquals("example ** note", a.text)
        assertFalse(bolded(a))
    }

    @Test fun headings_bullets_and_italic_notes_parse() {
        assertEquals(
            "Section one",
            (DossierMarkdown.blocks("## Section one", gray).single() as DossierMarkdown.Block.Heading).text,
        )
        val bullet = DossierMarkdown.blocks("- **Fact.** `[cite]`", gray).single() as DossierMarkdown.Block.Bullet
        assertEquals("Fact. [cite]", bullet.text.text)
        assertTrue("the bulleted statement is bold", bolded(bullet.text))
        val note = DossierMarkdown.blocks("*12 facts across 3 subjects*", gray).single() as DossierMarkdown.Block.Note
        assertEquals("12 facts across 3 subjects", note.text.text)
    }

    @Test fun a_page_body_splits_into_heading_and_bullet_blocks() {
        val md = "## Section one\n- **A is B.** `[p.1]`\n- **C is D.** `[p.2]`"
        val blocks = DossierMarkdown.blocks(md, gray)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is DossierMarkdown.Block.Heading)
        assertTrue(blocks[1] is DossierMarkdown.Block.Bullet)
        assertTrue(blocks[2] is DossierMarkdown.Block.Bullet)
    }
}
