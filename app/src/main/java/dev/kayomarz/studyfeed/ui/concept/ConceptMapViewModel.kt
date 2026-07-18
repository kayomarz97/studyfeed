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
        /** A7: the map opens as a searchable LIST; picking a concept switches to its ego-graph. */
        val browseMode: Boolean = true,
        val filter: ConceptRepository.ConceptFilter = ConceptRepository.ConceptFilter(),
        val subjects: List<String> = emptyList(),
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

    private fun start() = viewModelScope.launch { ensureLoaded() }

    /** Re-check the pack when entering the map — this VM may have been created before an import. */
    fun refresh() = viewModelScope.launch { ensureLoaded() }

    /** Open the concept reader on demand. A no-op once [repo] is set — self-heals a map VM created
     *  before the pack was imported (fresh install → import), mirroring the feed's loadPack(). */
    private suspend fun ensureLoaded(): ConceptRepository? {
        repo?.let { return it }
        val pack = withContext(Dispatchers.IO) { registry.activePack() }
            ?: run { _state.update { it.copy(loading = false, hasPack = false) }; return null }
        val (r, subjects, top) = withContext(Dispatchers.IO) {
            val opened = ConceptRepository.open(pack.dbFile)
            Triple(opened, opened?.subjects().orEmpty(), opened?.list("") ?: emptyList())
        }
        repo = r
        if (r == null) { _state.update { it.copy(loading = false, hasPack = false) }; return null }
        // A7: open as a searchable list, not straight onto the top hub.
        _state.update { it.copy(loading = false, searchResults = top, browseMode = true, subjects = subjects) }
        return r
    }

    /** Return to the searchable concept list (the Map's landing view). */
    fun browse() = _state.update { it.copy(browseMode = true) }

    /** Apply the A7 concept-list filters (importance / subject / PYQ). */
    fun setConceptFilter(filter: ConceptRepository.ConceptFilter) {
        _state.update { it.copy(filter = filter) }
        reloadList()
    }

    /** Load [conceptId]'s ego-graph and lay it out. Picking from the list, or "walking" a neighbour. */
    fun focusOn(conceptId: Long) = viewModelScope.launch {
        val r = repo ?: return@launch
        _state.update { it.copy(loading = true, browseMode = false) }
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
        reloadList()
    }

    private fun reloadList() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(180)
            val r = ensureLoaded() ?: return@launch
            val s = _state.value
            val results = withContext(Dispatchers.IO) { r.list(s.query, s.filter) }
            _state.update { it.copy(searchResults = results) }
        }
    }

    /** "Quiz me on these links" — load the interlink quizzes for the focus concept's edges. */
    fun openQuiz() {
        val ego = _state.value.ego ?: return
        _quiz.value = QuizState(loading = true, open = true, conceptName = ego.focus.name)
        viewModelScope.launch {
            val r = ensureLoaded() ?: return@launch
            val cards = withContext(Dispatchers.IO) {
                r.quizRefsForConcept(ego.focus.id).mapNotNull { r.loadInterlink(it) }
            }
            _quiz.update { it.copy(loading = false, cards = cards) }
        }
    }

    fun closeQuiz() { _quiz.value = QuizState() }

    override fun onCleared() { repo?.close(); super.onCleared() }
}
