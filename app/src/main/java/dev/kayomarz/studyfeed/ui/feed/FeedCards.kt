package dev.kayomarz.studyfeed.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kayomarz.studyfeed.feed.FeedCard

/**
 * The five card composables (BUILD_PROMPT §8 A2) — one per item type the pack actually has (PATCH 04).
 *
 * Each fills the pager page and scrolls internally if the content is tall (some MCQ stems + explanations
 * exceed a screen). The shared [CardHeader] rides every card. A figure, when attached, renders inline.
 *
 * **Ratings flow OUT via callbacks, not written here.** These composables are pure UI: they report what
 * the user did ([Rating]) and the feed layer writes FSRS + progress off the main thread (D5: a write must
 * never block the swipe). That keeps the cards testable and the DB work where it belongs.
 */

/** A user's self-assessment on a card. Maps to FSRS grades in the feed layer (A2 step 8). */
enum class Rating { AGAIN, HARD, GOOD, EASY }

@Composable
private fun CardScaffold(
    card: FeedCard,
    onImportanceClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        CardHeader(card, onImportanceClick)
        Spacer(Modifier.height(20.dp))
        content()
        Spacer(Modifier.height(40.dp)) // breathing room above the pager edge
    }
}

// ── MCQ ────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Best-of-five. Pick → the answer is **revealed inline** (§8 A2): the chosen option and the correct one
 * are marked, the explanation and per-distractor "why wrong" appear, and the rating buttons unlock.
 * A wrong pick is reported so the feed can write a `mistakes` row.
 */
@Composable
fun McqCard(
    card: FeedCard.Mcq,
    onImportanceClick: () -> Unit,
    onAnswered: (chosenIdx: Int, correct: Boolean) -> Unit,
    onRate: (Rating) -> Unit,
) {
    var chosen by remember(card.itemId) { mutableStateOf<Int?>(null) }
    val revealed = chosen != null

    CardScaffold(card, onImportanceClick) {
        if (card.keyConflict) {
            KeyDisputedBanner(card.keyConflictNote)
            Spacer(Modifier.height(12.dp))
        }
        MarkdownText(card.stemMd, style = MaterialTheme.typography.bodyLarge)
        card.figure?.let { Spacer(Modifier.height(16.dp)); FigureImage(it) }
        Spacer(Modifier.height(20.dp))

        card.options.forEachIndexed { idx, opt ->
            OptionRow(
                label = ('A' + idx).toString(),
                text = opt,
                state = optionState(idx, chosen, card.correctIdx, revealed),
                enabled = !revealed,
                onClick = {
                    if (chosen == null) { chosen = idx; onAnswered(idx, idx == card.correctIdx) }
                },
            )
            // Per-distractor "why wrong" appears under a WRONG option once revealed (§8 A2).
            if (revealed && idx != card.correctIdx) {
                card.perOptionWhyWrong.getOrNull(idx)?.takeIf { it.isNotBlank() }?.let { why ->
                    MarkdownText(
                        why,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 34.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        AnimatedVisibility(revealed) {
            Column {
                Spacer(Modifier.height(12.dp))
                Text("Explanation", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                MarkdownText(card.explanationMd, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(24.dp))
                RatingBar(onRate) // no negative marking (§8 A2): a wrong answer still gets rated for FSRS
            }
        }
    }
}

private enum class OptState { NEUTRAL, CHOSEN_WRONG, CORRECT }

private fun optionState(idx: Int, chosen: Int?, correctIdx: Int, revealed: Boolean): OptState = when {
    !revealed -> OptState.NEUTRAL
    idx == correctIdx -> OptState.CORRECT
    idx == chosen -> OptState.CHOSEN_WRONG
    else -> OptState.NEUTRAL
}

@Composable
private fun OptionRow(label: String, text: String, state: OptState, enabled: Boolean, onClick: () -> Unit) {
    val correct = Color(0xFF66BB6A)
    val wrong = MaterialTheme.colorScheme.error
    val border = when (state) {
        OptState.CORRECT -> correct
        OptState.CHOSEN_WRONG -> wrong
        OptState.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(12.dp))
            .background(
                when (state) {
                    OptState.CORRECT -> correct.copy(alpha = 0.10f)
                    OptState.CHOSEN_WRONG -> wrong.copy(alpha = 0.08f)
                    OptState.NEUTRAL -> Color.Transparent
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("$label ", fontWeight = FontWeight.Bold,
            color = when (state) {
                OptState.CORRECT -> correct
                OptState.CHOSEN_WRONG -> wrong
                OptState.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
            })
        MarkdownText(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun KeyDisputedBanner(note: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
            .padding(12.dp),
    ) {
        Text("⚠ Key disputed", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp)
        note?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            MarkdownText(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── Flashcard ──────────────────────────────────────────────────────────────────────────────────────

/** Tap to flip front→back; then a 4-button FSRS rating (§8 A2). */
@Composable
fun FlashcardCard(
    card: FeedCard.Flashcard,
    onImportanceClick: () -> Unit,
    onRate: (Rating) -> Unit,
) {
    var flipped by remember(card.itemId) { mutableStateOf(false) }
    CardScaffold(card, onImportanceClick) {
        Text(if (flipped) "ANSWER" else "TAP TO REVEAL", fontSize = 11.sp, letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable { flipped = !flipped }.padding(vertical = 8.dp),
        ) {
            MarkdownText(card.frontMd, style = MaterialTheme.typography.titleMedium)
            card.figure?.let { Spacer(Modifier.height(16.dp)); FigureImage(it) }
            AnimatedVisibility(flipped) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    MarkdownText(card.backMd, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        AnimatedVisibility(flipped) {
            Column { Spacer(Modifier.height(24.dp)); RatingBar(onRate) }
        }
    }
}

// ── Reel ───────────────────────────────────────────────────────────────────────────────────────────

/** Hook → tap to reveal the detail → got-it/shaky (§8 A2). Two-button rating: reels are lightweight. */
@Composable
fun ReelCard(
    card: FeedCard.Reel,
    onImportanceClick: () -> Unit,
    onRate: (Rating) -> Unit,
) {
    var revealed by remember(card.itemId) { mutableStateOf(false) }
    CardScaffold(card, onImportanceClick) {
        MarkdownText(card.hookMd, style = MaterialTheme.typography.headlineSmall)
        card.figure?.let { Spacer(Modifier.height(16.dp)); FigureImage(it) }
        if (!revealed) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { revealed = true }) { Text("Reveal") }
        }
        AnimatedVisibility(revealed) {
            Column {
                Spacer(Modifier.height(16.dp))
                MarkdownText(card.detailMd, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onRate(Rating.AGAIN) }) { Text("Shaky") }
                    Button(onClick = { onRate(Rating.GOOD) }) { Text("Got it") }
                }
            }
        }
    }
}

// ── Blob (passage) ───────────────────────────────────────────────────────────────────────────────────

/**
 * A passage to read. §8 A2: on scroll-past, the feed schedules its `blob_quiz` a few cards later and a
 * re-read next session — that scheduling is the feed layer's job (A2 step 9), driven by [onRead]. The
 * card just reports it was read.
 */
@Composable
fun BlobCard(
    card: FeedCard.Blob,
    onImportanceClick: () -> Unit,
    onRead: () -> Unit,
) {
    CardScaffold(card, onImportanceClick) {
        card.title?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
        }
        card.estReadSeconds?.let {
            Text("~${it}s read", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
        MarkdownText(card.bodyMd, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRead, colors = ButtonDefaults.buttonColors()) { Text("Mark read") }
    }
}

// ── Interlink ────────────────────────────────────────────────────────────────────────────────────────

/** A concept-link question — reveal the answer, then rate (§8 A2 / A5's "quiz me on these links"). */
@Composable
fun InterlinkCard(
    card: FeedCard.Interlink,
    onImportanceClick: () -> Unit,
    onRate: (Rating) -> Unit,
) {
    var revealed by remember(card.itemId) { mutableStateOf(false) }
    CardScaffold(card, onImportanceClick) {
        Text("CONNECTION", fontSize = 11.sp, letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        MarkdownText(card.questionMd, style = MaterialTheme.typography.titleMedium)
        if (!revealed) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { revealed = true }) { Text("Reveal") }
        }
        AnimatedVisibility(revealed) {
            Column {
                Spacer(Modifier.height(16.dp))
                MarkdownText(card.answerMd, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
                RatingBar(onRate)
            }
        }
    }
}

// ── shared rating bar ────────────────────────────────────────────────────────────────────────────────

/** The four FSRS grades. Colour follows the recall difficulty, not the theme accent. */
@Composable
private fun RatingBar(onRate: (Rating) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RateButton("Again", Color(0xFFE57373), Modifier.weight(1f)) { onRate(Rating.AGAIN) }
        RateButton("Hard", Color(0xFFFFB74D), Modifier.weight(1f)) { onRate(Rating.HARD) }
        RateButton("Good", Color(0xFF81C784), Modifier.weight(1f)) { onRate(Rating.GOOD) }
        RateButton("Easy", Color(0xFF64B5F6), Modifier.weight(1f)) { onRate(Rating.EASY) }
    }
}

@Composable
private fun RateButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        border = BorderStroke(1.dp, color.copy(alpha = 0.7f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) { Text(label, fontSize = 13.sp) }
}
