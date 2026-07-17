package dev.kayomarz.studyfeed.ui.dossier

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kayomarz.studyfeed.dossier.DossierListItem
import dev.kayomarz.studyfeed.ui.feed.ImportanceIndicator

/**
 * The Library / Search screen (BUILD_PROMPT §8 A4): search a topic by name/alias/body, results ranked
 * name-hits-first then importance ↓. Tapping a row opens its dossier. Pure render — the list is the
 * pack's `topic_pages`, nothing generated.
 *
 * ⚠ Visual layout is functional, not final — the A7 design pass owns polish (this screen isn't in the
 * A7 *feed* backlog, so it stays simple and correct).
 */
@Composable
fun LibraryScreen(
    vm: DossierViewModel,
    onOpenDossier: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val ui by vm.library.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::search,
            singleLine = true,
            placeholder = { Text("Search a topic, drug, or concept…") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )

        Box(Modifier.fillMaxSize()) {
            when {
                ui.loading && ui.results.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                ui.results.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        if (ui.query.isBlank()) "This pack has no topic pages."
                        else "No topic matches “${ui.query}”.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.results, key = { it.topicPageId }) { row ->
                        DossierRow(row, onClick = { onOpenDossier(row.topicPageId) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DossierRow(row: DossierListItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (row.subjects.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(row.subjects.joinToString(" · "), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ImportanceIndicator(row.importance, onClick = onClick)
    }
}
