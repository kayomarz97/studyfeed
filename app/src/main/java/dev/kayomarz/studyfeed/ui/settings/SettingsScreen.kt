package dev.kayomarz.studyfeed.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kayomarz.studyfeed.ui.AUTHOR_CREDIT
import dev.kayomarz.studyfeed.ui.AUTHOR_URL
import dev.kayomarz.studyfeed.ui.PackUiState
import dev.kayomarz.studyfeed.ui.openUrl
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The A7 Settings screen (BUILD_PROMPT §8 A7). Tunes the feed engine — the editable **deadline**, the
 * **re-drill N**, the **feed intensity** — and surfaces the §11e **Export / Restore progress** entry
 * points (import/export logic lives in [PackUiState], already built at A1; A7 only surfaces it here).
 *
 * @param onChanged called when any engine knob changed, so the caller can `reapplySettings()` on the feed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    packVm: PackUiState = viewModel(),
    onBack: () -> Unit,
    onManagePacks: () -> Unit,
    onChanged: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }

    val exportTo = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { packVm.exportProgress(it) } }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { packVm.importProgress(it) }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Row(Modifier.fillMaxWidth()) { TextButton(onClick = onBack) { Text("‹ Home") } }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))

        // ── Deadline ──────────────────────────────────────────────────────────────────────────────
        Text("Finish deadline", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text("New MCQs are paced so you get through every one by this date. Move it any time.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showDatePicker = true }) {
            Text(ui.deadline?.toString() ?: "Set a date")
        }

        Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(20.dp))

        // ── Re-drill N ────────────────────────────────────────────────────────────────────────────
        Text("Mistakes: correct streak to clear", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text("How many times in a row you must answer a missed question correctly before it leaves your " +
            "mistakes pile.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3, 5).forEach { n ->
                FilterChip(selected = ui.redrillN == n, onClick = { vm.setRedrillN(n); onChanged() },
                    label = { Text("$n×") })
            }
        }

        Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(20.dp))

        // ── Feed intensity ────────────────────────────────────────────────────────────────────────
        Text("Feed intensity", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text("How hard the feed pushes new material and how big an overdue backlog it serves per session.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsViewModel.Intensity.entries.forEach { i ->
                FilterChip(selected = ui.intensity == i, onClick = { vm.setIntensity(i); onChanged() },
                    label = { Text(i.label) })
            }
        }

        Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(20.dp))

        // ── After rating a card ─────────────────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Advance after rating", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("On: rating a card moves to the next one. Off: it just highlights your rating and stays.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ui.advanceAfterRating,
                onCheckedChange = { vm.setAdvanceAfterRating(it); onChanged() })
        }

        Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(20.dp))

        // ── Progress backup (§11e) ──────────────────────────────────────────────────────────────────
        Text("Your progress", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text("Stored only on this device and never uploaded. Export it somewhere safe — if you lose the " +
            "phone or reinstall, an export is the only way back.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { exportTo.launch(packVm.suggestedExportName()) }) { Text("Export") }
            OutlinedButton(onClick = { importFrom.launch(arrayOf("application/zip", "*/*")) }) { Text("Restore") }
        }

        Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(20.dp))

        // ── Display ─────────────────────────────────────────────────────────────────────────────────
        Text("Display", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text("Pure-black AMOLED theme is always on (it saves battery on OLED screens); card colour marks " +
            "how high-yield an item is.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onManagePacks, modifier = Modifier.fillMaxWidth()) { Text("Manage packs") }

        packVm.ui.collectAsStateWithLifecycle().value.let { p ->
            p.message?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
            p.error?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error) }
        }

        // ── Credit (user request 2026-07-18) — tappable → kayomarz.com via the system browser. Opening a
        // URL hands off to a separate app; StudyFeed keeps zero network capability (the guard stays green).
        Spacer(Modifier.height(28.dp))
        val context = LocalContext.current
        Text(
            AUTHOR_CREDIT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { openUrl(context, AUTHOR_URL) }.padding(vertical = 8.dp),
        )
        Spacer(Modifier.height(40.dp))
    }

    if (showDatePicker) {
        val initMillis = ui.deadline?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        vm.setDeadline(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                        onChanged()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}
