package dev.kayomarz.studyfeed.ui.feed

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kayomarz.studyfeed.feed.FeedFilter
import dev.kayomarz.studyfeed.feed.FeedQuery
import dev.kayomarz.studyfeed.pack.PackDatabase

/**
 * The feed's filter + sort bar (BUILD_PROMPT §8 A2). A horizontally-scrolling chip row plus a sort menu,
 * pinned over the pager. Every chip and dropdown value comes from the pack (subjects, tags) or the fixed
 * spec set — nothing exam-specific is hardcoded.
 *
 * Kept compact and one-tap: this rides above every card, so it must not steal the screen. Selecting a
 * chip toggles it; the ViewModel reloads the feed from the top for the new query.
 */
@Composable
fun FeedControls(
    query: FeedQuery,
    subjects: List<PackDatabase.Subject>,
    tags: List<PackDatabase.TagDef>,
    onFilter: (FeedFilter) -> Unit,
    onSort: (FeedQuery.Sort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val f = query.filter
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SortMenu(query.sort, onSort)

        // "All" clears every filter — visible only when something is active, so it reads as "reset".
        if (!f.isAll) {
            FilterChip(selected = false, onClick = { onFilter(FeedFilter()) }, label = { Text("✕ All") })
        }

        Chip("5★", f.importanceExact == 5) {
            onFilter(f.copy(importanceExact = if (f.importanceExact == 5) null else 5, importanceMin = null))
        }
        Chip("4★+", f.importanceMin == 4) {
            onFilter(f.copy(importanceMin = if (f.importanceMin == 4) null else 4, importanceExact = null))
        }
        Chip("PYQ", f.pyqOnly) { onFilter(f.copy(pyqOnly = !f.pyqOnly)) }
        Chip("Starred", f.starredOnly) { onFilter(f.copy(starredOnly = !f.starredOnly)) }
        Chip("Mistakes", f.mistakesOnly) { onFilter(f.copy(mistakesOnly = !f.mistakesOnly)) }
        Chip("MCQs", f.itemType == "mcq") { onFilter(f.copy(itemType = toggleType(f.itemType, "mcq"))) }
        Chip("Flashcards", f.itemType == "flashcard") { onFilter(f.copy(itemType = toggleType(f.itemType, "flashcard"))) }
        Chip("Reels", f.itemType == "reel") { onFilter(f.copy(itemType = toggleType(f.itemType, "reel"))) }
        Chip("Passages", f.itemType == "blob") { onFilter(f.copy(itemType = toggleType(f.itemType, "blob"))) }

        if (subjects.isNotEmpty()) SubjectMenu(f, subjects, onFilter)
        if (tags.isNotEmpty()) TagMenu(f, tags, onFilter)
    }
}

private fun toggleType(current: String?, type: String): String? = if (current == type) null else type

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SortMenu(sort: FeedQuery.Sort, onSort: (FeedQuery.Sort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Sort: ${sortLabel(sort)}") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        FeedQuery.Sort.entries.forEach { s ->
            DropdownMenuItem(text = { Text(sortLabel(s)) }, onClick = { onSort(s); open = false })
        }
    }
}

private fun sortLabel(s: FeedQuery.Sort): String = when (s) {
    FeedQuery.Sort.SMART -> "Smart"
    FeedQuery.Sort.IMPORTANCE_DESC -> "Importance ↓"
    FeedQuery.Sort.IMPORTANCE_ASC -> "Importance ↑"
    FeedQuery.Sort.PRIORITY -> "Priority"
    FeedQuery.Sort.RANDOM -> "Random"
}

@Composable
private fun SubjectMenu(f: FeedFilter, subjects: List<PackDatabase.Subject>, onFilter: (FeedFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = subjects.firstOrNull { it.id == f.subjectId }
    FilterChip(selected = f.subjectId != null, onClick = { open = true },
        label = { Text(current?.name ?: "Subject") })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("All subjects") },
            onClick = { onFilter(f.copy(subjectId = null)); open = false })
        subjects.forEach { s ->
            DropdownMenuItem(text = { Text(s.name) },
                onClick = { onFilter(f.copy(subjectId = s.id)); open = false })
        }
    }
}

@Composable
private fun TagMenu(f: FeedFilter, tags: List<PackDatabase.TagDef>, onFilter: (FeedFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = tags.firstOrNull { it.key == f.tagKey }
    FilterChip(selected = f.tagKey != null, onClick = { open = true },
        label = { Text(current?.label ?: "Tag") })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("Any tag") },
            onClick = { onFilter(f.copy(tagKey = null)); open = false })
        tags.forEach { t ->
            DropdownMenuItem(text = { Text(t.label) },
                onClick = { onFilter(f.copy(tagKey = t.key)); open = false })
        }
    }
}
