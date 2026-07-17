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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kayomarz.studyfeed.stats.CurvePoint
import dev.kayomarz.studyfeed.stats.SubjectStat

/**
 * The Progress screen (BUILD_PROMPT §8 A6): streak / daily goal / XP, the mistake pile (→ Review), per-
 * subject mastery bars, the forgetting curve, and high-importance gaps. All from the user's own data.
 * Functional layout; A7 owns polish (and may fold this into the home page, backlog 2.1).
 */
@Composable
fun StatsScreen(vm: StatsViewModel, onReviewMistakes: () -> Unit, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            !ui.hasPack -> Empty("Import a pack to see your progress.")
            !ui.hasData -> Empty("Answer some questions and your stats will appear here.")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                TodayCard(ui)
                Spacer(Modifier.height(20.dp))
                MistakesCard(ui.unresolvedMistakes, onReviewMistakes)
                Spacer(Modifier.height(20.dp))
                SectionTitle("Mastery by subject")
                ui.subjects.forEach { MasteryBar(it) }
                if (ui.subjects.isEmpty()) Muted("No subject data yet.")
                Spacer(Modifier.height(20.dp))
                SectionTitle("Forgetting curve")
                ForgettingCurve(ui.curve)
                Spacer(Modifier.height(20.dp))
                SectionTitle("High-importance gaps (5★ you keep failing)")
                if (ui.gaps.isEmpty()) Muted("None — your 5★ items are on track.")
                ui.gaps.forEach { g ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("5★", color = Color(0xFFFFB74D), fontSize = 12.sp)
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

@Composable private fun Empty(msg: String) =
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable private fun Muted(msg: String) =
    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp))

@Composable private fun SectionTitle(t: String) =
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp))

@Composable
private fun TodayCard(ui: StatsViewModel.Ui) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat("🔥 Streak", "${ui.streakDays}", if (ui.streakDays == 1) "day" else "days", Modifier.weight(1f))
        Stat("Today", "${ui.reviewsToday}", "of ${ui.dailyGoal} goal", Modifier.weight(1f))
        Stat("XP", "${ui.xp}", "${(ui.accuracy * 100).toInt()}% acc", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
        .padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun MasteryBar(s: SubjectStat) {
    val m = s.mastery.coerceIn(0.0, 1.0).toFloat()
    val c = when { m >= 0.6f -> Color(0xFF81C784); m >= 0.3f -> Color(0xFFFFB74D); else -> Color(0xFFE57373) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.name, style = MaterialTheme.typography.bodyMedium)
            Text("${(m * 100).toInt()}%  ·  ${(s.accuracy * 100).toInt()}% acc",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(m).clip(RoundedCornerShape(4.dp)).background(c))
        }
    }
}

@Composable
private fun ForgettingCurve(points: List<CurvePoint>) {
    if (points.size < 2) { Muted("Not enough reviews yet to plot retention."); return }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 8.dp)) {
        val maxDay = points.last().days.toFloat().coerceAtLeast(1f)
        fun px(p: CurvePoint) = Offset(
            x = (p.days / maxDay) * size.width,
            y = (1f - p.retention.toFloat().coerceIn(0f, 1f)) * size.height,
        )
        // baseline + 50% line
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 2f)
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        val path = Path().apply {
            moveTo(px(points.first()).x, px(points.first()).y)
            points.drop(1).forEach { lineTo(px(it).x, px(it).y) }
        }
        drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
        points.forEach { drawCircle(line, 5f, px(it)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("now", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${points.last().days}d", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
