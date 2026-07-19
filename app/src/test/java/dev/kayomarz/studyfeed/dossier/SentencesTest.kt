package dev.kayomarz.studyfeed.dossier

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sentence splitter (2026-07-19) — the guard that lets a user star ONE sentence, not the whole block.
 * The notation cases are the point: it must NOT shatter lab values, alleles, doses or abbreviations (the
 * same hazard class the markdown renderers protect against). **Fixtures are SYNTHETIC** — the shapes are
 * real, the medical facts are invented, so no pack content lives in a tracked file.
 */
class SentencesTest {

    @Test fun splits_plain_prose_on_sentence_boundaries() {
        assertEquals(
            listOf("Condition A is common.", "It affects the small parts."),
            Sentences.split("Condition A is common. It affects the small parts."),
        )
    }

    @Test fun a_decimal_dose_is_never_split() {
        assertEquals(listOf("Take 1.5 mg daily."), Sentences.split("Take 1.5 mg daily."))
    }

    @Test fun abbreviations_do_not_end_a_sentence() {
        assertEquals(listOf("Use e.g. option two for a case."), Sentences.split("Use e.g. option two for a case."))
        assertEquals(listOf("Check A, B, etc. every week."), Sentences.split("Check A, B, etc. every week."))
    }

    @Test fun single_letter_initials_are_guarded() {
        assertEquals(listOf("Noted by A. Baker in 1990."), Sentences.split("Noted by A. Baker in 1990."))
    }

    @Test fun notation_has_no_boundary_so_stays_whole() {
        assertEquals(listOf("A count < 50 * 10^9/L is low."), Sentences.split("A count < 50 * 10^9/L is low."))
        assertEquals(listOf("Marker*04:01 confers risk."), Sentences.split("Marker*04:01 confers risk."))
    }

    @Test fun no_boundary_returns_the_whole_line() {
        assertEquals(listOf("Option one is first-line; monitor levels"),
            Sentences.split("Option one is first-line; monitor levels"))
    }

    @Test fun blank_is_empty() {
        assertEquals(emptyList<String>(), Sentences.split("   "))
    }

    @Test fun a_question_then_a_statement_splits() {
        assertEquals(listOf("Which option?", "Option two is preferred."),
            Sentences.split("Which option? Option two is preferred."))
    }
}
