package dev.kayomarz.studyfeed.ui.feed

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Renders the pack's `*_md` fields — deliberately NOT a markdown library (BUILD_PROMPT §8 A2 / PATCH 04).
 *
 * ## Why this is hand-written, and why it must stay that way
 *
 * This looks like a job for a markdown dependency. It is not — and "upgrading" it to one would be a
 * **clinical correctness regression**, not a tidy-up. The reason is the corpus, measured across 68,893
 * real `*_md` fields:
 *
 *  - The pack's actual markdown vocabulary is tiny: newlines (~23% of fields), `- ` bullets (~2%),
 *    `1. ` numbered lists (~0.04%). Headings, links, images, `code`, fences and strikethrough are
 *    **entirely absent** — a general renderer would carry a mountain of code for constructs that never
 *    occur.
 *  - Medical text is full of characters a markdown renderer would **seize and corrupt**. `*` is a
 *    multiplication sign in blood counts (form `<count> * 10^9/L`); `>` and `<` are thresholds and
 *    reference ranges. A renderer treats text between two `*` as italic **and eats the asterisks**, so a
 *    `<count> * 10^9/L` value renders as `<count> 10^9/L` — the multiplication silently vanishes from a
 *    lab value on a physician's revision card. Exposure is not marginal: `>` in ~9% of MCQ explanations,
 *    `<` in ~6%, `*` in ~3.6% of stems.
 *
 * So this renderer supports **exactly** what the corpus contains and treats `*  <  >  ~  ` `` as
 * ordinary characters. For this pack that is strictly *more* correct than any general markdown engine.
 *
 * ⚠ **Do not add bold/italic/`code` parsing "for completeness".** Completeness here means corrupting
 * lab values. The two `***…***` emphases and 5 table fields in the whole pack render as literal text —
 * 7 fields out of 68,893, the correct price for never mangling the other ~1,300.
 */
object PackMarkdown {

    /** One rendered block: either a run of text (possibly multi-line) or a list. */
    sealed interface Block {
        data class Paragraph(val text: AnnotatedString) : Block
        /** [items] are already ordinal-prefixed for numbered lists ("1.", "2.", …) or "•" for bullets. */
        data class BulletList(val items: List<AnnotatedString>) : Block
    }

    /**
     * Split a markdown field into renderable blocks.
     *
     * Blank lines separate paragraphs. Consecutive `- `/`* `/`+ ` or `1. ` lines fold into one list.
     * Everything else — including every `*`, `<`, `>` — is literal text.
     *
     * NOTE `* ` at line-start is a bullet marker (a real construct in this corpus), but a `*` mid-line
     * is multiplication and stays literal. The distinction is position, checked below, and it is the
     * whole reason a general renderer is wrong here: it cannot tell the two apart.
     */
    fun blocks(md: String): List<Block> {
        if (md.isBlank()) return emptyList()
        val out = mutableListOf<Block>()
        val paragraph = StringBuilder()
        val listItems = mutableListOf<AnnotatedString>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                out += Block.Paragraph(AnnotatedString(paragraph.toString().trimEnd('\n')))
                paragraph.setLength(0)
            }
        }
        fun flushList() {
            if (listItems.isNotEmpty()) { out += Block.BulletList(listItems.toList()); listItems.clear() }
        }

        for (raw in md.split('\n')) {
            val line = raw.trimEnd()
            val bullet = bulletContent(line)
            val numbered = numberedContent(line)
            when {
                bullet != null -> { flushParagraph(); listItems += AnnotatedString("•  $bullet") }
                numbered != null -> { flushParagraph(); listItems += AnnotatedString(numbered) }
                line.isEmpty() -> { flushList(); flushParagraph() }
                else -> { flushList(); paragraph.append(line).append('\n') }
            }
        }
        flushList(); flushParagraph()
        return out
    }

    /**
     * Flatten to a single AnnotatedString for compact contexts (an MCQ option, a one-line hook) where a
     * full block layout is overkill. Lists become "•  " lines; no styling is applied.
     */
    fun inline(md: String): AnnotatedString = buildAnnotatedString {
        blocks(md).forEachIndexed { i, b ->
            if (i > 0) append("\n")
            when (b) {
                is Block.Paragraph -> append(b.text)
                is Block.BulletList -> b.items.forEachIndexed { j, it ->
                    if (j > 0) append("\n"); append(it)
                }
            }
        }
    }

    // `- `, `* `, `+ ` at the START of a (trimmed) line is a bullet. A `*` anywhere else is literal
    // (multiplication). Requires the trailing space so "*emphasis" or "5*10" never counts as a bullet.
    private fun bulletContent(line: String): String? {
        val t = line.trimStart()
        for (marker in listOf("- ", "* ", "+ ")) {
            if (t.startsWith(marker)) return t.removePrefix(marker).trim()
        }
        return null
    }

    // `<n>. ` at line start → keep the "n." and the content. Anything else is literal.
    private val NUMBERED = Regex("""^\s*(\d+)\.\s+(.*)$""")
    private fun numberedContent(line: String): String? =
        NUMBERED.matchEntire(line)?.let { "${it.groupValues[1]}.  ${it.groupValues[2].trim()}" }

    /**
     * The ONE styling this renderer does: the deliberate `***…***` emphasis the compiler occasionally
     * emits (2 fields in the whole pack). Applied ONLY to a fully triple-asterisk-wrapped span, never to
     * lone or paired asterisks — those are multiplication and must never be touched. Callers that want
     * this pass a paragraph's text through here; most do not bother.
     */
    fun withTripleEmphasis(text: String): AnnotatedString = buildAnnotatedString {
        val rx = Regex("""\*\*\*(.+?)\*\*\*""")
        var last = 0
        for (m in rx.findAll(text)) {
            append(text.substring(last, m.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                append(m.groupValues[1])
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}
