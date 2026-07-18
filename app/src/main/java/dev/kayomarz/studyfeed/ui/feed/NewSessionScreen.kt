package dev.kayomarz.studyfeed.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kayomarz.studyfeed.feed.FeedFilter
import dev.kayomarz.studyfeed.feed.FeedQuery
import dev.kayomarz.studyfeed.feed.SessionConfig

/**
 * "New session" (A7 cycle 3, P2) — the pick-and-choose config the user asked for: content type, 5★/4★,
 * PYQ/Starred/Mistakes, a subject, and the order. It builds a [FeedQuery], previews the auto-name, and
 * creates + opens a named session (which then remembers its place). Reuses the same filter vocabulary as
 * the Feed's Filter sheet, as a full screen so the choices are deliberate.
 */
@Composable
fun NewSessionScreen(vm: FeedViewModel, onStart: () -> Unit, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    val types: SnapshotStateList<String> = remember { mutableStateListOf() }
    var importance by remember { mutableStateOf(0) }   // 0=any, 5, or -4 (means "4★+")
    var pyq by remember { mutableStateOf(false) }
    var starred by remember { mutableStateOf(false) }
    var mistakes by remember { mutableStateOf(false) }
    var subjectId by remember { mutableStateOf<Long?>(null) }
    var sort by remember { mutableStateOf(FeedQuery.Sort.SMART) }
    var subjectMenu by remember { mutableStateOf(false) }

    fun query(): FeedQuery = FeedQuery(
        sort = sort,
        filter = FeedFilter(
            itemTypesIn = types.toList().takeIf { it.isNotEmpty() && it.size < 4 },
            importanceExact = if (importance == 5) 5 else null,
            importanceMin = if (importance == -4) 4 else null,
            pyqOnly = pyq, starredOnly = starred, mistakesOnly = mistakes,
            subjectId = subjectId,
        ),
    )

    val name = SessionConfig.autoName(query()) { sid -> ui.subjects.firstOrNull { it.id == sid }?.name }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth()) { TextButton(onClick = onBack) { Text("‹ Home") } }
        Text("New session", style = MaterialTheme.typography.headlineMedium)
        Text("Pick what goes in — I'll name it for you.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Label("Content")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("mcq" to "MCQs", "flashcard" to "Flashcards", "reel" to "Reels", "blob" to "Passages").forEach { (k, lbl) ->
                FilterChip(selected = types.contains(k),
                    onClick = { if (types.contains(k)) types.remove(k) else types.add(k) },
                    label = { Text(lbl) })
            }
        }

        Label("Importance")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = importance == 5, onClick = { importance = if (importance == 5) 0 else 5 }, label = { Text("5★ only") })
            FilterChip(selected = importance == -4, onClick = { importance = if (importance == -4) 0 else -4 }, label = { Text("4★ +") })
        }

        Label("Focus")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = pyq, onClick = { pyq = !pyq }, label = { Text("PYQ only") })
            FilterChip(selected = starred, onClick = { starred = !starred }, label = { Text("Starred") })
            FilterChip(selected = mistakes, onClick = { mistakes = !mistakes }, label = { Text("Mistakes") })
        }

        if (ui.subjects.isNotEmpty()) {
            Label("Subject")
            Box {
                OutlinedButton(onClick = { subjectMenu = true }) {
                    Text(subjectId?.let { id -> ui.subjects.firstOrNull { it.id == id }?.name } ?: "Any subject")
                }
                DropdownMenu(expanded = subjectMenu, onDismissRequest = { subjectMenu = false }) {
                    DropdownMenuItem(text = { Text("Any subject") }, onClick = { subjectId = null; subjectMenu = false })
                    ui.subjects.forEach { s ->
                        DropdownMenuItem(text = { Text(s.name) }, onClick = { subjectId = s.id; subjectMenu = false })
                    }
                }
            }
        }

        Label("Order")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val orders = listOf(FeedQuery.Sort.SMART to "Smart", FeedQuery.Sort.SUBJECT to "By subject",
                FeedQuery.Sort.PRIORITY to "Weakest first", FeedQuery.Sort.RANDOM to "Shuffle")
            orders.forEach { (s, lbl) ->
                FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(lbl) })
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("WILL BE NAMED", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.createSession(query()) { onStart() } }, modifier = Modifier.fillMaxWidth()) {
            Text("Start session")
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
}
