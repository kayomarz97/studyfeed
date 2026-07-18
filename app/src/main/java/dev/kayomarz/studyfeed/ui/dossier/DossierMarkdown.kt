package dev.kayomarz.studyfeed.ui.dossier

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kayomarz.studyfeed.dossier.LineText
import dev.kayomarz.studyfeed.ui.theme.AppColor

/**
 * The **dossier** renderer (BUILD_PROMPT §8 A4; A7 cycle 3 line marks). A SEPARATE renderer from
 * [dev.kayomarz.studyfeed.ui.feed.PackMarkdown] because the two corpora have **different, measured**
 * vocabularies:
 *
 * - **Card `*_md` fields** (MCQ/atom, PackMarkdown): only newlines + `- ` bullets. `*`/`<`/`>`/backtick
 *   are LITERAL — every `*` is multiplication, every `>` a threshold (PATCH 04, 68,893 fields).
 * - **Dossier `topic_pages`/`topic_sections` body_md** (this): the compiler ASSEMBLED these, so they add
 *   `#`/`##` headings, `**bold**` (163k+ pairs), and backtick-wrapped `` `citations` ``.
 *
 * **The PATCH 04 hazard still holds here** (13 `N * 10^9` + 20 `DRB1*04:01`), so: `**bold**` matches
 * DOUBLE asterisks only; every lone `*`/`<`/`>` stays literal; backtick → a muted citation span.
 * NO markdown library.
 *
 * **A7 cycle 3:** each block keeps its trimmed [Block.raw] line so a line can be hashed
 * ([LineText.hash]) for the user's stars + the approximate PYQ/5★ highlight (a coloured left stripe).
 */
object DossierMarkdown {

    sealed interface Block {
        /** The trimmed raw source line — the hash anchor for line marks (P3). */
        val raw: String
        data class Heading(override val raw: String, val text: String) : Block
        data class Bullet(override val raw: String, val text: AnnotatedString) : Block
        data class Para(override val raw: String, val text: AnnotatedString) : Block
        /** A whole-line `*…*` italic (e.g. the page subtitle) — rendered muted. */
        data class Note(override val raw: String, val text: AnnotatedString) : Block
    }

    fun blocks(md: String, citationColor: Color): List<Block> {
        val out = ArrayList<Block>()
        for (raw in md.split("\n")) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            when {
                t.startsWith("## ") -> out += Block.Heading(t, t.removePrefix("## ").trim())
                t.startsWith("# ") -> out += Block.Heading(t, t.removePrefix("# ").trim())
                t.startsWith("- ") -> out += Block.Bullet(t, inline(t.removePrefix("- "), citationColor))
                t.startsWith("* ") && !t.startsWith("**") -> out += Block.Bullet(t, inline(t.removePrefix("* "), citationColor))
                isWholeLineItalic(t) -> out += Block.Note(t, inline(t.substring(1, t.length - 1), citationColor))
                else -> out += Block.Para(t, inline(t, citationColor))
            }
        }
        return out
    }

    private fun isWholeLineItalic(t: String): Boolean =
        t.length > 2 && t.first() == '*' && t.last() == '*' &&
            !t.startsWith("**") && !t.endsWith("**") && t.count { it == '*' } == 2

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

    /** A line mark kind → its accent colour. `star`/`highlight` = the user's; `pyq`/`5star` = auto. */
    const val NO_MARKS = Int.MIN_VALUE
}

/**
 * Renders a dossier `*_md` field via [DossierMarkdown]. When [sectionOrd] is set (not [DossierMarkdown.
 * NO_MARKS]), each body line becomes long-pressable (→ [onLongPressLine] to star it) and shows a coloured
 * left stripe when [markKind] returns a kind for its hash (`star`/`highlight` = yours, `pyq`/`5star` = auto).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DossierText(
    md: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    sectionOrd: Int = DossierMarkdown.NO_MARKS,
    markKind: (String) -> String? = { null },
    onLongPressLine: (Int, String) -> Unit = { _, _ -> },
) {
    val citation = MaterialTheme.colorScheme.onSurfaceVariant
    val marksOn = sectionOrd != DossierMarkdown.NO_MARKS
    Column(modifier) {
        DossierMarkdown.blocks(md, citation).forEachIndexed { i, b ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            val kind = if (!marksOn || b is DossierMarkdown.Block.Heading) null else markKind(LineText.hash(b.raw))
            val wrap: Modifier = if (!marksOn || b is DossierMarkdown.Block.Heading) Modifier
            else Modifier.fillMaxWidth()
                .let { m -> kind?.let { m.leftStripe(colorForMark(it)) } ?: m }
                .combinedClickable(onClick = {}, onLongClick = { onLongPressLine(sectionOrd, b.raw) })
                .padding(start = if (kind != null) 10.dp else 0.dp, top = 1.dp, bottom = 1.dp)
            when (b) {
                is DossierMarkdown.Block.Heading ->
                    Text(b.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp))
                is DossierMarkdown.Block.Bullet -> Row(wrap.padding(start = 2.dp)) {
                    Text("•  ", style = style)
                    Text(b.text, style = style)
                }
                is DossierMarkdown.Block.Para -> Box(wrap) { Text(b.text, style = style) }
                is DossierMarkdown.Block.Note -> Box(wrap) {
                    Text(b.text, style = MaterialTheme.typography.bodySmall, color = citation)
                }
            }
        }
    }
}

private fun colorForMark(kind: String): Color = when (kind) {
    "pyq" -> AppColor.PyqCoral
    "5star" -> AppColor.Review
    else -> AppColor.Mcq   // the user's own star/highlight (iris)
}

/** A subtle left accent stripe + faint tint — the "this line matters" affordance. */
private fun Modifier.leftStripe(color: Color): Modifier = this
    .background(color.copy(alpha = 0.08f))
    .drawBehind { drawRect(color, size = Size(3.dp.toPx(), size.height)) }
