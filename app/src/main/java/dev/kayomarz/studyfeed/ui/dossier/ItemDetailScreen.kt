package dev.kayomarz.studyfeed.ui.dossier

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kayomarz.studyfeed.feed.FeedCard
import dev.kayomarz.studyfeed.ui.feed.CardActionsState
import dev.kayomarz.studyfeed.ui.feed.FlashcardCard
import dev.kayomarz.studyfeed.ui.feed.McqCard

/**
 * The in-place reference viewer (BUILD_PROMPT §8 A4): a related MCQ/flashcard opened from a dossier,
 * rendered with the SAME A2 card composables. **Read-only by design** (the A4 decision: "separate from
 * your main feed progress") — every interaction callback is a no-op, so revealing the answer here does
 * NOT write FSRS/mistakes. Back returns to the dossier.
 */
@Composable
fun ItemDetailScreen(vm: DossierViewModel, onBack: () -> Unit) {
    val ui by vm.item.collectAsStateWithLifecycle()

    // A viewer that never writes: star/tag disabled, no ratings, no answer recording.
    val inertActions = CardActionsState(
        starred = false, appliedTagKeys = emptySet(), tags = emptyList(),
        onToggleStar = {}, onToggleTag = {},
    )

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Dossier") }
            Text("Reference", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxSize()) {
            when (val card = ui.card) {
                null -> if (ui.loading) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                } else {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("This item could not be loaded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is FeedCard.Mcq -> McqCard(card, onImportanceClick = {}, actions = inertActions,
                    onAnswered = { _, _ -> }, onRate = {})
                // Read-only reference view: onSeen is a no-op (no FSRS write here, A4).
                is FeedCard.Flashcard -> FlashcardCard(card, onImportanceClick = {}, actions = inertActions,
                    onRate = {}, onSeen = {})
                else -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Unsupported item type.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
