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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
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
    onOpenTopic: (Long) -> Unit = {},
    onOpenMap: (Long) -> Unit = {},
    onOpenLibrary: () -> Unit = {},
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val view = LocalView.current
    // Keep the screen awake while studying, if the user turned it on — a plain View flag (no wake-lock,
    // no permission), cleared the moment we leave the feed so it can't drain the battery in the background.
    DisposableEffect(ui.keepScreenOn) {
        view.keepScreenOn = ui.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // A light haptic tick on a card interaction, gated by the user's setting. `performHapticFeedback` is a
    // View affordance — it needs NO VIBRATE permission, so the offline guard stays green.
    val tap: () -> Unit = {
        if (ui.haptics) view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    CompositionLocalProvider(LocalTapFeedback provides tap) {
        when {
            // Bounce to import ONLY when there is genuinely no pack — never mid-load, or a feed entered
            // right after import (while the pack self-heals) would flick back to the import screen.
            !ui.hasPack && !ui.loading -> LaunchedEffect(Unit) { onNoPack() }
            else -> Column(Modifier.fillMaxSize()) {
                var showFilter by remember { mutableStateOf(false) }
                // A7 (2026-07-19): one-row controls = Home · Filter. The Study-mode menu and the
                // active-filter token row were removed — the mixed feed is the only "mode", and clearing
                // filters lives inside the Filter sheet.
                Row(
                    Modifier.statusBarsPadding().padding(top = 4.dp, start = 4.dp, end = 8.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onHome) { Text("‹ Home") }
                    Spacer(Modifier.weight(1f))
                    FilterButton(ui.query.filter.refinementCount(ui.lockedTypes), onClick = { showFilter = true })
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                        ui.cards.isEmpty() -> {
                            // Honest empty state: a filter that excludes everything reads differently from a
                            // pack that has nothing to play (ABSENT). present = "the pack has items to show if
                            // you drop the filter" — false only when there's no filter AND no items at all.
                            val filterActive = !ui.query.filter.isAll
                            val present = filterActive || ui.total > 0
                            when (EmptyStates.decide(present, filterActive)) {
                                EmptyCase.ABSENT ->
                                    EmptyState("Nothing to study", "This pack has no playable items.")
                                EmptyCase.NOTHING_MATCHES -> {
                                    val labels = ui.query.filter.activeLabels(ui.subjects, ui.tags)
                                    EmptyState("Nothing matches",
                                        "No items match: ${labels.joinToString(" · ")}.\nRemove a filter to see more.")
                                }
                                EmptyCase.NOTHING_DUE -> EmptyState("All caught up",
                                    "Nothing left to study here right now — great work.")
                            }
                        }
                        else -> FeedPager(ui, vm, onOpenTopic, onOpenMap, onOpenLibrary)
                    }
                }
                if (showFilter) {
                    FilterSheet(ui.query, ui.subjects, ui.tags, ui.total, ui.lockedTypes, ui.capabilities,
                        vm::setFilter, onDismiss = { showFilter = false })
                }
            }
        }

        ui.whySheetFor?.let { WhySheet(it, onDismiss = vm::dismissWhy) }
    }
}

@Composable
private fun FeedPager(
    ui: FeedViewModel.Ui,
    vm: FeedViewModel,
    onOpenTopic: (Long) -> Unit,
    onOpenMap: (Long) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    // Start at the resume index (a fresh FeedPager is composed after every reload, since the loading
    // spinner briefly replaces it, so initialPage re-applies each time) — the user lands where they left
    // off, and can swipe UP through the back-buffer of cards they've already seen.
    val pager = rememberPagerState(
        initialPage = ui.startAt.coerceIn(0, (ui.cards.size - 1).coerceAtLeast(0)),
        pageCount = { ui.cards.size },
    )
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
            starColor = marks.starColor,
            onSetStarColor = { vm.setStarColor(card, it) },
            onOpenTopic = card.topicPageId?.let { tp -> { onOpenTopic(tp) } },
            onOpenMap = card.conceptId?.let { cid -> { onOpenMap(cid) } },
            onOpenLibrary = onOpenLibrary,
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
                onSeen = { vm.onSeen(card) },
                initialChosen = (card as? FeedCard.Mcq)?.let { ui.answeredMcqs[it.itemId] },
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
    onSeen: () -> Unit,
    initialChosen: Int?,
) {
    when (card) {
        is FeedCard.Mcq -> McqCard(card, onWhy, actions, onAnswered, onRate, initialChosen)
        is FeedCard.Flashcard -> FlashcardCard(card, onWhy, actions, onRate, onSeen)
        is FeedCard.Reel -> ReelCard(card, onWhy, actions, onRate, onSeen)
        is FeedCard.Blob -> BlobCard(card, onWhy, actions, onRead)
        is FeedCard.Interlink -> InterlinkCard(card, onWhy, actions, onRate, onSeen)
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
