package dev.kayomarz.studyfeed.ui.exam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kayomarz.studyfeed.ui.feed.MarkdownText
import dev.kayomarz.studyfeed.ui.theme.AppColor
import kotlinx.coroutines.delay

/**
 * The **mock exam** (A7 cycle 3, P4): CONFIG → RUNNING (a pager of questions, no reveal, a running clock)
 * → DONE (a score report: overall %, pass/fail vs 60%, and a per-subject breakdown). Answers are written
 * to progress on submit, so a mock counts toward the feed + your stats.
 */
@Composable
fun MockExamScreen(vm: MockExamViewModel, onExit: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    when {
        ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        !ui.hasPack -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Import a pack first.") }
        ui.phase == MockExamViewModel.Phase.CONFIG -> ConfigPhase(onStart = vm::start, onExit = onExit)
        ui.phase == MockExamViewModel.Phase.RUNNING -> RunningPhase(ui, vm::answer, vm::submit)
        else -> ReportPhase(ui.report!!, onNew = vm::reset, onExit = onExit)
    }
}

@Composable
private fun ConfigPhase(onStart: (Int) -> Unit, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth()) { TextButton(onClick = onExit) { Text("‹ Home") } }
        Spacer(Modifier.weight(1f))
        Text("Mock exam", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("A timed block of questions, weighted across subjects like the real paper. No answers shown " +
            "until you submit — then you get a score and a per-subject breakdown. It counts toward your progress.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Text("HOW MANY QUESTIONS?", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(20, 50, 100).forEach { n ->
                OutlinedButton(onClick = { onStart(n) }, modifier = Modifier.weight(1f)) { Text("$n") }
            }
        }
        Spacer(Modifier.weight(2f))
    }
}

@Composable
private fun RunningPhase(
    ui: MockExamViewModel.Ui,
    onAnswer: (Int, Int) -> Unit,
    onSubmit: () -> Unit,
) {
    val pager = rememberPagerState(pageCount = { ui.questions.size })
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1000); elapsed += 1 } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Q ${pager.currentPage + 1} / ${ui.questions.size}", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(elapsed), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(12.dp))
            Button(onClick = onSubmit) { Text("Submit") }
        }
        Text("${ui.answers.size} answered", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
        VerticalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
            val q = ui.questions[page]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                q.subjectName?.let {
                    Text(it.uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                }
                MarkdownText(q.stemMd, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(20.dp))
                q.options.forEachIndexed { idx, opt ->
                    OptionRow(('A' + idx), opt, selected = ui.answers[page] == idx, onClick = { onAnswer(page, idx) })
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun OptionRow(letter: Char, text: String, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) AppColor.Mcq else MaterialTheme.colorScheme.outline
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) AppColor.Mcq.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$letter", fontWeight = FontWeight.Bold, color = if (selected) AppColor.Mcq else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ReportPhase(report: MockExamViewModel.Report, onNew: () -> Unit, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth()) { TextButton(onClick = onExit) { Text("‹ Home") } }
        Spacer(Modifier.height(20.dp))
        Text("${report.pct}%", fontSize = 64.sp, fontWeight = FontWeight.Bold,
            color = if (report.passed) AppColor.CorrectGreen else AppColor.PyqCoral)
        Text(if (report.passed) "PASS" else "BELOW ${report.passMark}%", fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp, color = if (report.passed) AppColor.CorrectGreen else AppColor.PyqCoral)
        Spacer(Modifier.height(6.dp))
        Text("${report.correct} of ${report.total} correct · ${fmtTime(report.elapsedSec)}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(28.dp))
        Text("BY SUBJECT", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        report.bySubject.forEach { s ->
            val frac = if (s.total > 0) s.correct.toFloat() / s.total else 0f
            val c = when { frac >= 0.7f -> AppColor.CorrectGreen; frac >= 0.5f -> AppColor.Review; else -> AppColor.PyqCoral }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    Text("${s.correct}/${s.total}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(4.dp)).background(c))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onNew, modifier = Modifier.weight(1f)) { Text("New exam") }
            Button(onClick = onExit, modifier = Modifier.weight(1f)) { Text("Done") }
        }
        Spacer(Modifier.height(40.dp))
    }
}

private fun fmtTime(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)
