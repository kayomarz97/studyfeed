package dev.kayomarz.studyfeed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kayomarz.studyfeed.pack.PackImporter
import dev.kayomarz.studyfeed.ui.PackUiState
import dev.kayomarz.studyfeed.ui.concept.ConceptMapScreen
import dev.kayomarz.studyfeed.ui.concept.ConceptMapViewModel
import dev.kayomarz.studyfeed.ui.dossier.DossierScreen
import dev.kayomarz.studyfeed.ui.dossier.DossierViewModel
import dev.kayomarz.studyfeed.ui.dossier.ItemDetailScreen
import dev.kayomarz.studyfeed.ui.dossier.LibraryScreen
import dev.kayomarz.studyfeed.feed.FeedFilter
import dev.kayomarz.studyfeed.ui.feed.FeedScreen
import dev.kayomarz.studyfeed.ui.feed.FeedViewModel
import dev.kayomarz.studyfeed.ui.home.HomeScreen
import dev.kayomarz.studyfeed.ui.home.HomeViewModel
import dev.kayomarz.studyfeed.ui.settings.SettingsScreen
import dev.kayomarz.studyfeed.ui.settings.SettingsViewModel
import dev.kayomarz.studyfeed.ui.stats.StatsScreen
import dev.kayomarz.studyfeed.ui.stats.StatsViewModel
import dev.kayomarz.studyfeed.ui.theme.StudyFeedTheme

/**
 * The app's ONLY component (BUILD_PROMPT §8 A0/A1).
 *
 * There is no Service, no BroadcastReceiver, no ContentProvider of our own, and there never will be —
 * `verifyOfflineGuarantees` fails the build if one appears in the merged manifest.
 *
 * **A1 scope: import a pack, show what it configured itself into, switch exams, export/import progress.
 * The Study Feed itself is A2 — do not grow this into it.**
 *
 * ⚠ **Every user-visible exam label on this screen comes from the pack**, never from a string resource.
 * Search this file for an exam name and you will not find one; that is the point (§0 `multi_pack`).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StudyFeedTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root()
                }
            }
        }
    }
}

/**
 * Top-level routing between the A-stage screens (**A7 redesign 2026-07-18**):
 *  - **Home** (A7) — the dashboard the app now opens to: what's left + the nav hub.
 *  - **Feed** (A2/A3) — the scrolling study feed, reached via "Start studying".
 *  - **Settings** (A7) — deadline/re-drill/intensity knobs + export/restore.
 *  - **Manage** (A1) — import/switch packs.
 *  - **Library → Dossier → Reference** (A4) · **ConceptMap** (A5) · **Stats** (A6).
 *
 * A deliberately simple `when`-over-sealed router (no nav library — it would pull dependencies the guard
 * has no reason to allow). Every screen shares its ONE activity-scoped ViewModel, so state persists across
 * hops. Home is the hub: Feed/Library/Map/Stats/Settings all return to it.
 */
private sealed interface Screen {
    data object Home : Screen
    data object Feed : Screen
    data object Settings : Screen
    data object Manage : Screen
    data object Library : Screen
    data object ConceptMap : Screen
    data object Stats : Screen
    data class Dossier(val topicPageId: Long) : Screen
    data class ItemDetail(val type: String, val refId: Long) : Screen
}

@Composable
private fun Root() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    // Remembered so the Reference viewer's Back returns to the dossier it was opened from.
    var currentDossierId by remember { mutableStateOf(0L) }
    val homeVm: HomeViewModel = viewModel()
    val feedVm: FeedViewModel = viewModel()
    val dossierVm: DossierViewModel = viewModel()
    val conceptVm: ConceptMapViewModel = viewModel()
    val statsVm: StatsViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()
    // Refresh the dashboard's counts whenever we return to it.
    val goHome = { homeVm.load(); screen = Screen.Home }

    // Android system back (gesture/button). Without this, back exits the app from any screen because our
    // simple `when`-router has no back stack. Map each screen to its parent; on Home, stay disabled so the
    // OS handles back normally (exit). Mirrors each screen's on-screen back affordance.
    androidx.activity.compose.BackHandler(enabled = screen != Screen.Home) {
        screen = when (val s = screen) {
            is Screen.Dossier -> Screen.Library
            is Screen.ItemDetail -> Screen.Dossier(currentDossierId)
            Screen.Home -> Screen.Home // unreachable (handler disabled on Home)
            else -> { homeVm.load(); Screen.Home }
        }
    }

    when (screen) {
        Screen.Home -> HomeScreen(
            vm = homeVm,
            onStartStudying = { screen = Screen.Feed },
            onNoPack = { screen = Screen.Manage },
            onOpenLibrary = { screen = Screen.Library },
            onOpenMap = { screen = Screen.ConceptMap },
            onOpenStats = { statsVm.load(); screen = Screen.Stats },
            onOpenSettings = { settingsVm.load(); screen = Screen.Settings },
            onManagePacks = { screen = Screen.Manage },
        )
        Screen.Feed -> FeedScreen(
            vm = feedVm,
            onNoPack = { screen = Screen.Manage },
            onHome = goHome,
        )
        Screen.Settings -> SettingsScreen(
            vm = settingsVm,
            onBack = goHome,
            onManagePacks = { screen = Screen.Manage },
            onChanged = { feedVm.reapplySettings() },
        )
        Screen.Stats -> StatsScreen(
            vm = statsVm,
            onReviewMistakes = { feedVm.setFilter(FeedFilter(mistakesOnly = true)); screen = Screen.Feed },
            onBack = goHome,
        )
        Screen.ConceptMap -> ConceptMapScreen(
            vm = conceptVm,
            onOpenDossier = { pid -> currentDossierId = pid; dossierVm.openDossier(pid); screen = Screen.Dossier(pid) },
            onBack = goHome,
        )
        Screen.Manage -> PackScreen(onOpenFeed = { screen = Screen.Feed })
        Screen.Library -> LibraryScreen(
            vm = dossierVm,
            onOpenDossier = { id -> currentDossierId = id; dossierVm.openDossier(id); screen = Screen.Dossier(id) },
            onBack = goHome,
        )
        is Screen.Dossier -> DossierScreen(
            vm = dossierVm,
            onOpenItem = { type, refId -> dossierVm.openItem(type, refId); screen = Screen.ItemDetail(type, refId) },
            onBack = { screen = Screen.Library },
        )
        is Screen.ItemDetail -> ItemDetailScreen(
            vm = dossierVm,
            onBack = { screen = Screen.Dossier(currentDossierId) },
        )
    }
}

@Composable
private fun PackScreen(onOpenFeed: () -> Unit = {}, vm: PackUiState = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    // SAF: a DIRECTORY picker, because a pack is a directory (spec/SPEC.md §1) — db + manifest +
    // figures. No storage permission is needed or requested; SAF grants access to what the user picks.
    val pickPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.importFrom(it) }
    }
    val exportTo = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { vm.exportProgress(it) } }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importProgress(it) }
    }

    Column(
        // Inset below the status bar (edge-to-edge on targetSdk 36) so the header isn't clipped,
        // while the true-black background still fills behind the bar.
        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        when {
            ui.importing != null -> ImportingCard(ui.importing!!, onCancel = vm::cancelImport)
            ui.loading -> Row(
                Modifier.fillMaxWidth().padding(top = 40.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            ui.active == null -> EmptyState(onImport = { pickPack.launch(null) })
            else -> LoadedPack(
                ui = ui,
                onImport = { pickPack.launch(null) },
                onSwitch = vm::switchTo,
                onExport = { exportTo.launch(vm.suggestedExportName()) },
                onImportProgress = { importFrom.launch(arrayOf("application/zip", "*/*")) },
                onStudy = onOpenFeed,
            )
        }

        ui.error?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Couldn't do that", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(6.dp))
                    // The importer's messages explain what to DO, not just what broke — show them whole.
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::dismissMessage) { Text("Dismiss") }
                }
            }
        }
        ui.message?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(msg, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(0.dp))
                TextButton(onClick = vm::dismissMessage) { Text("OK") }
            }
        }
    }
}

@Composable
private fun EmptyState(onImport: () -> Unit) {
    // A fresh install has no pack. That is a normal state, not an error — so it reads as an invitation,
    // not a failure.
    Column(
        Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("StudyFeed", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "No study pack yet.\n\nImport a pack folder to begin — the app takes its subjects, tags and " +
                "labels from whatever pack you give it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport) { Text("Import a pack") }
    }
}

@Composable
private fun ImportingCard(p: PackImporter.Progress, onCancel: () -> Unit) {
    // A multi-minute copy with no feedback is indistinguishable from a hang — hence a real percentage
    // and a real cancel. Cancelling leaves nothing behind (PackImporter's staging + atomic rename).
    Column(Modifier.fillMaxWidth().padding(top = 40.dp)) {
        Text(
            when (p.phase) {
                PackImporter.Phase.READING_MANIFEST -> "Reading the pack…"
                PackImporter.Phase.CHECKING_SPACE -> "Checking space…"
                PackImporter.Phase.COPYING_DATABASE -> "Copying the pack…"
                PackImporter.Phase.VERIFYING -> "Verifying the pack…"
                PackImporter.Phase.COPYING_FIGURES -> "Copying images…"
                PackImporter.Phase.FINALISING -> "Finishing…"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(p.fileLabel, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (p.bytesTotal > 0) {
            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text("${p.bytesCopied / 1_000_000} MB of ${p.bytesTotal / 1_000_000} MB",
                style = MaterialTheme.typography.labelSmall)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun LoadedPack(
    ui: PackUiState.Ui,
    onImport: () -> Unit,
    onSwitch: (String) -> Unit,
    onExport: () -> Unit,
    onImportProgress: () -> Unit,
    onStudy: () -> Unit,
) {
    val a = ui.active!!
    // THE app-bar label — read from pack_manifest.exam_name. The only reason this app knows any exam
    // exists is that a pack told it.
    Text(a.examName, style = MaterialTheme.typography.headlineSmall)
    Text("pack ${a.packVersion} · ${a.feedSize} items · ${a.seenCount} seen",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Spacer(Modifier.height(16.dp))
    // The primary action: go study. The feed IS the app; this screen is for managing packs.
    Button(onClick = onStudy, modifier = Modifier.fillMaxWidth()) { Text("Study ${a.feedSize} items") }

    Spacer(Modifier.height(20.dp))
    Section("Subjects (${a.subjects.size})")
    // Subject chips: built from the pack's own subjects table, in its own blueprint order.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        a.subjects.forEach { s -> AssistChip(onClick = {}, label = { Text(s.name) }) }
    }

    if (a.tags.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Section("Tags (${a.tags.size})")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            a.tags.forEach { t -> AssistChip(onClick = {}, label = { Text(t.label) }) }
        }
    }

    Spacer(Modifier.height(16.dp))
    Section("What this pack offers")
    // Capability list — resolved from the pack's ACTUAL ROWS, never hardcoded. A leaner pack simply
    // shows fewer lines here, with no code change (§8 A1 / PATCH 03).
    val caps = listOf(
        "Flashcards" to a.capabilities.hasFlashcards,
        "Reels" to a.capabilities.hasReels,
        "Passages" to a.capabilities.hasBlobs,
        "Link questions" to a.capabilities.hasInterlink,
        "Images" to a.capabilities.hasFigures,
        "Topic pages" to a.capabilities.hasDossiers,
        "Concept map" to a.capabilities.hasConceptMap,
    )
    caps.forEach { (label, present) ->
        Text(if (present) "· $label" else "· $label — not in this pack",
            style = MaterialTheme.typography.bodySmall,
            color = if (present) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // A pack whose own flags disagree with its rows is malformed — surfaced, never swallowed.
    if (a.capabilities.declarationMismatches.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        a.capabilities.declarationMismatches.forEach {
            Text("⚠ $it", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
        }
    }

    if (ui.installed.size > 1) {
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Section("Switch exam")
        ui.installed.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSwitch(p.examCode) }, enabled = p.examCode != a.examCode) {
                    Text(if (p.examCode == a.examCode) "${p.examName} (current)" else p.examName)
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Section("Progress")
    // §11e. This is the ONLY protection the user's revision history has: allowBackup=false means
    // Android will never copy it anywhere, deliberately (§0 — study data stays on the device).
    Text(
        "Your progress is stored only on this device and is never uploaded. Export it somewhere safe — " +
            "if you lose the phone or reinstall, an export is the only way to get it back.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onExport) { Text("Export") }
        OutlinedButton(onClick = onImportProgress) { Text("Restore") }
    }

    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onImport) { Text("Import another pack") }
    Spacer(Modifier.height(40.dp))
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
}
