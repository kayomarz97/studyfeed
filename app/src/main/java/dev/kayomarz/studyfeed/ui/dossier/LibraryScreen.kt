package dev.kayomarz.studyfeed.ui.dossier

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kayomarz.studyfeed.dossier.DossierListItem
import dev.kayomarz.studyfeed.dossier.DossierRepository
import dev.kayomarz.studyfeed.ui.feed.ImportanceIndicator
import dev.kayomarz.studyfeed.ui.theme.AppColor

/**
 * The Library / Search screen (BUILD_PROMPT §8 A4; **A7 redesign**): search a topic by name/alias/body,
 * plus quick filters — **5★ · 4★+ · PYQ · Subject** (user request). Each row shows a **Map** button when
 * the topic has a concept map. Tapping a row opens its dossier. Pure render — the pack's `topic_pages`.
 */
@Composable
fun LibraryScreen(
    vm: DossierViewModel,
    onOpenDossier: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenMap: (Long) -> Unit = {},
) {
    val ui by vm.library.collectAsStateWithLifecycle()
    val f = ui.filter

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Home") }
            Text("Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::search,
            singleLine = true,
            placeholder = { Text("Search a topic, drug, or concept…") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        // Filter chips (A7): importance · PYQ · subject.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = f.importanceExact == 5,
                onClick = { vm.setLibraryFilter(f.copy(importanceExact = if (f.importanceExact == 5) null else 5, importanceMin = null)) },
                label = { Text("5★") })
            FilterChip(selected = f.importanceMin == 4,
                onClick = { vm.setLibraryFilter(f.copy(importanceMin = if (f.importanceMin == 4) null else 4, importanceExact = null)) },
                label = { Text("4★+") })
            FilterChip(selected = f.pyqOnly,
                onClick = { vm.setLibraryFilter(f.copy(pyqOnly = !f.pyqOnly)) },
                label = { Text("PYQ") })
            FilterChip(selected = ui.starredOnly, onClick = { vm.toggleStarredOnly() },
                label = { Text("★ Starred") })
            SubjectFilter(f, ui.subjects, vm::setLibraryFilter)
        }

        Box(Modifier.fillMaxSize()) {
            when {
                ui.loading && ui.results.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                ui.results.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        if (ui.query.isBlank() && f.isAll) "This pack has no topic pages."
                        else "No topic matches your search + filters.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.results, key = { it.topicPageId }) { row ->
                        DossierRow(row, onClick = { onOpenDossier(row.topicPageId) }, onOpenMap = onOpenMap)
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SubjectFilter(
    f: DossierRepository.DossierFilter,
    subjects: List<String>,
    onFilter: (DossierRepository.DossierFilter) -> Unit,
) {
    if (subjects.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    FilterChip(selected = f.subject != null, onClick = { open = true },
        label = { Text(f.subject ?: "Subject") })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("All subjects") }, onClick = { onFilter(f.copy(subject = null)); open = false })
        subjects.forEach { s ->
            DropdownMenuItem(text = { Text(s) }, onClick = { onFilter(f.copy(subject = s)); open = false })
        }
    }
}

@Composable
private fun DossierRow(row: DossierListItem, onClick: () -> Unit, onOpenMap: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (row.subjects.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                // Cap the subject list so a 13-subject topic doesn't wrap to four lines (A7 cleanup).
                val shown = row.subjects.take(2).joinToString(" · ")
                val extra = row.subjects.size - 2
                Text(if (extra > 0) "$shown +$extra" else shown, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        row.conceptId?.let { cid ->
            Text("🕸 Map", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColor.MapTeal,
                modifier = Modifier.clickable { onOpenMap(cid) }.padding(horizontal = 6.dp, vertical = 2.dp))
        }
        ImportanceIndicator(row.importance, onClick = onClick)
    }
}
