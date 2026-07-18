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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    onHome: () -> Unit = {},
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    when {
        !ui.hasPack -> LaunchedEffect(Unit) { onNoPack() } // hand off to the import screen
        else -> Column(Modifier.fillMaxSize()) {
            // A7: a "Home" affordance leads the top bar (the dashboard is now the hub); the study-mode
            // row lets the user choose HOW to study; the A2 filter/sort bar sits below it. Everything
            // stays visible while a filter narrows the deck to empty, so the user can always undo it.
            Row(
                Modifier.statusBarsPadding().padding(top = 4.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onHome) { Text("‹ Home") }
                StudyModeBar(ui.studyMode, vm::setStudyMode, Modifier.weight(1f))
            }
            FeedControls(
                query = ui.query,
                subjects = ui.subjects,
                tags = ui.tags,
                onFilter = vm::setFilter,
                onSort = vm::setSort,
                modifier = Modifier.padding(top = 2.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    ui.cards.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            if (ui.query.filter.isAll) "This pack has no playable items."
                            else "Nothing matches this filter.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> FeedPager(ui, vm)
                }
            }
        }
    }

    ui.whySheetFor?.let { WhySheet(it, onDismiss = vm::dismissWhy) }
}

/** The A7 study-mode selector — Mixed / By subject / Recall / Skim (user backlog 2.5). */
@Composable
private fun StudyModeBar(
    current: FeedViewModel.StudyMode,
    onSelect: (FeedViewModel.StudyMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = listOf(
        FeedViewModel.StudyMode.MIXED to "Mixed",
        FeedViewModel.StudyMode.BY_SUBJECT to "By subject",
        FeedViewModel.StudyMode.RECALL to "Recall",
        FeedViewModel.StudyMode.SKIM to "Skim",
    )
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modes.forEach { (mode, label) ->
            FilterChip(selected = current == mode, onClick = { onSelect(mode) }, label = { Text(label) })
        }
    }
}

@Composable
private fun FeedPager(ui: FeedViewModel.Ui, vm: FeedViewModel) {
    val pager = rememberPagerState(pageCount = { ui.cards.size })
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { vm.onReachedIndex(pager.currentPage) }

    VerticalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        pageSize = PageSize.Fill,
        // Keep neighbours composed so a swipe reveals a ready card, not a flash of blank.
        beyondViewportPageCount = 1,
        // Advance on a SHORT swipe. Default (0.5) needs a slow drag past half the screen — on-device
        // that felt like "swipe a long distance". 0.15 commits to the next card once ~15% of the page
        // is dragged, which reads as a natural flick. (User confirmed "perfect", 2026-07-17, S23 Ultra.)
        flingBehavior = PagerDefaults.flingBehavior(state = pager, snapPositionalThreshold = 0.15f),
    ) { page ->
        val card = ui.cards[page]
        // Load this card's star/tag state the first time it's shown, so the icons reflect the DB.
        LaunchedEffect(card.itemType, card.itemId) { vm.loadMarks(card) }
        val marks = ui.marks[card.itemType to card.itemId] ?: FeedViewModel.CardMarks()
        val actions = CardActionsState(
            starred = marks.starred,
            appliedTagKeys = marks.tagKeys,
            tags = ui.tags,
            onToggleStar = { vm.toggleStar(card) },
            onToggleTag = { vm.toggleTag(card, it) },
        )
        Box(Modifier.fillMaxSize()) {
            CardBody(
                card = card,
                actions = actions,
                onWhy = { vm.showWhy(card) },
                onRate = {
                    vm.rate(card, it)
                    // A7 "buttons now work": after rating, advance to the next card (unless the user
                    // turned that off in Settings — then the rating just highlights and stays).
                    if (ui.advanceAfterRating) {
                        scope.launch { pager.animateScrollToPage((page + 1).coerceAtMost(ui.cards.size - 1)) }
                    }
                },
                onAnswered = { chosen, correct ->
                    if (card is FeedCard.Mcq) vm.onMcqAnswered(card, chosen, correct)
                },
                onRead = { if (card is FeedCard.Blob) vm.markBlobRead(card) },
            )
        }
    }
}

/** Dispatch to the right composable for the card's type. Five types (PATCH 04). */
@Composable
private fun CardBody(
    card: FeedCard,
    actions: CardActionsState,
    onWhy: () -> Unit,
    onRate: (Rating) -> Unit,
    onAnswered: (chosenIdx: Int, correct: Boolean) -> Unit,
    onRead: () -> Unit,
) {
    when (card) {
        is FeedCard.Mcq -> McqCard(card, onWhy, actions, onAnswered, onRate)
        is FeedCard.Flashcard -> FlashcardCard(card, onWhy, actions, onRate)
        is FeedCard.Reel -> ReelCard(card, onWhy, actions, onRate)
        is FeedCard.Blob -> BlobCard(card, onWhy, actions, onRead)
        is FeedCard.Interlink -> InterlinkCard(card, onWhy, actions, onRate)
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
