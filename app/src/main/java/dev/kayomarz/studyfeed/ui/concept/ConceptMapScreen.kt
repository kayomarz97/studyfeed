package dev.kayomarz.studyfeed.ui.concept

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kayomarz.studyfeed.concept.ConceptRef
import dev.kayomarz.studyfeed.concept.EgoGraph
import dev.kayomarz.studyfeed.concept.ForceLayout
import dev.kayomarz.studyfeed.ui.feed.Importance
import kotlin.math.min

/**
 * The Concept Map (BUILD_PROMPT §8 A5): a walkable ego-graph on a pan/zoom Compose Canvas. Node size
 * scales with centrality, colour with importance; **tap a neighbour to walk to it**, tap the centre for
 * its typed edges + dossier + "Quiz me on these links". Ego-scoped (never the full 37,795-node graph).
 *
 * Visual layout is functional, not final (A7 owns polish).
 */
@Composable
fun ConceptMapScreen(
    vm: ConceptMapViewModel,
    onOpenDossier: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val quiz by vm.quiz.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Concept map", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { showSearch = !showSearch }) { Text(if (showSearch) "Close" else "Find") }
        }
        if (showSearch) SearchPanel(ui.query, ui.searchResults, vm::search, onPick = { id ->
            showSearch = false; vm.focusOn(id)
        })

        Box(Modifier.fillMaxSize()) {
            when {
                ui.loading && ui.ego == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                !ui.hasPack -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Import a pack to explore its concept map.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ui.ego == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No concept graph in this pack.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> GraphCanvas(
                    ego = ui.ego!!, positions = ui.positions,
                    onTapNeighbour = { vm.focusOn(it) },
                    onTapFocus = { showInfo = true },
                )
            }
            Text("Tap a node to walk · tap the centre for details",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }

    if (showInfo) ui.ego?.let { ConceptInfoSheet(it, onDismiss = { showInfo = false },
        onWalk = { showInfo = false; vm.focusOn(it) },
        onOpenDossier = { showInfo = false; onOpenDossier(it) },
        onQuiz = { showInfo = false; vm.openQuiz() }) }

    if (quiz.open) ConceptQuizViewer(quiz, onClose = vm::closeQuiz)
}

@Composable
private fun GraphCanvas(
    ego: EgoGraph,
    positions: List<ForceLayout.Pt>,
    onTapNeighbour: (Long) -> Unit,
    onTapFocus: () -> Unit,
) {
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // Reset the view when the focus changes (walking to a new concept).
    val focusId = ego.focus.id
    LaunchedEffect(focusId) { scale = 1f; pan = Offset.Zero }

    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface
    val edgeColor = MaterialTheme.colorScheme.outline
    val relColor = MaterialTheme.colorScheme.onSurfaceVariant

    val posRef = rememberUpdatedState(positions)
    val egoRef = rememberUpdatedState(ego)
    val scaleRef = rememberUpdatedState(scale)
    val panRef = rememberUpdatedState(pan)
    var canvasSize by remember { mutableStateOf(Offset.Zero) }

    fun nodeCenter(i: Int, w: Float, h: Float): Offset {
        val p = posRef.value.getOrNull(i) ?: return Offset(w / 2, h / 2)
        val base = min(w, h) * 0.40f // A7: more spread so labels near the centre stop colliding
        return Offset(w / 2 + p.x * base * scaleRef.value + panRef.value.x,
            h / 2 + p.y * base * scaleRef.value + panRef.value.y)
    }
    fun radius(importanceCentrality: Double, focus: Boolean): Float =
        ((if (focus) 34f else 22f) + importanceCentrality.toFloat() * 16f) * scaleRef.value.coerceIn(0.6f, 1.8f)

    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { canvasSize = Offset(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.4f, 4f); pan += panChange
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { t ->
                    val w = canvasSize.x; val h = canvasSize.y
                    val e = egoRef.value
                    // Neighbours first (index 1..n), then focus (0).
                    for (i in e.neighbours.indices) {
                        val c = nodeCenter(i + 1, w, h)
                        val r = radius(e.neighbours[i].concept.centrality, false).coerceAtLeast(44f)
                        if ((t - c).getDistance() <= r) { onTapNeighbour(e.neighbours[i].concept.id); return@detectTapGestures }
                    }
                    val fc = nodeCenter(0, w, h)
                    if ((t - fc).getDistance() <= radius(e.focus.centrality, true).coerceAtLeast(48f)) onTapFocus()
                }
            }
    ) {
        val w = size.width; val h = size.height
        val fc = nodeCenter(0, w, h)

        // edges (focus → each neighbour) + relation labels
        ego.neighbours.forEachIndexed { i, nb ->
            val nc = nodeCenter(i + 1, w, h)
            drawLine(edgeColor, fc, nc, strokeWidth = 2f)
            val mid = Offset((fc.x + nc.x) / 2, (fc.y + nc.y) / 2)
            val rel = measurer.measure(nb.relation.replace('_', ' '),
                style = TextStyle(fontSize = 9.sp, color = relColor))
            drawText(rel, topLeft = Offset(mid.x - rel.size.width / 2, mid.y - rel.size.height / 2))
        }
        // neighbour nodes + labels. Labels are pushed radially OUTWARD from the focus (A7 de-collision),
        // so they fan out around the ring instead of stacking below crowded central nodes.
        ego.neighbours.forEachIndexed { i, nb ->
            val nc = nodeCenter(i + 1, w, h)
            val r = radius(nb.concept.centrality, false)
            drawCircle(Importance.color(nb.concept.importance), r, nc)
            drawNodeLabel(measurer, nb.concept.name, labelAnchor(nc, fc, r), labelColor)
        }
        // focus node (on top) — its label sits just below it.
        val fr = radius(ego.focus.centrality, true)
        drawCircle(Importance.color(ego.focus.importance), fr, fc)
        drawCircle(Color.White.copy(alpha = 0.9f), fr, fc, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        drawNodeLabel(measurer, ego.focus.name, Offset(fc.x, fc.y + fr + 12f), labelColor, bold = true)
    }
}

/** Where a neighbour's label should be centred: outward from the focus, clear of the node circle. */
private fun labelAnchor(node: Offset, focus: Offset, r: Float): Offset {
    val dx = node.x - focus.x; val dy = node.y - focus.y
    val d = kotlin.math.sqrt(dx * dx + dy * dy)
    if (d < 1f) return Offset(node.x, node.y + r + 12f) // degenerate: fall back to "below"
    val ux = dx / d; val uy = dy / d
    return Offset(node.x + ux * (r + 12f), node.y + uy * (r + 12f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNodeLabel(
    measurer: androidx.compose.ui.text.TextMeasurer, name: String, anchor: Offset, color: Color, bold: Boolean = false,
) {
    val label = if (name.length > 22) name.take(20) + "…" else name
    val m = measurer.measure(label, style = TextStyle(fontSize = 11.sp, color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    // Anchor is the label's CENTRE point.
    drawText(m, topLeft = Offset(anchor.x - m.size.width / 2, anchor.y - m.size.height / 2))
}

@Composable
private fun SearchPanel(query: String, results: List<ConceptRef>, onQuery: (String) -> Unit, onPick: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OutlinedTextField(value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text("Find a concept…") }, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxWidth().height(220.dp)) {
            items(results, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clickable { onPick(c.id) }.padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.height(10.dp).padding(0.dp)) {}
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${c.importance}★", color = Importance.color(c.importance), fontSize = 12.sp)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ConceptInfoSheet(
    ego: EgoGraph, onDismiss: () -> Unit, onWalk: (Long) -> Unit,
    onOpenDossier: (Long) -> Unit, onQuiz: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ego.focus.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text("${ego.focus.importance}★", color = Importance.color(ego.focus.importance), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            ego.focus.dossierPageId?.let { pid ->
                TextButton(onClick = { onOpenDossier(pid) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Open dossier →")
                }
            }
            TextButton(onClick = onQuiz, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Quiz me on these links →")
            }
            Spacer(Modifier.height(8.dp))
            Text("Connections", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxWidth().height(280.dp)) {
                items(ego.neighbours, key = { it.concept.id }) { nb ->
                    Row(Modifier.fillMaxWidth().clickable { onWalk(nb.concept.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (nb.outgoing) nb.relation.replace('_', ' ') else "${nb.relation.replace('_', ' ')} (of)",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
                        Text(nb.concept.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
                        Text("${nb.concept.importance}★", color = Importance.color(nb.concept.importance), fontSize = 11.sp)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                }
                if (ego.moreCount > 0) item {
                    Text("+${ego.moreCount} more connections", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
