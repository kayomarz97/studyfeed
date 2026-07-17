package dev.kayomarz.studyfeed.ui.concept

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kayomarz.studyfeed.concept.ConceptRef
import dev.kayomarz.studyfeed.concept.ConceptRepository
import dev.kayomarz.studyfeed.concept.EgoGraph
import dev.kayomarz.studyfeed.concept.ForceLayout
import dev.kayomarz.studyfeed.feed.FeedCard
import dev.kayomarz.studyfeed.pack.PackRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the Concept Map (BUILD_PROMPT §8 A5) — the walkable ego-graph explorer. Loads a focus concept's
 * neighbourhood + its force-layout off the main thread; tapping a neighbour re-centres on it.
 */
class ConceptMapViewModel(app: Application) : AndroidViewModel(app) {

    data class MapState(
        val loading: Boolean = true,
        val hasPack: Boolean = true,
        val ego: EgoGraph? = null,
        /** index 0 = focus, 1..n = neighbours (same order as [EgoGraph.neighbours]). Virtual coords. */
        val positions: List<ForceLayout.Pt> = emptyList(),
        val query: String = "",
        val searchResults: List<ConceptRef> = emptyList(),
    )

    data class QuizState(val loading: Boolean = false, val open: Boolean = false,
                         val conceptName: String? = null, val cards: List<FeedCard.Interlink> = emptyList())

    private val registry = PackRegistry(app)
    private var repo: ConceptRepository? = null
    private var searchJob: Job? = null

    private val _state = MutableStateFlow(MapState())
    val state: StateFlow<MapState> = _state.asStateFlow()

    private val _quiz = MutableStateFlow(QuizState())
    val quiz: StateFlow<QuizState> = _quiz.asStateFlow()

    init { start() }

    private fun start() = viewModelScope.launch {
        val pack = withContext(Dispatchers.IO) { registry.activePack() }
        if (pack == null) { _state.update { it.copy(loading = false, hasPack = false) }; return@launch }
        val (r, top) = withContext(Dispatchers.IO) {
            val opened = ConceptRepository.open(pack.dbFile)
            opened to (opened?.topConcepts(40) ?: emptyList())
        }
        repo = r
        if (r == null || top.isEmpty()) { _state.update { it.copy(loading = false, hasPack = r != null) }; return@launch }
        _state.update { it.copy(searchResults = top) }
        focusOn(top.first().id)   // land on the most central hub
    }

    /** Load [conceptId]'s ego-graph and lay it out. This is the "walk" — tapping a neighbour calls it. */
    fun focusOn(conceptId: Long) = viewModelScope.launch {
        val r = repo ?: return@launch
        _state.update { it.copy(loading = true) }
        val (ego, positions) = withContext(Dispatchers.IO) {
            val e = r.ego(conceptId, k = 12)
            val pos = if (e == null) emptyList() else {
                val n = 1 + e.neighbours.size
                // Ego graph is a star: focus (0) connected to each neighbour (1..n).
                ForceLayout.layout(n, e.neighbours.indices.map { 0 to it + 1 })
            }
            e to pos
        }
        _state.update { it.copy(loading = false, ego = ego, positions = positions) }
    }

    fun search(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(180)
            val r = repo ?: return@launch
            val results = withContext(Dispatchers.IO) { r.search(q) }
            _state.update { it.copy(searchResults = results) }
        }
    }

    /** "Quiz me on these links" — load the interlink quizzes for the focus concept's edges. */
    fun openQuiz() {
        val ego = _state.value.ego ?: return
        _quiz.value = QuizState(loading = true, open = true, conceptName = ego.focus.name)
        viewModelScope.launch {
            val r = repo ?: return@launch
            val cards = withContext(Dispatchers.IO) {
                r.quizRefsForConcept(ego.focus.id).mapNotNull { r.loadInterlink(it) }
            }
            _quiz.update { it.copy(loading = false, cards = cards) }
        }
    }

    fun closeQuiz() { _quiz.value = QuizState() }

    override fun onCleared() { repo?.close(); super.onCleared() }
}
