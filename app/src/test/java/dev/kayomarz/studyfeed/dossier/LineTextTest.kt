package dev.kayomarz.studyfeed.dossier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A7 cycle 3 (P3): the line-mark anchor. A stable hash is what keeps a star attached across a rebuild. */
class LineTextTest {

    @Test fun hash_ignores_case_and_whitespace_so_marks_survive_reflow() {
        assertEquals(LineText.hash("Methotrexate  is First-line"), LineText.hash("methotrexate is first-line"))
    }

    @Test fun distinct_lines_hash_differently() {
        assertNotEquals(LineText.hash("aspirin lowers risk"), LineText.hash("warfarin raises risk"))
    }

    @Test fun tokens_keep_salient_words_and_drop_stopwords_and_short_ones() {
        val t = LineText.tokens("The patient with rheumatoid arthritis")
        assertTrue("rheumatoid" in t)
        assertTrue("arthritis" in t)
        assertFalse("the" in t)        // too short
        assertFalse("with" in t)       // stopword
        assertFalse("patient" in t)    // stopword
    }
}
