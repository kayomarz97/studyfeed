package dev.kayomarz.studyfeed.ui.dossier

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.kayomarz.studyfeed.dossier.Sentences
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

    /** Sentinel [DossierText] `sectionOrd` meaning "this text is not markable" (the reference viewer). */
    const val NO_MARKS = Int.MIN_VALUE

    /** The 5 user star/highlight colours (long-press a sentence → pick one). Kinds `c1..c5`. */
    val USER_MARK_COLORS: List<Pair<String, Color>> = listOf(
        "c1" to Color(0xFF8E7CF0), "c2" to Color(0xFFFFB74D), "c3" to Color(0xFF66BB6A),
        "c4" to Color(0xFFEC6A9C), "c5" to Color(0xFF4FC3F7),
    )

    /** A mark kind → its accent colour. `c1..c5` = the user's own; `pyq`/`5star` = the auto highlight. */
    fun colorForMark(kind: String): Color = when (kind) {
        "pyq" -> AppColor.PyqCoral
        "5star" -> AppColor.Review
        else -> USER_MARK_COLORS.firstOrNull { it.first == kind }?.second ?: AppColor.Mcq
    }
}

/**
 * Renders a dossier `*_md` field via [DossierMarkdown], **one markable SENTENCE per row** (2026-07-19).
 * When [sectionOrd] is set (not [DossierMarkdown.NO_MARKS]) each sentence is long-pressable → a 5-colour
 * picker (or Remove), and shows a coloured left stripe for its mark ([markKind]: `c1..c5` = your colours,
 * `pyq`/`5star` = the approximate auto-highlight). Splitting a block into sentences ([Sentences]) is what
 * lets you star ONE sentence instead of the whole paragraph.
 *
 * @param onSetMark `(sectionOrd, sentence, kind)` — a `c1..c5` sets that colour, `null` removes the mark.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DossierText(
    md: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    sectionOrd: Int = DossierMarkdown.NO_MARKS,
    markKind: (String) -> String? = { null },
    onSetMark: (Int, String, String?) -> Unit = { _, _, _ -> },
) {
    val citation = MaterialTheme.colorScheme.onSurfaceVariant
    val marksOn = sectionOrd != DossierMarkdown.NO_MARKS
    Column(modifier) {
        DossierMarkdown.blocks(md, citation).forEachIndexed { i, b ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            when (b) {
                is DossierMarkdown.Block.Heading ->
                    Text(b.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp))
                is DossierMarkdown.Block.Bullet ->
                    SentenceRows(bulletContent(b.raw), true, sectionOrd, marksOn, citation, style, markKind, onSetMark)
                is DossierMarkdown.Block.Para ->
                    SentenceRows(b.raw, false, sectionOrd, marksOn, citation, style, markKind, onSetMark)
                // A whole-line italic subtitle stays one muted unit — not sentence-split, not markable.
                is DossierMarkdown.Block.Note ->
                    Text(b.text, style = MaterialTheme.typography.bodySmall, color = citation)
            }
        }
    }
}

private fun bulletContent(raw: String): String = when {
    raw.startsWith("- ") -> raw.removePrefix("- ")
    raw.startsWith("* ") -> raw.removePrefix("* ")
    else -> raw
}

/** One row per sentence in a block — each independently markable (the point: star a sentence, not a block). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SentenceRows(
    content: String, bullet: Boolean, sectionOrd: Int, marksOn: Boolean, citation: Color, style: TextStyle,
    markKind: (String) -> String?, onSetMark: (Int, String, String?) -> Unit,
) {
    val sentences = Sentences.split(content).ifEmpty { listOf(content.trim()) }
    Column(Modifier.fillMaxWidth()) {
        sentences.forEachIndexed { i, sentence ->
            if (!marksOn) {
                Row {
                    if (bullet && i == 0) Text("•  ", style = style)
                    Text(DossierMarkdown.inline(sentence, citation), style = style)
                }
            } else {
                MarkableLine(sentence, sectionOrd, bullet && i == 0, citation, style, markKind, onSetMark)
            }
        }
    }
}

/** A single sentence: coloured stripe for its mark, long-press → the 5-colour picker (or Remove). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkableLine(
    sentence: String, sectionOrd: Int, bullet: Boolean, citation: Color, style: TextStyle,
    markKind: (String) -> String?, onSetMark: (Int, String, String?) -> Unit,
) {
    val kind = markKind(LineText.hash(sentence))
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .let { m -> kind?.let { m.leftStripe(DossierMarkdown.colorForMark(it)) } ?: m }
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(start = if (kind != null) 10.dp else 2.dp, top = 1.dp, bottom = 1.dp),
        ) {
            if (bullet) Text("•  ", style = style)
            Text(DossierMarkdown.inline(sentence, citation), style = style)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DossierMarkdown.USER_MARK_COLORS.forEach { (k, c) ->
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(c)
                            .border(BorderStroke(if (kind == k) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface), CircleShape)
                            .clickable { onSetMark(sectionOrd, sentence, k); menu = false },
                    )
                }
            }
            DropdownMenuItem(text = { Text("Remove mark") },
                onClick = { onSetMark(sectionOrd, sentence, null); menu = false })
        }
    }
}

/** A subtle left accent stripe + faint tint — the "this line matters" affordance. */
private fun Modifier.leftStripe(color: Color): Modifier = this
    .background(color.copy(alpha = 0.08f))
    .drawBehind { drawRect(color, size = Size(3.dp.toPx(), size.height)) }
