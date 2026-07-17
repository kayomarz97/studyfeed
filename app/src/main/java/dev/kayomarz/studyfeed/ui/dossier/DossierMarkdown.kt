package dev.kayomarz.studyfeed.ui.dossier

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The **dossier** renderer (BUILD_PROMPT §8 A4). A SEPARATE renderer from [dev.kayomarz.studyfeed.ui.
 * feed.PackMarkdown] because the two corpora have **different, measured** vocabularies:
 *
 * - **Card `*_md` fields** (MCQ/atom, PackMarkdown): only newlines + `- ` bullets. `*`/`<`/`>`/backtick
 *   are LITERAL — every `*` is multiplication, every `>` a threshold (PATCH 04, 68,893 fields).
 * - **Dossier `topic_pages`/`topic_sections` body_md** (this): the compiler ASSEMBLED these, so they add
 *   `#`/`##` headings, `**bold**` (163k+ pairs), and backtick-wrapped `` `citations` ``.
 *
 * **The PATCH 04 hazard still holds here** (measured: 13 `N * 10^9` multiplications + 20 `DRB1*04:01`
 * alleles across the dossier corpus), so this renderer is deliberately safe:
 *  - **`**bold**` matches DOUBLE asterisks only** — a lone `*` (multiplication, allele, `*italic*` line
 *    wrapper) is NEVER treated as emphasis; it stays literal. `DRB1*04:01` and `< 50 * 10^9/L` survive.
 *  - **`<` and `>` are LITERAL** (thresholds), never parsed as HTML/blockquote.
 *  - backtick `` `…` `` → a muted citation span (the backticks stripped; not a code fence — there are
 *    zero fences in the corpus).
 *
 * NO markdown library — same standing rule as PackMarkdown. This is an in-house, corpus-specific parser.
 */
object DossierMarkdown {

    sealed interface Block {
        /** A `## ` (or `# `) sub-heading line. */
        data class Heading(val text: String) : Block
        data class Bullet(val text: AnnotatedString) : Block
        data class Para(val text: AnnotatedString) : Block
        /** A whole-line `*…*` italic (e.g. the page subtitle) — rendered muted. */
        data class Note(val text: AnnotatedString) : Block
    }

    fun blocks(md: String, citationColor: Color): List<Block> {
        val out = ArrayList<Block>()
        for (raw in md.split("\n")) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            when {
                t.startsWith("## ") -> out += Block.Heading(t.removePrefix("## ").trim())
                t.startsWith("# ") -> out += Block.Heading(t.removePrefix("# ").trim())
                t.startsWith("- ") -> out += Block.Bullet(inline(t.removePrefix("- "), citationColor))
                t.startsWith("* ") && !t.startsWith("**") -> out += Block.Bullet(inline(t.removePrefix("* "), citationColor))
                isWholeLineItalic(t) -> out += Block.Note(inline(t.substring(1, t.length - 1), citationColor))
                else -> out += Block.Para(inline(t, citationColor))
            }
        }
        return out
    }

    /** A line wholly wrapped in single `*…*` (exactly two stars, not `**`) — safe to treat as italic. */
    private fun isWholeLineItalic(t: String): Boolean =
        t.length > 2 && t.first() == '*' && t.last() == '*' &&
            !t.startsWith("**") && !t.endsWith("**") && t.count { it == '*' } == 2

    /**
     * Inline styling: `**bold**` (double-star pairs only) and `` `citation` `` (backtick). A single `*`,
     * and any `<`/`>`, are appended LITERALLY — the multiplication/allele/threshold safety invariant.
     */
    fun inline(text: String, citationColor: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '*' && i + 1 < text.length && text[i + 1] == '*') {
                val close = text.indexOf("**", i + 2)
                if (close >= 0) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, close)); pop()
                    i = close + 2; continue
                }
            } else if (ch == '`') {
                val close = text.indexOf('`', i + 1)
                if (close >= 0) {
                    pushStyle(SpanStyle(color = citationColor, fontSize = 12.sp))
                    append(text.substring(i + 1, close)); pop()
                    i = close + 1; continue
                }
            }
            append(ch); i++   // literal — includes every lone '*', '<', '>'
        }
    }
}

/** Renders a dossier `*_md` field via [DossierMarkdown]. Thin, like the feed's MarkdownText. */
@Composable
fun DossierText(md: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val citation = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        DossierMarkdown.blocks(md, citation).forEachIndexed { i, b ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            when (b) {
                is DossierMarkdown.Block.Heading ->
                    Text(b.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp))
                is DossierMarkdown.Block.Bullet -> Row(Modifier.padding(start = 2.dp)) {
                    Text("•  ", style = style)
                    Text(b.text, style = style, modifier = Modifier)
                }
                is DossierMarkdown.Block.Para -> Text(b.text, style = style)
                is DossierMarkdown.Block.Note ->
                    Text(b.text, style = MaterialTheme.typography.bodySmall, color = citation)
            }
        }
    }
}
