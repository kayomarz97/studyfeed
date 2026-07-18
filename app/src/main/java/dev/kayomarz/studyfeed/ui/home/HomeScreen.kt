package dev.kayomarz.studyfeed.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kayomarz.studyfeed.stats.SubjectStat

/**
 * The A7 dashboard home (user choice "Dashboard home first"). Opens on app launch once a pack is present:
 * shows what's left to study, then leads into the feed with "Start studying". Also the app's nav hub —
 * Search (library), Concept map, Progress (stats), Settings, and pack management all branch from here.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel = viewModel(),
    onStartStudying: () -> Unit,
    onNoPack: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    onManagePacks: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    when {
        ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        !ui.hasPack -> LaunchedEffect(Unit) { onNoPack() }
        else -> Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            // The exam label — read from pack_manifest, never a hardcoded string (§0 multi_pack).
            Text(ui.examName ?: "StudyFeed", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text("Here's what's left.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Due today", "${ui.dueToday}", Modifier.weight(1f))
                StatTile("New left", "${ui.newRemaining}", Modifier.weight(1f))
                StatTile("Streak", "${ui.streakDays}d", Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))
            // Today's target = the A3 deadline pace, and how far into it the user is today.
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)).padding(16.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        if (ui.dailyTarget > 0) "Today's goal: ${ui.reviewsToday} of ${ui.dailyTarget} new MCQs"
                        else "All new MCQs are in your rotation — keep the reviews going.",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (ui.dailyTarget > 0) {
                        Spacer(Modifier.height(10.dp))
                        ProgressBar(ui.reviewsToday.toFloat() / ui.dailyTarget.coerceAtLeast(1))
                        Spacer(Modifier.height(6.dp))
                        Text("Paced to finish every MCQ by your deadline (change it in Settings).",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(onClick = onStartStudying, modifier = Modifier.fillMaxWidth()) {
                Text("Start studying →")
            }

            if (ui.subjects.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Progress by subject", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
                ui.subjects.forEach { s -> SubjectBar(s); Spacer(Modifier.height(10.dp)) }
            }

            Spacer(Modifier.height(24.dp))
            Text("Explore", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.weight(1f)) { Text("Search") }
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
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)).padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SubjectBar(s: SubjectStat) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.name, style = MaterialTheme.typography.bodyMedium)
            Text("${(s.mastery * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        ProgressBar(s.mastery.toFloat())
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary),
        )
    }
}
