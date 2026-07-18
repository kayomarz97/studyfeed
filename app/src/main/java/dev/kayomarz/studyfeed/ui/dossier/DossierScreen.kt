package dev.kayomarz.studyfeed.ui.dossier

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kayomarz.studyfeed.dossier.Dossier
import dev.kayomarz.studyfeed.dossier.DossierSection
import dev.kayomarz.studyfeed.dossier.RelatedItem
import dev.kayomarz.studyfeed.feed.FeedCard
import dev.kayomarz.studyfeed.ui.feed.FigureImage
import dev.kayomarz.studyfeed.ui.feed.ImportanceIndicator
import dev.kayomarz.studyfeed.ui.feed.Importance
import kotlinx.coroutines.launch

/**
 * A Topic Dossier (BUILD_PROMPT §8 A4): the pre-assembled `topic_pages.body_md` as ONE long scrollable
 * page — inline figures, collapsible per-subject `topic_sections`, jump-nav, and links to the entity's
 * MCQs/flashcards (opened in the in-place viewer). **Pure render, nothing generated.**
 *
 * Jump-nav uses [rememberLazyListState] `animateScrollToItem` against fixed item indices (header, opt.
 * jump-nav, body, then one lazy item per section — each section is ONE item so its index is stable
 * whether or not it's expanded).
 */
@Composable
fun DossierScreen(
    vm: DossierViewModel,
    onOpenItem: (type: String, refId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val ui by vm.dossier.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Library") }
            Text("Dossier", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxSize()) {
            val d = ui.dossier
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                d == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("This topic page could not be loaded.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> DossierBody(d, onOpenItem)
            }
        }
    }
}

@Composable
private fun DossierBody(d: Dossier, onOpenItem: (String, Long) -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hasSections = d.sections.isNotEmpty()
    val sectionBase = 1 + (if (hasSections) 1 else 0) + 1  // header + (jump-nav?) + body

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // (0) header
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(d.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportanceIndicator(d.importance, onClick = {})
                    if (d.subjectsCovered.isNotEmpty()) {
                        Text(d.subjectsCovered.joinToString(" · "), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // (1) jump-nav
        if (hasSections) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.sections.forEachIndexed { i, s ->
                        AssistChip(
                            onClick = { scope.launch { listState.animateScrollToItem(sectionBase + i) } },
                            label = { Text(s.heading.ifBlank { s.subjectName ?: "Section ${i + 1}" }) },
                        )
                    }
                }
            }
        }
        // (2) preamble (page subtitle etc.) + page-level figures. The per-subject content lives in the
        // collapsible sections below (topic_sections) — body_md's sections are the SAME text, so we do
        // NOT render body_md's section bodies here (that would double every fact).
        item {
            Column(Modifier.fillMaxWidth()) {
                if (d.bodyMd.isNotBlank()) DossierText(d.bodyMd, style = MaterialTheme.typography.bodyMedium)
                d.figures.forEach { fig ->
                    Spacer(Modifier.height(12.dp)); FigureImage(fig)
                }
                if (hasSections) { Spacer(Modifier.height(8.dp)); HorizontalDivider() }
            }
        }
        // (3+) one collapsible item per section
        items(d.sections.size) { i -> SectionItem(d.sections[i]) }

        // related links
        if (d.relatedMcqs.isNotEmpty()) {
            item { RelatedHeader("Related questions", d.relatedMcqs.size) }
            items(d.relatedMcqs.size) { i -> RelatedRow(d.relatedMcqs[i], onOpenItem) }
        }
        if (d.relatedFlashcards.isNotEmpty()) {
            item { RelatedHeader("Related flashcards", d.relatedFlashcards.size) }
            items(d.relatedFlashcards.size) { i -> RelatedRow(d.relatedFlashcards[i], onOpenItem) }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun SectionItem(s: DossierSection) {
    var expanded by remember(s) { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(s.heading.ifBlank { s.subjectName ?: "Section" },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (s.subjectName != null && s.heading.isNotBlank()) {
                    Text(s.subjectName.uppercase(), fontSize = 10.sp, letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                if (expanded) "▾" else "▸",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (expanded) {
            DossierText(s.bodyMd, style = MaterialTheme.typography.bodyMedium)
            s.figures.forEach { fig -> Spacer(Modifier.height(10.dp)); FigureImage(fig) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun RelatedHeader(title: String, count: Int) {
    Text("$title ($count)", style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun RelatedRow(item: RelatedItem, onOpenItem: (String, Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpenItem(item.type, item.refId) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.border(1.dp, Importance.color(item.importance), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) { Text("${item.importance}★", color = Importance.color(item.importance), fontSize = 11.sp) }
        Text(
            (item.subjectName ?: item.type.replaceFirstChar { it.uppercase() }) +
                " · open ${if (item.type == "mcq") "question" else "flashcard"}",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
        )
    }
}
