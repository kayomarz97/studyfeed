package dev.kayomarz.studyfeed.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PackMarkdown] — the hand-written renderer that must NOT behave like a markdown library.
 *
 * The headline tests are [multiplication_asterisk_is_never_treated_as_italic] and
 * [greater_than_is_never_treated_as_a_blockquote]: they assert the exact corruption a markdown library
 * would cause DOES NOT happen. If either fails, someone has reintroduced markdown parsing and lab values
 * are being silently mangled — the clinical-safety reason this renderer is hand-written (PATCH 04).
 *
 * All inputs here are SYNTHETIC forms, never copied from the pack (copyright rule: docs/tests log
 * shapes, never book content). They reproduce the STRUCTURE that matters — a count with `* 10^9/L`, a
 * `> N:` threshold — without being a substring of any real item.
 */
class PackMarkdownTest {

    private fun oneParagraphText(md: String): String {
        val blocks = PackMarkdown.blocks(md)
        assertEquals("expected exactly one paragraph block for: $md", 1, blocks.size)
        return (blocks[0] as PackMarkdown.Block.Paragraph).text.text
    }

    // ---- THE clinical-safety guarantees ------------------------------------------------------------

    @Test
    fun multiplication_asterisk_is_never_treated_as_italic() {
        // A markdown renderer takes text between two * as italic AND EATS THE ASTERISKS, turning a
        // blood count into a wrong value. This asserts the asterisks SURVIVE, verbatim.
        val input = "Platelets 999 * 10^9/L, WBC 8 * 10^9/L"   // synthetic, not a pack string
        val rendered = oneParagraphText(input)
        assertEquals("the multiplication signs must survive exactly", input, rendered)
        assertTrue("both asterisks present", rendered.count { it == '*' } == 2)
    }

    @Test
    fun a_reference_range_with_two_asterisks_keeps_both() {
        // The dangerous shape: two asterisks on one line WOULD be a matched italic span to a renderer.
        val input = "Eosinophils 9 * 10^9/L (0 - 0.5 * 10^9/L)"  // synthetic
        assertEquals(input, oneParagraphText(input))
    }

    @Test
    fun greater_than_is_never_treated_as_a_blockquote() {
        // "> N:" is a threshold, not a quote. A renderer would indent it and drop the ">".
        val input = "> 42: some interpretation"                  // synthetic
        assertEquals("the '>' threshold must survive as literal text", input, oneParagraphText(input))
    }

    @Test
    fun less_than_reference_ranges_survive() {
        val input = "CRP <5, ESR <(age+10)/2"                    // synthetic
        assertEquals(input, oneParagraphText(input))
    }

    @Test
    fun angle_brackets_are_not_treated_as_html() {
        val input = "value <lower> to <upper> limit"             // synthetic
        assertEquals(input, oneParagraphText(input))
    }

    @Test
    fun backticks_and_underscores_are_literal() {
        val input = "the `x` gene and the a_b_c locus"           // synthetic
        assertEquals(input, oneParagraphText(input))
    }

    // ---- what it DOES render -----------------------------------------------------------------------

    @Test
    fun a_dash_bullet_at_line_start_becomes_a_list() {
        val blocks = PackMarkdown.blocks("Findings:\n- weakness\n- numbness")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is PackMarkdown.Block.Paragraph)
        val list = blocks[1] as PackMarkdown.Block.BulletList
        assertEquals(2, list.items.size)
        assertEquals("•  weakness", list.items[0].text)
        assertEquals("•  numbness", list.items[1].text)
    }

    @Test
    fun a_star_bullet_at_line_start_is_a_bullet_but_a_star_mid_line_is_multiplication() {
        // THE distinction a markdown library cannot make: position decides. Leading "* " = bullet;
        // "* " inside a line = multiplication.
        val blocks = PackMarkdown.blocks("* first point\nPlatelets 5 * 10^9/L")
        val list = blocks[0] as PackMarkdown.Block.BulletList
        assertEquals("•  first point", list.items[0].text)
        val para = blocks[1] as PackMarkdown.Block.Paragraph
        assertEquals("Platelets 5 * 10^9/L", para.text.text)  // asterisk kept
    }

    @Test
    fun numbered_lists_keep_their_ordinals() {
        val blocks = PackMarkdown.blocks("1. first\n2. second")
        val list = blocks[0] as PackMarkdown.Block.BulletList
        assertEquals("1.  first", list.items[0].text)
        assertEquals("2.  second", list.items[1].text)
    }

    @Test
    fun blank_lines_separate_paragraphs() {
        val blocks = PackMarkdown.blocks("first para\nstill first\n\nsecond para")
        assertEquals(2, blocks.size)
        assertEquals("first para\nstill first", (blocks[0] as PackMarkdown.Block.Paragraph).text.text)
        assertEquals("second para", (blocks[1] as PackMarkdown.Block.Paragraph).text.text)
    }

    @Test
    fun empty_and_blank_input_yields_no_blocks() {
        assertEquals(emptyList<PackMarkdown.Block>(), PackMarkdown.blocks(""))
        assertEquals(emptyList<PackMarkdown.Block>(), PackMarkdown.blocks("   \n  \n"))
    }

    @Test
    fun inline_flattens_without_dropping_the_multiplication() {
        // The compact path (MCQ options, hooks) must be just as safe as the block path.
        assertEquals("Na+ 140, K+ 4 * something", PackMarkdown.inline("Na+ 140, K+ 4 * something").text)
    }

    // ---- the one deliberate emphasis ---------------------------------------------------------------

    @Test
    fun triple_asterisk_emphasis_is_styled_but_lone_asterisks_are_not() {
        // The compiler's rare ***…*** emphasis IS styled; a lone/paired multiplication * is NOT.
        val styled = PackMarkdown.withTripleEmphasis("see ***this*** but 5 * 3 stays")
        assertEquals("the visible text is intact (asterisks removed only from the ***triple*** span)",
            "see this but 5 * 3 stays", styled.text)
        // The lone multiplication asterisk survived in the flattened text.
        assertTrue(styled.text.contains("5 * 3"))
    }
}
