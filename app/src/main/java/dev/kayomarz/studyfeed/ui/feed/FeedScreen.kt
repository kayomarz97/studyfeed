package dev.kayomarz.studyfeed.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kayomarz.studyfeed.feed.FeedCard
import dev.kayomarz.studyfeed.feed.ImportanceSignals

/**
 * The Study Feed (BUILD_PROMPT §8 A2) — an infinite vertical pager, one card per page, swipe up for the
 * next. This is the screen the app opens to once a pack is imported.
 *
 * **AMOLED, swipe feel and scroll smoothness are judged on the phone** (plan §3.6 / D6) — an emulator
 * cannot show what `#000000` looks like on a panel where the pixels are off. Everything else (the card
 * logic, FSRS writes, the "why" sheet) is verifiable without one.
 */
@Composable
fun FeedScreen(
    vm: FeedViewModel = viewModel(),
    onNoPack: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    when {
        ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        !ui.hasPack -> LaunchedEffect(Unit) { onNoPack() } // hand off to the import screen
        ui.cards.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("This pack has no playable items.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val pager = rememberPagerState(pageCount = { ui.cards.size })
            LaunchedEffect(pager.currentPage) { vm.onReachedIndex(pager.currentPage) }

            VerticalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                pageSize = PageSize.Fill,
                // Keep neighbours composed so a swipe reveals a ready card, not a flash of blank.
                beyondViewportPageCount = 1,
            ) { page ->
                val card = ui.cards[page]
                Box(Modifier.fillMaxSize()) {
                    CardBody(
                        card = card,
                        onWhy = { vm.showWhy(card) },
                        onRate = { vm.rate(card, it) },
                        onAnswered = { chosen, correct ->
                            if (!correct && card is FeedCard.Mcq) vm.recordMistake(card, chosen)
                        },
                        onRead = { if (card is FeedCard.Blob) vm.markBlobRead(card) },
                    )
                }
            }
        }
    }

    ui.whySheetFor?.let { WhySheet(it, onDismiss = vm::dismissWhy) }
}

/** Dispatch to the right composable for the card's type. Five types (PATCH 04). */
@Composable
private fun CardBody(
    card: FeedCard,
    onWhy: () -> Unit,
    onRate: (Rating) -> Unit,
    onAnswered: (chosenIdx: Int, correct: Boolean) -> Unit,
    onRead: () -> Unit,
) {
    when (card) {
        is FeedCard.Mcq -> McqCard(card, onWhy, onAnswered, onRate)
        is FeedCard.Flashcard -> FlashcardCard(card, onWhy, onRate)
        is FeedCard.Reel -> ReelCard(card, onWhy, onRate)
        is FeedCard.Blob -> BlobCard(card, onWhy, onRead)
        is FeedCard.Interlink -> InterlinkCard(card, onWhy, onRate)
    }
}

/**
 * The "why is this important" sheet (§8 A2 acceptance 3) — renders `importance_signals_json` as human
 * phrases, highest contribution first. Tapping the importance indicator on any card opens this.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WhySheet(card: FeedCard, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Why ${card.importance}★", style = MaterialTheme.typography.titleLarge,
                    color = Importance.color(card.importance))
                Spacer(Modifier.width(12.dp))
                PyqBadge(card.pyqYears)
            }
            Spacer(Modifier.height(16.dp))

            val signals: ImportanceSignals? = card.importanceSignals
            if (signals == null || signals.ranked().isEmpty()) {
                Text("This item's importance comes from the pack's overall scoring.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                signals.ranked().forEach { (label, weight) ->
                    SignalRow(label, weight)
                    Spacer(Modifier.height(10.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Importance is read from the pack, never computed on the phone.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SignalRow(label: String, weight: Double) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${(weight * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(weight.coerceIn(0.0, 1.0).toFloat())
                    .clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
