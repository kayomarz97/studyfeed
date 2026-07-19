package dev.kayomarz.studyfeed.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kayomarz.studyfeed.feed.FeedRepository
import dev.kayomarz.studyfeed.pack.PackDatabase
import dev.kayomarz.studyfeed.progress.StudySession
import dev.kayomarz.studyfeed.stats.SubjectCoverage
import dev.kayomarz.studyfeed.ui.theme.AppColor

/**
 * The A7 dashboard home (user-approved v3): a "what's left" line, a prominent **Library** card, four
 * tappable **content-type tiles** each showing "X / total done", a **Review** tile (due · mistakes ·
 * starred), and a nav row where **Endless mix** sits beside **Map**. Every count comes from the user's
 * own progress; the exam label comes from `pack_manifest` (§0 multi_pack, no hardcoded string).
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel = viewModel(),
    onStartMixed: () -> Unit,
    onStartMcqs: () -> Unit,
    onStartCards: () -> Unit,
    onStartPassages: () -> Unit,
    onReview: () -> Unit,
    onNoPack: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    onManagePacks: () -> Unit,
    onResumeSession: (StudySession) -> Unit,
    onNewSession: () -> Unit,
    onMockExam: () -> Unit,
    onDrillWeakest: (SubjectCoverage) -> Unit,
    onLeeches: () -> Unit,
    onStarredDeck: () -> Unit,
    onHardDeck: () -> Unit,
    onEasyDeck: () -> Unit,
    onTagDeck: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    when {
        ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        !ui.hasPack -> LaunchedEffect(Unit) { onNoPack() }
        else -> Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Text("HERE'S WHAT'S LEFT", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ui.examName ?: "StudyFeed", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatInline("${ui.dueToday}", "due")
                StatInline("${fmt(ui.newRemaining)}", "new")
                StatInline("${ui.streakDays}d", "streak")
                if (ui.dailyTarget > 0) StatInline("${ui.reviewsToday}/${ui.dailyTarget}", "goal")
            }

            SessionsSection(ui.sessions, onResumeSession, onNewSession,
                onRename = vm::renameSession, onArchive = vm::archiveSession)

            Spacer(Modifier.height(18.dp))
            // Library — promoted to a first-class entry.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(AppColor.Mcq.copy(alpha = 0.10f)).clickable(onClick = onOpenLibrary)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Library", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Read any topic in detail", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("→", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }

            Spacer(Modifier.height(18.dp))
            Text("STUDY", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ContentTile("MCQs", ui.mcq, AppColor.Mcq, Modifier.weight(1f), onStartMcqs)
                ContentTile("Flashcards & reels", ui.cards, AppColor.Deck, Modifier.weight(1f), onStartCards)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ContentTile("Passages", ui.passages, AppColor.Passage, Modifier.weight(1f), onStartPassages)
                ReviewTile(ui.dueToday, ui.mistakes, ui.starred, ui.reviewProgress, Modifier.weight(1f), onReview)
            }

            if (ui.subjects.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Text("PROGRESS BY SUBJECT", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                ui.subjects.forEach { s -> SubjectBar(s); Spacer(Modifier.height(10.dp)) }
            }

            ToolsSection(ui, onMockExam, onDrillWeakest, onLeeches)
            DecksSection(ui.tags, ui.hard, ui.easy, onStarredDeck, onHardDeck, onEasyDeck, onTagDeck)

            Spacer(Modifier.height(22.dp))
            Text("EXPLORE", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onStartMixed, modifier = Modifier.weight(1f)) { Text("Endless mix") }
                OutlinedButton(onClick = onOpenMap, modifier = Modifier.weight(1f)) { Text("Map") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpenStats, modifier = Modifier.weight(1f)) { Text("Progress") }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onManagePacks, modifier = Modifier.fillMaxWidth()) { Text("Manage packs") }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun StatInline(value: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ContentTile(label: String, p: FeedRepository.TypeProgress, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.10f))
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.height(10.dp).width(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
            Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(10.dp))
        Text(fmt(p.done), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("/ ${fmt(p.total)} done", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ProgressBar(if (p.total > 0) p.done.toFloat() / p.total else 0f, color)
    }
}

@Composable
private fun ReviewTile(due: Int, mistakes: Int, starred: Int, progress: Float, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(AppColor.Review.copy(alpha = 0.10f))
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.height(10.dp).width(10.dp).clip(RoundedCornerShape(3.dp)).background(AppColor.Review))
            Text("Review", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(10.dp))
        Text("$due due", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("$mistakes mistakes · $starred starred", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        // Bar = fraction of the mistake pile cleared (real now — was hardcoded 0).
        ProgressBar(progress, AppColor.Review)
    }
}

@Composable
private fun SubjectBar(s: SubjectCoverage) {
    val accColor = s.accuracy?.let {
        when { it >= 0.7 -> AppColor.CorrectGreen; it >= 0.5 -> AppColor.Review; else -> Color(0xFFE06A6A) }
    } ?: MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
            Text(buildString {
                append("${fmt(s.done)}/${fmt(s.total)}")
                s.accuracy?.let { append(" · ${(it * 100).toInt()}%") }
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        ProgressBar(s.coverage.toFloat(), accColor)
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(3.dp)).background(color),
        )
    }
}

/** Thousands-separated, locale-free (deterministic). */
private fun fmt(n: Int): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val sb = StringBuilder()
    val firstGroup = s.length % 3
    if (firstGroup > 0) { sb.append(s, 0, firstGroup); if (s.length > firstGroup) sb.append(',') }
    var i = firstGroup
    while (i < s.length) { sb.append(s, i, i + 3); if (i + 3 < s.length) sb.append(','); i += 3 }
    return sb.toString()
}

// ── A7 cycle 3: named study sessions (P2) ────────────────────────────────────────────────────────

@Composable
private fun SessionsSection(
    sessions: List<StudySession>,
    onResume: (StudySession) -> Unit,
    onNew: () -> Unit,
    onRename: (Long, String) -> Unit,
    onArchive: (Long) -> Unit,
) {
    var renaming by remember { mutableStateOf<StudySession?>(null) }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("YOUR SESSIONS", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onNew) { Text("+ New") }
    }
    if (sessions.isEmpty()) {
        Text("Start a focused run — pick subjects, 5★, PYQ — and it remembers your place.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        sessions.forEach { s ->
            SessionCard(s, onResume, onRename = { renaming = s }, onArchive = onArchive)
            Spacer(Modifier.height(8.dp))
        }
    }
    renaming?.let { s ->
        RenameDialog(s.name ?: "Session", onDismiss = { renaming = null },
            onConfirm = { newName -> onRename(s.id, newName); renaming = null })
    }
}

@Composable
private fun SessionCard(
    s: StudySession, onResume: (StudySession) -> Unit, onRename: () -> Unit, onArchive: (Long) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .clickable { onResume(s) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.name ?: "Session", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${fmt(s.seen ?: 0)} seen · resume", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Resume ›", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Box {
            Text("  ⋮", modifier = Modifier.clickable { menu = true },
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; onArchive(s.id) })
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── A7 cycle 3: tools (mock exam · weak-spot drill · leeches) + decks (P4) ───────────────────────

@Composable
private fun ToolsSection(
    ui: HomeViewModel.Ui, onMockExam: () -> Unit, onDrillWeakest: (SubjectCoverage) -> Unit, onLeeches: () -> Unit,
) {
    Spacer(Modifier.height(22.dp))
    Text("TOOLS", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Button(onClick = onMockExam, modifier = Modifier.fillMaxWidth()) { Text("Mock exam") }
    if (ui.weakest != null || ui.leeches > 0) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ui.weakest?.let { w ->
                OutlinedButton(onClick = { onDrillWeakest(w) }, modifier = Modifier.weight(1f)) {
                    Text("Drill: ${w.name}", maxLines = 1)
                }
            }
            if (ui.leeches > 0) {
                OutlinedButton(onClick = onLeeches, modifier = Modifier.weight(1f)) { Text("Leeches (${ui.leeches})") }
            }
        }
    }
}

@Composable
private fun DecksSection(
    tags: List<PackDatabase.TagDef>, hard: Int, easy: Int,
    onStarred: () -> Unit, onHard: () -> Unit, onEasy: () -> Unit, onTag: (String) -> Unit,
) {
    Spacer(Modifier.height(22.dp))
    Text("DECKS", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    // Decks reflect YOUR study now: Starred + Hard (missed at least once) + Easy (mastered) + any tags.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DeckChip("⭐ Starred", onStarred)
        DeckChip("🔴 Hard" + if (hard > 0) " ($hard)" else "", onHard)
        DeckChip("🟢 Easy" + if (easy > 0) " ($easy)" else "", onEasy)
        tags.forEach { t -> DeckChip(t.label, { onTag(t.key) }) }
    }
}

@Composable
private fun DeckChip(label: String, onClick: () -> Unit) {
    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}
