package dev.kayomarz.studyfeed.ui.feed

/**
 * Anki-style **cloze deletion** for flashcards (A7 fix, 2026-07-18).
 *
 * ~54% of the pack's flashcards are cloze (`flashcards.is_cloze = 1`): their `front_md` embeds the answer
 * inside `{{c1::answer}}` (optionally `{{c1::answer::hint}}`) markers — e.g.
 * `"Rheumatoid arthritis is most strongly associated with HLA-{{c1::DR4}}."`. The card renderer treats
 * `{` `}` as literal (PATCH 04), so before this fix it printed the **answer and the raw markers** on the
 * front — the "the answer is coming in the flashcard itself" bug.
 *
 * So for a cloze card we transform the front twice:
 * - [mask] — replace each cloze span with a blank, shown BEFORE reveal (the question the user answers).
 * - [reveal] — fill the answer back in (keeping the sentence context), shown AFTER reveal.
 *
 * Pure string work, no Android — JVM-testable. Non-cloze fronts never touch this.
 */
object Cloze {
    // {{cN::answer}} or {{cN::answer::hint}} — non-greedy so multiple clozes on one line each match.
    private val RE = Regex("""\{\{c\d+::(.*?)\}\}""")

    /** The blank shown in place of each cloze answer on the un-flipped (question) side. */
    private const val BLANK = "[ …… ]"

    /** Front with every cloze answer hidden — the question side. */
    fun mask(text: String): String = RE.replace(text) { BLANK }

    /** Front with every cloze answer filled back in (drops any `::hint`) — the answer side. */
    fun reveal(text: String): String = RE.replace(text) { m -> m.groupValues[1].substringBefore("::") }

    /** True if the text actually contains a cloze marker (belt-and-braces alongside `is_cloze`). */
    fun hasCloze(text: String): Boolean = RE.containsMatchIn(text)
}
