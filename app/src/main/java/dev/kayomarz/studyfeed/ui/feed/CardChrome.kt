package dev.kayomarz.studyfeed.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kayomarz.studyfeed.feed.FeedCard

/**
 * The chrome every feed card shares (BUILD_PROMPT §8 A2; **A7 redesign 2026-07-18**): the 1–5 importance
 * indicator, the **loud PYQ badge**, the quiet subject footer, and the "key disputed" flag.
 *
 * **Importance is encoded in COLOUR, and that is deliberate and load-bearing** (§8 A5: "colour with
 * importance"; §0 dynamic-colour is off precisely so nothing else repaints these). The ramp runs cool→hot
 * so a 5★ item is unmistakable at a glance while scrolling. Defined once here so the feed, the dossier
 * reader (A4) and the concept map (A5) all speak the same colour language.
 *
 * **A7 (user choice "Loud PYQ badge"):** PYQ is the single strongest yield signal in the app, so it gets
 * a filled, prominent badge at the TOP with the importance stars; the **subject** moves out of the header
 * to a quiet label at the BOTTOM of the card ([SubjectFooter]) — colour can't encode subject because it
 * already encodes importance.
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
 * The **loud PYQ badge** (A7) — a filled pill "PYQ · 2019 · 2023". Shown only for a past-paper item with
 * known years, so its mere presence shouts "this has come up before", the strongest yield cue in the app.
 * Filled (not outlined like the old A2 badge) so it reads first on the card.
 */
@Composable
fun PyqBadge(years: List<Int>, modifier: Modifier = Modifier) {
    if (years.isEmpty()) return
    val label = "PYQ · " + years.sorted().joinToString(" · ") { it.toString() }
    Text(
        text = label,
        color = Color(0xFF180A07), // near-black on coral
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(dev.kayomarz.studyfeed.ui.theme.AppColor.PyqCoral)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * The card's TOP signal row (A7): the loud PYQ badge (if any) + the importance stars. The star toggle is
 * added by the card scaffold on the right; the subject is rendered at the bottom ([SubjectFooter]).
 */
@Composable
fun CardTopRow(card: FeedCard, onImportanceClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PyqBadge(card.pyqYears)
        ImportanceIndicator(card.importance, onImportanceClick)
    }
}

/**
 * The one reusable empty-state for every section (2026-07-21). Centered [title] + muted [body] — used
 * for both honest cases: **ABSENT** ("this pack doesn't include X") and **NOTHING_DUE / NOTHING_MATCHES**
 * ("all caught up" / "clear a filter"). Which copy to pass is [EmptyStates.decide]'s job — this only
 * renders. A section a lean pack can't fill shows this, never a blank screen.
 */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** The quiet subject label pinned at the BOTTOM of a card (A7). Non-colour-coded — colour = importance. */
@Composable
fun SubjectFooter(card: FeedCard, modifier: Modifier = Modifier) {
    val subject = card.subjectName ?: return
    Text(
        subject.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}
