package dev.kayomarz.studyfeed.ui.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kayomarz.studyfeed.feed.FeedCard

/**
 * The chrome every feed card shares (BUILD_PROMPT §8 A2): the 1–5 importance indicator, the PYQ badge,
 * the subject label, and the "key disputed" flag.
 *
 * **Importance is encoded in COLOUR, and that is deliberate and load-bearing** (§8 A5: "colour with
 * importance"; §0 dynamic-colour is off precisely so nothing else repaints these). The ramp runs cool→hot
 * so a 5★ item is unmistakable at a glance while scrolling. Defined once here so the feed, the dossier
 * reader (A4) and the concept map (A5) all speak the same colour language.
 */
object Importance {
    /** 1★…5★ → a fixed colour. Muted at the low end, saturated warm at the high end. */
    fun color(importance: Int): Color = when (importance.coerceIn(1, 5)) {
        5 -> Color(0xFFFFB74D) // amber — highest yield, the "do not skip" band
        4 -> Color(0xFF81C784) // green
        3 -> Color(0xFF64B5F6) // blue
        2 -> Color(0xFF90A4AE) // blue-grey
        else -> Color(0xFF616161) // grey — low yield
    }
}

/**
 * A compact 1–5 pip + star. Tap opens the "why" sheet (§8 A2 acceptance 3). Small on purpose: it rides
 * on every card and must never dominate the content.
 */
@Composable
fun ImportanceIndicator(importance: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Importance.color(importance)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text("$importance★", color = c, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

/**
 * The PYQ badge — "Asked 2019 · 2023" (§8 A2). Shown only for a past-paper item with known years, so its
 * mere presence signals "this has come up before", the single strongest yield cue in the app.
 */
@Composable
fun PyqBadge(years: List<Int>, modifier: Modifier = Modifier) {
    if (years.isEmpty()) return
    val label = "Asked " + years.sorted().joinToString(" · ") { it.toString() }
    Text(
        text = label,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The header row shared by every card: subject · importance · PYQ · (optional) key-disputed. */
@Composable
fun CardHeader(card: FeedCard, onImportanceClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        card.subjectName?.let {
            Text(it.uppercase(), fontSize = 11.sp, letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(0.dp))
        ImportanceIndicator(card.importance, onImportanceClick)
        PyqBadge(card.pyqYears)
    }
}
