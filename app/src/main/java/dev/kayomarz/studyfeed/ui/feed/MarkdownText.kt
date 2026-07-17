package dev.kayomarz.studyfeed.ui.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Renders a `*_md` field via [PackMarkdown] — paragraphs and lists, NEVER markdown-parsed inline
 * styling (PATCH 04: `*`/`<`/`>` are medical notation, not emphasis; see PackMarkdown's KDoc).
 *
 * Deliberately thin: it lays out the blocks [PackMarkdown.blocks] produces. Where the compiler emitted
 * its rare `***…***` emphasis, [PackMarkdown.withTripleEmphasis] styles only that, leaving every lone
 * asterisk (multiplication) untouched.
 */
@Composable
fun MarkdownText(
    md: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val blocks = PackMarkdown.blocks(md)
    Column(modifier) {
        blocks.forEachIndexed { i, block ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            when (block) {
                is PackMarkdown.Block.Paragraph ->
                    Text(text = PackMarkdown.withTripleEmphasis(block.text.text), style = style)

                is PackMarkdown.Block.BulletList -> Column {
                    block.items.forEachIndexed { j, item ->
                        if (j > 0) Spacer(Modifier.height(4.dp))
                        // The marker ("•  " / "1.  ") is already in the item text from PackMarkdown; a
                        // Row would double-indent, so render the line as-is with a hanging feel via style.
                        Text(text = item, style = style)
                    }
                }
            }
        }
    }
}
