package dev.kayomarz.studyfeed.ui.feed

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kayomarz.studyfeed.feed.FeedFilter
import dev.kayomarz.studyfeed.feed.FeedQuery
import dev.kayomarz.studyfeed.pack.PackDatabase

/**
 * The A7 feed controls (user-approved redesign). The old three stacked rows (mode chips + Sort + a 12-chip
 * filter rail) collapse to ONE row — a **Study ▾** mode menu and a **Filter** button — plus a row of
 * removable active-filter tokens. Everything fine-grained lives in the [FilterSheet].
 */

/** Study mode presets (how to study) as a compact dropdown: Mixed / By subject / Recall / Skim. */
@Composable
fun StudyModeMenu(
    current: FeedViewModel.StudyMode,
    onSelect: (FeedViewModel.StudyMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier) {
        Text("Study: ${modeLabel(current)} ▾", fontWeight = FontWeight.SemiBold)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        FeedViewModel.StudyMode.entries.forEach { m ->
            DropdownMenuItem(text = { Text(modeLabel(m)) }, onClick = { onSelect(m); open = false })
        }
    }
}

private fun modeLabel(m: FeedViewModel.StudyMode) = when (m) {
    FeedViewModel.StudyMode.MIXED -> "Mixed"
    FeedViewModel.StudyMode.BY_SUBJECT -> "By subject"
    FeedViewModel.StudyMode.RECALL -> "Recall"
    FeedViewModel.StudyMode.SKIM -> "Skim"
}

/** The "⚙ Filter" button, showing how many filters are active. */
@Composable
fun FilterButton(activeCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(if (activeCount > 0) "⚙ Filter ($activeCount)" else "⚙ Filter")
    }
}

/** How many filter fields are set — drives [FilterButton]'s badge. */
fun FeedFilter.activeCount(): Int {
    var n = 0
    if (importanceExact != null || importanceMin != null) n++
    if (pyqOnly) n++
    if (starredOnly) n++
    if (mistakesOnly) n++
    if (!itemTypesIn.isNullOrEmpty()) n += itemTypesIn.size
    if (itemType != null) n++
    if (subjectId != null) n++
    if (tagKey != null) n++
    return n
}

/** A human phrase for each active filter, for the empty-state message and tokens. */
fun FeedFilter.activeLabels(subjects: List<PackDatabase.Subject>, tags: List<PackDatabase.TagDef>): List<String> {
    val out = mutableListOf<String>()
    importanceExact?.let { out += "$it★" }
    importanceMin?.let { out += "$it★+" }
    if (pyqOnly) out += "PYQ"
    if (starredOnly) out += "Starred"
    if (mistakesOnly) out += "Mistakes"
    itemTypesIn?.forEach { out += typeLabel(it) }
    itemType?.let { out += typeLabel(it) }
    subjectId?.let { id -> subjects.firstOrNull { it.id == id }?.let { out += it.name } }
    tagKey?.let { k -> tags.firstOrNull { it.key == k }?.let { out += it.label } }
    return out
}

private fun typeLabel(t: String) = when (t) {
    "mcq" -> "MCQs"; "flashcard" -> "Flashcards"; "reel" -> "Reels"; "blob" -> "Passages"
    "interlink" -> "Links"; else -> t
}

/** Removable active-filter tokens shown under the top row (tap ✕ to clear that one). */
@Composable
fun ActiveFilterTokens(
    filter: FeedFilter,
    subjects: List<PackDatabase.Subject>,
    tags: List<PackDatabase.TagDef>,
    onFilter: (FeedFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (filter.isAll) return
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(selected = true, onClick = { onFilter(FeedFilter()) }, label = { Text("Clear all ✕") })
        filter.activeLabels(subjects, tags).forEach { label ->
            FilterChip(selected = true, onClick = { onFilter(clearOne(filter, label, subjects, tags)) },
                label = { Text("$label ✕") })
        }
    }
}

/** Remove the single filter field that produced [label]. */
private fun clearOne(f: FeedFilter, label: String, subjects: List<PackDatabase.Subject>, tags: List<PackDatabase.TagDef>): FeedFilter {
    return when {
        label == "PYQ" -> f.copy(pyqOnly = false)
        label == "Starred" -> f.copy(starredOnly = false)
        label == "Mistakes" -> f.copy(mistakesOnly = false)
        label.endsWith("★+") -> f.copy(importanceMin = null)
        label.endsWith("★") -> f.copy(importanceExact = null)
        label in listOf("MCQs", "Flashcards", "Reels", "Passages", "Links") -> {
            val t = typeKey(label)
            f.copy(itemTypesIn = f.itemTypesIn?.filter { it != t }?.ifEmpty { null },
                itemType = if (f.itemType == t) null else f.itemType)
        }
        subjects.any { it.name == label } -> f.copy(subjectId = null)
        tags.any { it.label == label } -> f.copy(tagKey = null)
        else -> f
    }
}

private fun typeKey(label: String) = when (label) {
    "MCQs" -> "mcq"; "Flashcards" -> "flashcard"; "Reels" -> "reel"; "Passages" -> "blob"; "Links" -> "interlink"; else -> label
}

/**
 * The filter sheet — every fine-grained control (content type, importance, focus, subject) in one modal.
 * Changes apply live; the button dismisses. Reused verbatim by the feed, passages and library so the app
 * has one filter model everywhere (user request).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    query: FeedQuery,
    subjects: List<PackDatabase.Subject>,
    tags: List<PackDatabase.TagDef>,
    total: Int,
    onFilter: (FeedFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    val f = query.filter
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Filter", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { onFilter(FeedFilter()) }) { Text("Clear all") }
            }

            Group("Content type")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("mcq" to "MCQs", "flashcard" to "Flashcards", "reel" to "Reels",
                    "blob" to "Passages", "interlink" to "Links").forEach { (t, label) ->
                    val on = f.itemTypesIn?.contains(t) == true || f.itemType == t
                    Chip(label, on) { onFilter(toggleType(f, t)) }
                }
            }

            Group("Importance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("5★", f.importanceExact == 5) {
                    onFilter(f.copy(importanceExact = if (f.importanceExact == 5) null else 5, importanceMin = null))
                }
                Chip("4★+", f.importanceMin == 4) {
                    onFilter(f.copy(importanceMin = if (f.importanceMin == 4) null else 4, importanceExact = null))
                }
            }

            Group("Focus")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("PYQ", f.pyqOnly) { onFilter(f.copy(pyqOnly = !f.pyqOnly)) }
                Chip("Starred", f.starredOnly) { onFilter(f.copy(starredOnly = !f.starredOnly)) }
                Chip("Mistakes", f.mistakesOnly) { onFilter(f.copy(mistakesOnly = !f.mistakesOnly)) }
            }

            if (subjects.isNotEmpty()) {
                Group("Subject")
                SubjectMenu(f, subjects, onFilter)
            }
            if (tags.isNotEmpty()) {
                Group("Tag")
                TagMenu(f, tags, onFilter)
            }

            Spacer(Modifier.height(18.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(if (f.isAll) "Show all $total items" else "Show $total items")
            }
        }
    }
}

private fun toggleType(f: FeedFilter, type: String): FeedFilter {
    val cur = f.itemTypesIn?.toMutableList() ?: mutableListOf()
    if (type in cur) cur.remove(type) else cur.add(type)
    // migrate off the legacy single itemType when using the multi-select sheet
    return f.copy(itemTypesIn = cur.ifEmpty { null }, itemType = null)
}

@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SubjectMenu(f: FeedFilter, subjects: List<PackDatabase.Subject>, onFilter: (FeedFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = subjects.firstOrNull { it.id == f.subjectId }
    FilterChip(selected = f.subjectId != null, onClick = { open = true },
        label = { Text(current?.name ?: "Any subject") })
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
        label = { Text(current?.label ?: "Any tag") })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("Any tag") },
            onClick = { onFilter(f.copy(tagKey = null)); open = false })
        tags.forEach { t ->
            DropdownMenuItem(text = { Text(t.label) },
                onClick = { onFilter(f.copy(tagKey = t.key)); open = false })
        }
    }
}
