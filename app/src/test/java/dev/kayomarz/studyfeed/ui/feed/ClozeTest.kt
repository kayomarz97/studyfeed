package dev.kayomarz.studyfeed.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A7: cloze deletion never leaks the answer on the masked (question) side, and fills it in on reveal.
 *
 * All fixtures are **synthetic** (invented tokens, never real pack content) — the tests exercise the cloze
 * SYNTAX, not any medical fact, so made-up strings prove exactly the same behaviour.
 */
class ClozeTest {

    @Test fun mask_hides_the_answer_and_reveal_fills_it_in() {
        val front = "The widget is bonded to the FOO-{{c1::BARBAZ}} rail."
        val masked = Cloze.mask(front)
        assertFalse("the answer must not appear on the question side", masked.contains("BARBAZ"))
        assertFalse("no raw cloze markers on screen", masked.contains("{{"))
        assertTrue(masked.contains("FOO-"))
        assertEquals("The widget is bonded to the FOO-BARBAZ rail.", Cloze.reveal(front))
    }

    @Test fun multiple_clozes_on_one_line_all_masked() {
        val front = "{{c1::Alpha}} connects to {{c2::Omega node}}."
        val masked = Cloze.mask(front)
        assertFalse(masked.contains("Alpha"))
        assertFalse(masked.contains("Omega node"))
        assertEquals("Alpha connects to Omega node.", Cloze.reveal(front))
    }

    @Test fun hint_syntax_is_dropped_on_reveal() {
        val front = "The token is {{c1::ZETA::a greek hint}}."
        assertEquals("The token is ZETA.", Cloze.reveal(front))
        assertFalse(Cloze.mask(front).contains("ZETA"))
    }

    @Test fun plain_text_is_untouched_and_flagged_correctly() {
        val plain = "What is the value of the placeholder gadget?"
        assertEquals(plain, Cloze.mask(plain))
        assertEquals(plain, Cloze.reveal(plain))
        assertFalse(Cloze.hasCloze(plain))
        assertTrue(Cloze.hasCloze("A {{c1::x}} b"))
    }

    @Test fun multiplication_asterisks_survive_masking() {
        // The PATCH 04 hazard: a lone '*' is multiplication, not markdown — cloze must not touch it.
        val front = "The reading climbs to {{c1::99}} * 10^9/L in the sample."
        val masked = Cloze.mask(front)
        assertTrue("multiplication asterisk preserved", masked.contains("* 10^9/L"))
        assertFalse(masked.contains("99 * 10^9"))   // the value 99 is hidden, the * stays
        assertEquals("The reading climbs to 99 * 10^9/L in the sample.", Cloze.reveal(front))
    }
}
