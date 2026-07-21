package dev.kayomarz.studyfeed.ui.stats

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kayomarz.studyfeed.stats.CurvePoint
import dev.kayomarz.studyfeed.stats.SubjectCoverage
import dev.kayomarz.studyfeed.ui.feed.EmptyState
import dev.kayomarz.studyfeed.ui.theme.AppColor

/**
 * The Progress screen (BUILD_PROMPT §8 A6, rebuilt data-rich in A7 cycle 3): today · MCQ coverage +
 * accuracy by subject · activity heatmap · weekly accuracy + forgetting curve · due forecast · 5★/PYQ
 * core rings · high-importance gaps. All from the user's own data; no charting library.
 */
private val Red = Color(0xFFE06A6A)

@Composable
fun StatsScreen(vm: StatsViewModel, onReviewMistakes: () -> Unit, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Home") }
            Text("Progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            !ui.hasPack -> EmptyState("No pack yet", "Import a pack to see your progress.")
            // ABSENT: a lean pack with nothing scorable — distinct from "you just haven't studied yet".
            !ui.packHasContent -> EmptyState("Nothing to score",
                "This pack has no scorable items, so there are no stats to show.")
            // NOTHING_DUE: the pack has content, you simply haven't answered anything yet — encouraging.
            !ui.hasData -> EmptyState("No progress yet",
                "Answer some questions and your stats will appear here.")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                TodayCard(ui)
                Spacer(Modifier.height(20.dp))
                MistakesCard(ui.unresolvedMistakes, onReviewMistakes)

                Spacer(Modifier.height(20.dp))
                SectionTitle("Coverage & accuracy by subject")
                Muted("MCQs — the pack tags questions with a subject.")
                ui.subjects.forEach { CoverageRow(it) }

                Spacer(Modifier.height(22.dp))
                SectionTitle("Activity")
                Heatmap(ui.heat, ui.heatMax)

                Spacer(Modifier.height(22.dp))
                SectionTitle("MCQ accuracy — last 8 weeks")
                AccuracyTrend(ui.weekly)

                Spacer(Modifier.height(22.dp))
                SectionTitle("Forgetting curve")
                ForgettingCurve(ui.curve)

                Spacer(Modifier.height(22.dp))
                SectionTitle("Due — next 7 days")
                DueForecast(ui.forecast)

                Spacer(Modifier.height(22.dp))
                SectionTitle("High-yield core")
                CoreRings(ui.core.star5Done, ui.core.star5Total, ui.core.pyqDone, ui.core.pyqTotal)

                Spacer(Modifier.height(22.dp))
                SectionTitle("High-importance gaps (5★ you keep failing)")
                if (ui.gaps.isEmpty()) Muted("None — your 5★ items are on track.")
                ui.gaps.forEach { g ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("5★", color = AppColor.Review, fontSize = 12.sp)
                        Text(g.subjectName ?: g.itemType.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(g.itemType.uppercase(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable private fun Muted(msg: String) =
    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp))

@Composable private fun SectionTitle(t: String) =
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp))

@Composable
private fun TodayCard(ui: StatsViewModel.Ui) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat("🔥 Streak", "${ui.streakDays}", if (ui.streakDays == 1) "day" else "days", Modifier.weight(1f))
        Stat("Today", "${ui.reviewsToday}", "of ${ui.dailyGoal} goal", Modifier.weight(1f))
        Stat("MCQ acc", ui.mcqAccuracy?.let { "${(it * 100).toInt()}%" } ?: "—", "${ui.mcqAnswered} ans", Modifier.weight(1f))
        Stat("XP", "${ui.xp}", "earned", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
        .padding(vertical = 14.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(sub, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MistakesCard(count: Int, onReview: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Mistakes to review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (count == 0) "All caught up" else "$count item${if (count == 1) "" else "s"} in your pile",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (count > 0) TextButton(onClick = onReview) { Text("Review →") }
    }
}

@Composable
private fun CoverageRow(s: SubjectCoverage) {
    val accColor = s.accuracy?.let { when { it >= 0.7 -> AppColor.CorrectGreen; it >= 0.5 -> AppColor.Review; else -> Red } }
        ?: MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
            Text(buildString {
                append("${fmt(s.done)} / ${fmt(s.total)}")
                s.accuracy?.let { append("  ·  ${(it * 100).toInt()}%") }
            }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(s.coverage.coerceIn(0.0, 1.0).toFloat())
                .clip(RoundedCornerShape(4.dp)).background(accColor))
        }
    }
}

@Composable
private fun Heatmap(days: List<StatsViewModel.HeatDay>, max: Int) {
    if (days.isEmpty()) { Muted("No activity yet."); return }
    val weeks = days.chunked(7)
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    fun shade(n: Int): Color = when {
        n <= 0 -> base
        max <= 0 -> base
        n <= max * 0.25 -> AppColor.CorrectGreen.copy(alpha = 0.30f)
        n <= max * 0.5 -> AppColor.CorrectGreen.copy(alpha = 0.55f)
        n <= max * 0.75 -> AppColor.CorrectGreen.copy(alpha = 0.80f)
        else -> AppColor.CorrectGreen
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        weeks.forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { d ->
                    Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(shade(d.count)))
                }
            }
        }
    }
}

@Composable
private fun AccuracyTrend(weekly: List<Double?>) {
    if (weekly.count { it != null } < 2) { Muted("Answer questions across a few weeks to see the trend."); return }
    val line = AppColor.Mcq
    val grid = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp)) {
        val n = weekly.size
        fun x(i: Int) = if (n <= 1) 0f else i / (n - 1).toFloat() * size.width
        fun y(v: Double) = (1f - v.toFloat().coerceIn(0f, 1f)) * size.height
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 2f)
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        val pts = weekly.mapIndexedNotNull { i, v -> v?.let { i to it } }
        val path = Path()
        pts.forEachIndexed { idx, (i, v) -> if (idx == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, line, style = Stroke(width = 4f))
        pts.forEach { (i, v) -> drawCircle(line, 5f, Offset(x(i), y(v))) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("8 wk ago", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("now", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ForgettingCurve(points: List<CurvePoint>) {
    if (points.size < 2) { Muted("Not enough reviews yet to plot retention."); return }
    val line = AppColor.MapTeal
    val grid = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp)) {
        val maxDay = points.last().days.toFloat().coerceAtLeast(1f)
        fun px(p: CurvePoint) = Offset((p.days / maxDay) * size.width,
            (1f - p.retention.toFloat().coerceIn(0f, 1f)) * size.height)
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 2f)
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        val path = Path().apply {
            moveTo(px(points.first()).x, px(points.first()).y)
            points.drop(1).forEach { lineTo(px(it).x, px(it).y) }
        }
        drawPath(path, line, style = Stroke(width = 4f))
        points.forEach { drawCircle(line, 5f, px(it)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("now", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${points.last().days}d", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DueForecast(bars: List<StatsViewModel.DayBar>) {
    if (bars.isEmpty()) { Muted("Nothing scheduled."); return }
    val max = (bars.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        bars.forEach { b ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom) {
                Text(if (b.count > 0) "${b.count}" else "", fontSize = 8.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Box(Modifier.fillMaxWidth().height((72f * b.count / max).dp.coerceAtLeast(3.dp))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(if (b.label == "!") AppColor.PyqCoral else AppColor.Mcq))
                Spacer(Modifier.height(3.dp))
                Text(b.label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CoreRings(star5Done: Int, star5Total: Int, pyqDone: Int, pyqTotal: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Ring("5★ done", star5Done, star5Total, AppColor.Review, Modifier.weight(1f))
        Ring("PYQ done", pyqDone, pyqTotal, AppColor.PyqCoral, Modifier.weight(1f))
    }
}

@Composable
private fun Ring(label: String, done: Int, total: Int, color: Color, modifier: Modifier) {
    val frac = if (total > 0) done.toFloat() / total else 0f
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(64.dp)) {
                val sw = 8.dp.toPx()
                drawArc(track, 0f, 360f, false, style = Stroke(sw))
                drawArc(color, -90f, 360f * frac, false, style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            Text("${(frac * 100).toInt()}%", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("${fmt(done)} / ${fmt(total)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Thousands-separated, locale-free (deterministic). */
private fun fmt(n: Int): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val sb = StringBuilder()
    val firstGroup = s.length % 3
    if (firstGroup > 0) { sb.append(s, 0, firstGroup); sb.append(',') }
    var i = firstGroup
    while (i < s.length) { sb.append(s, i, i + 3); if (i + 3 < s.length) sb.append(','); i += 3 }
    return sb.toString()
}
