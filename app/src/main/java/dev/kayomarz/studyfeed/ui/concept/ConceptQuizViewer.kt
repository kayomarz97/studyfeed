package dev.kayomarz.studyfeed.ui.concept

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kayomarz.studyfeed.ui.feed.CardActionsState
import dev.kayomarz.studyfeed.ui.feed.InterlinkCard

/**
 * "Quiz me on these links" (BUILD_PROMPT §8 A5): the focus concept's `interlink_quizzes`, one per pager
 * page. **Read-only** (like the A4 reference viewer) — reveal the answer, but no FSRS write; these are a
 * study aside from the map, not a graded feed session.
 */
@Composable
fun ConceptQuizViewer(quiz: ConceptMapViewModel.QuizState, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("← Map") }
                Text(quiz.conceptName?.let { "Links · $it" } ?: "Links",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val inert = CardActionsState(false, emptySet(), emptyList(), {}, {})
            Box(Modifier.fillMaxSize()) {
                when {
                    quiz.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    quiz.cards.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("No link-quizzes for this concept.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        val pager = rememberPagerState(pageCount = { quiz.cards.size })
                        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), pageSize = PageSize.Fill) { page ->
                            InterlinkCard(quiz.cards[page], onImportanceClick = {}, actions = inert, onRate = {})
                        }
                    }
                }
            }
        }
    }
}
