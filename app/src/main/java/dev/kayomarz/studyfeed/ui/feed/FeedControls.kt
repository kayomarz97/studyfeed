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
import dev.kayomarz.studyfeed.pack.PackCapabilities
import dev.kayomarz.studyfeed.pack.PackDatabase

/**
 * The A7 feed controls (user-approved redesign, trimmed 2026-07-19). The top row is now just **Home ·
 * Filter** — the Study-mode dropdown and the removable active-filter token row were removed (the mixed
 * feed is the only "mode"; clearing lives inside the sheet). Everything fine-grained lives in [FilterSheet].
 */

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

/**
 * How many *refinements* are set on a single-type feed — the badge count, excluding the locked content
 * type itself (a locked MCQ feed shouldn't read "Filter (1)" merely for being MCQs). In the mixed feed
 * ([lockedTypes] null) this is just [activeCount].
 */
fun FeedFilter.refinementCount(lockedTypes: List<String>?): Int {
    if (lockedTypes == null) return activeCount()
    return activeCount() - (itemTypesIn?.count { it in lockedTypes } ?: 0)
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

/**
 * The filter sheet — every fine-grained control (content type, importance, focus, subject) in one modal.
 * Changes apply live; the button dismisses. Reused verbatim by the feed, passages and library so the app
 * has one filter model everywhere (user request).
 *
 * On a **single-type feed** ([lockedTypes] non-null) the "Content type" group is hidden and "Clear all"
 * keeps you on the type — you refine *which* of that type you see (Subject / Importance / Focus), never
 * switch away from it (user request 2026-07-19).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    query: FeedQuery,
    subjects: List<PackDatabase.Subject>,
    tags: List<PackDatabase.TagDef>,
    total: Int,
    lockedTypes: List<String>?,
    capabilities: PackCapabilities?,
    onFilter: (FeedFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    val f = query.filter
    // "Clear all" resets to the empty filter — but on a single-type feed it keeps you ON the type.
    val cleared = if (lockedTypes != null) FeedFilter(itemTypesIn = lockedTypes) else FeedFilter()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Filter", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { onFilter(cleared) }) { Text("Clear all") }
            }

            // Content type is switchable only in the mixed feed; a single-type feed stays on its type.
            if (lockedTypes == null) {
                Group("Content type")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("mcq" to "MCQs", "flashcard" to "Flashcards", "reel" to "Reels",
                        "blob" to "Passages", "interlink" to "Links").forEach { (t, label) ->
                        val on = f.itemTypesIn?.contains(t) == true || f.itemType == t
                        // A type the loaded pack doesn't include is DISABLED, not hidden — so the user can
                        // see the app supports it and this pack simply lacks it (§0 multi_pack). MCQs are
                        // core (always on); null caps = still loading, so don't grey anything yet.
                        Chip(label, on, enabled = typePresent(t, capabilities)) { onFilter(toggleType(f, t)) }
                    }
                }
                if (capabilities != null && anyTypeAbsent(capabilities)) {
                    Spacer(Modifier.height(6.dp))
                    Text("Greyed types aren't in this pack.", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * Whether the loaded pack contains a given feed content type. MCQs are core (always present); the rest
 * are resolved from [PackCapabilities] (rows-authoritative). null caps = pack not opened yet → treat as
 * present so nothing greys out prematurely while loading.
 */
private fun typePresent(type: String, caps: PackCapabilities?): Boolean = when (type) {
    "mcq" -> true
    "flashcard" -> caps?.hasFlashcards ?: true
    "reel" -> caps?.hasReels ?: true
    "blob" -> caps?.hasBlobs ?: true
    "interlink" -> caps?.hasInterlink ?: true
    else -> true
}

private fun anyTypeAbsent(caps: PackCapabilities): Boolean =
    !caps.hasFlashcards || !caps.hasReels || !caps.hasBlobs || !caps.hasInterlink

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
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(selected = selected, enabled = enabled, onClick = onClick, label = { Text(label) })
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
