package dev.kayomarz.studyfeed.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.HorizontalDivider
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
 * The five card composables (BUILD_PROMPT §8 A2; **A7 redesign 2026-07-18**) — one per item type the pack
 * actually has (PATCH 04).
 *
 * Each fills the pager page and scrolls internally if the content is tall (some MCQ stems + explanations
 * exceed a screen). The shared [CardScaffold] rides every card. A figure, when attached, renders inline.
 *
 * **A7 layout (user choices):**
 * - **Loud PYQ badge** + importance stars at the TOP; the **subject** sits quietly at the BOTTOM.
 * - **Tags moved to the BOTTOM and only shown AFTER the answer is revealed** (thumb-reach, user 2.4).
 * - **Tap ANYWHERE to reveal** (reel/interlink/flashcard/blob) — MCQ keeps option-tap (user 2.6).
 * - **Flashcard vs reel are styled distinctly** — reel = "Did you know?" skim, flashcard = "Recall this".
 *
 * **Ratings flow OUT via callbacks, not written here.** These composables are pure UI: they report what
 * the user did ([Rating]) and the feed layer writes FSRS + progress off the main thread (D5: a write must
 * never block the swipe). That keeps the cards testable and the DB work where it belongs.
 */

/** A user's self-assessment on a card. Maps to FSRS grades in the feed layer (A2 step 8). */
enum class Rating { AGAIN, HARD, GOOD, EASY }

/**
 * The frame around every card.
 *
 * @param revealed whether the card has passed its reveal/answer stage — gates the bottom tag row and
 *   turns off tap-anywhere so post-reveal controls (options, rating) stay interactive.
 * @param onReveal if non-null, tapping ANYWHERE on the card while `!revealed` calls this (A7 tap-anywhere).
 *   MCQ passes null (you tap an option, not "reveal").
 */
@Composable
private fun CardScaffold(
    card: FeedCard,
    actions: CardActionsState,
    revealed: Boolean,
    onImportanceClick: () -> Unit,
    onReveal: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Tap-anywhere-to-reveal: a no-indication, full-card click, active ONLY before reveal. Child
    // clickables (importance pill, star, the explicit Reveal/Mark-read button, MCQ options) hit first,
    // so this only fires on the "empty" areas — exactly the affordance the user asked for (2.6).
    val tapToReveal = if (!revealed && onReveal != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onReveal,
        )
    } else Modifier

    Column(
        Modifier
            .fillMaxSize()
            // NOTE: no statusBarsPadding here — the controls bar above the pager owns the top inset
            // (targetSdk 36 edge-to-edge). The true-black background still bleeds full behind the bar.
            .then(tapToReveal)
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CardTopRow(card, onImportanceClick, Modifier.weight(1f))
            StarButton(actions.starred, actions.onToggleStar)
        }
        Spacer(Modifier.height(16.dp))
        content()

        // ── bottom band: quiet subject, then the tag row (only after the answer is revealed) ──────────
        Spacer(Modifier.height(24.dp))
        SubjectFooter(card)
        if (revealed && actions.tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            TagRow(actions)
        }
        Spacer(Modifier.height(40.dp)) // breathing room above the pager edge
    }
}

/** A small uppercase eyebrow label — the type/mode cue at the top of a card body. */
@Composable
private fun Eyebrow(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, fontSize = 11.sp, letterSpacing = 1.sp, color = color, fontWeight = FontWeight.SemiBold)
}

/** Star + tag state and callbacks passed down to a card. Progress-side, never pack content. */
data class CardActionsState(
    val starred: Boolean,
    val appliedTagKeys: Set<String>,
    val tags: List<dev.kayomarz.studyfeed.pack.PackDatabase.TagDef>,
    val onToggleStar: () -> Unit,
    val onToggleTag: (String) -> Unit,
)

@Composable
private fun StarButton(starred: Boolean, onClick: () -> Unit) {
    val c = if (starred) Color(0xFFFFD54F) else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = if (starred) "★" else "☆",
        color = c,
        fontSize = 22.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun TagRow(actions: CardActionsState) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        actions.tags.forEach { tag ->
            val on = tag.key in actions.appliedTagKeys
            val border = if (on) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
            Text(
                text = tag.label,
                fontSize = 12.sp,
                color = if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(BorderStroke(1.dp, border), RoundedCornerShape(50))
                    .background(
                        if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
                    )
                    .clickable { actions.onToggleTag(tag.key) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

// ── MCQ ────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Best-of-five. Pick → the answer is **revealed inline** (§8 A2): the chosen option and the correct one
 * are marked, the explanation and per-distractor "why wrong" appear, and the rating buttons unlock.
 * A wrong pick is reported so the feed can write a `mistakes` row. **No tap-anywhere** — you tap an option.
 */
@Composable
fun McqCard(
    card: FeedCard.Mcq,
    onImportanceClick: () -> Unit,
    actions: CardActionsState,
    onAnswered: (chosenIdx: Int, correct: Boolean) -> Unit,
    onRate: (Rating) -> Unit,
) {
    var chosen by remember(card.itemId) { mutableStateOf<Int?>(null) }
    val revealed = chosen != null

    CardScaffold(card, actions, revealed, onImportanceClick, onReveal = null) {
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

/**
 * **Active recall** (A7 distinct styling): a "RECALL THIS" prompt you try to answer in your head, then
 * tap anywhere to reveal the back and rate with the 4-button FSRS scale. The eyebrow + rating scale mark
 * it as *recall*, distinct from a reel's passive skim.
 */
@Composable
fun FlashcardCard(
    card: FeedCard.Flashcard,
    onImportanceClick: () -> Unit,
    actions: CardActionsState,
    onRate: (Rating) -> Unit,
) {
    var flipped by remember(card.itemId) { mutableStateOf(false) }
    val cloze = card.isCloze || Cloze.hasCloze(card.frontMd)
    // Cloze cards blank the answer on the front and fill it back in on reveal; plain cards keep the
    // front as-is and show the back on reveal.
    val front = when {
        cloze && !flipped -> Cloze.mask(card.frontMd)
        cloze -> Cloze.reveal(card.frontMd)
        else -> card.frontMd
    }
    CardScaffold(card, actions, revealed = flipped, onImportanceClick, onReveal = { flipped = true }) {
        Eyebrow("RECALL THIS", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        MarkdownText(front, style = MaterialTheme.typography.titleMedium)
        card.figure?.let { Spacer(Modifier.height(16.dp)); FigureImage(it) }
        if (!flipped) {
            Spacer(Modifier.height(16.dp))
            Text("Tap anywhere to reveal the answer", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(flipped) {
            Column {
                // A cloze card's answer is already filled into the front above — showing back_md too
                // would just repeat it, so only plain cards render the separate back.
                if (!cloze) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    MarkdownText(card.backMd, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(24.dp))
                RatingBar(onRate)
            }
        }
    }
}

// ── Reel ───────────────────────────────────────────────────────────────────────────────────────────

/**
 * **Passive skim** (A7 distinct styling): a "DID YOU KNOW?" one-hook fact you tap anywhere to expand,
 * then a light got-it/shaky. The eyebrow + two-button rating mark it as *exposure*, not active recall —
 * the difference the user asked us to make obvious (backlog 2.5).
 */
@Composable
fun ReelCard(
    card: FeedCard.Reel,
    onImportanceClick: () -> Unit,
    actions: CardActionsState,
    onRate: (Rating) -> Unit,
) {
    var revealed by remember(card.itemId) { mutableStateOf(false) }
    CardScaffold(card, actions, revealed, onImportanceClick, onReveal = { revealed = true }) {
        Eyebrow("DID YOU KNOW?", color = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.height(12.dp))
        MarkdownText(card.hookMd, style = MaterialTheme.typography.headlineSmall)
        card.figure?.let { Spacer(Modifier.height(16.dp)); FigureImage(it) }
        if (!revealed) {
            Spacer(Modifier.height(16.dp))
            Text("Tap anywhere to reveal", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
 * A passage to read, then its quiz-back (§8 A2 / Acceptance 7).
 *
 * **Scope boundary:** [onRead] records the read + schedules the re-read (blob_reads, done in the feed
 * layer). A3's ordering engine consumes `reread_due` to place the passage back in the feed. Tap anywhere
 * (A7) marks it read.
 */
@Composable
fun BlobCard(
    card: FeedCard.Blob,
    onImportanceClick: () -> Unit,
    actions: CardActionsState,
    onRead: () -> Unit,
) {
    var read by remember(card.itemId) { mutableStateOf(false) }
    CardScaffold(card, actions, revealed = read, onImportanceClick,
        onReveal = { read = true; onRead() }) {
        Eyebrow("PASSAGE")
        Spacer(Modifier.height(8.dp))
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

        if (!read) {
            Button(onClick = { read = true; onRead() }) { Text("Mark read") }
        } else if (card.quiz != null) {
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Eyebrow("QUICK CHECK")
            Spacer(Modifier.height(8.dp))
            BlobQuizBack(card.quiz!!)
        } else {
            Text("Read ✓ — you'll see this again next session.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The passage quiz-back: pick an option → correct/wrong marked inline + the answer note. */
@Composable
private fun BlobQuizBack(quiz: FeedCard.BlobQuiz) {
    var chosen by remember { mutableStateOf<Int?>(null) }
    val revealed = chosen != null
    MarkdownText(quiz.questionMd, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(12.dp))
    quiz.options.forEachIndexed { idx, opt ->
        OptionRow(
            label = ('A' + idx).toString(),
            text = opt,
            state = optionState(idx, chosen, quiz.correctIdx, revealed),
            enabled = !revealed,
            onClick = { if (chosen == null) chosen = idx },
        )
        Spacer(Modifier.height(8.dp))
    }
    AnimatedVisibility(revealed) {
        Column {
            Spacer(Modifier.height(8.dp))
            MarkdownText(quiz.answerMd, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ── Interlink ────────────────────────────────────────────────────────────────────────────────────────

/** A concept-link question — tap anywhere to reveal the answer, then rate (§8 A2 / A5's "quiz me"). */
@Composable
fun InterlinkCard(
    card: FeedCard.Interlink,
    onImportanceClick: () -> Unit,
    actions: CardActionsState,
    onRate: (Rating) -> Unit,
) {
    var revealed by remember(card.itemId) { mutableStateOf(false) }
    CardScaffold(card, actions, revealed, onImportanceClick, onReveal = { revealed = true }) {
        Eyebrow("CONNECTION")
        Spacer(Modifier.height(12.dp))
        MarkdownText(card.questionMd, style = MaterialTheme.typography.titleMedium)
        if (!revealed) {
            Spacer(Modifier.height(16.dp))
            Text("Tap anywhere to reveal", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
