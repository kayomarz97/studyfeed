package dev.kayomarz.studyfeed.ui.dossier

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kayomarz.studyfeed.dossier.Dossier
import dev.kayomarz.studyfeed.dossier.DossierListItem
import dev.kayomarz.studyfeed.dossier.DossierRepository
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
 * Drives the Topic Dossier reader (BUILD_PROMPT §8 A4). Loads the Library search results, a full
 * dossier, and the in-place related-item viewer — all pure pack reads off the main thread. One
 * [DossierRepository] (pack-only, read-only) for the session.
 */
class DossierViewModel(app: Application) : AndroidViewModel(app) {

    data class LibraryState(
        val loading: Boolean = true,
        val hasPack: Boolean = true,
        val query: String = "",
        val results: List<DossierListItem> = emptyList(),
        val filter: DossierRepository.DossierFilter = DossierRepository.DossierFilter(),
        val subjects: List<String> = emptyList(),
    )

    data class DossierState(val loading: Boolean = true, val dossier: Dossier? = null)
    data class ItemState(val loading: Boolean = true, val card: FeedCard? = null)

    private val registry = PackRegistry(app)
    private var repo: DossierRepository? = null

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _dossier = MutableStateFlow(DossierState())
    val dossier: StateFlow<DossierState> = _dossier.asStateFlow()

    private val _item = MutableStateFlow(ItemState())
    val item: StateFlow<ItemState> = _item.asStateFlow()

    private var searchJob: Job? = null

    init { start() }

    private fun start() = viewModelScope.launch {
        val pack = withContext(Dispatchers.IO) { registry.activePack() }
        if (pack == null) { _library.update { it.copy(loading = false, hasPack = false) }; return@launch }
        val (r, subjects, top) = withContext(Dispatchers.IO) {
            val opened = DossierRepository.open(pack.dbFile)
            Triple(opened, opened?.subjects().orEmpty(), opened?.list("") ?: emptyList())
        }
        repo = r
        _library.update { it.copy(loading = false, hasPack = r != null, results = top, subjects = subjects) }
    }

    /** Search on each keystroke, lightly debounced; empty query falls back to the top dossiers. */
    fun search(query: String) {
        _library.update { it.copy(query = query, loading = true) }
        reloadList()
    }

    /** Apply the A7 Library filters (importance / subject / PYQ). */
    fun setLibraryFilter(filter: DossierRepository.DossierFilter) {
        _library.update { it.copy(filter = filter, loading = true) }
        reloadList()
    }

    private fun reloadList() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(180) // debounce: coalesce fast typing before hitting SQLite
            val r = repo ?: return@launch
            val s = _library.value
            val results = withContext(Dispatchers.IO) { r.list(s.query, s.filter) }
            _library.update { it.copy(loading = false, results = results) }
        }
    }

    fun openDossier(topicPageId: Long) {
        _dossier.value = DossierState(loading = true, dossier = null)
        viewModelScope.launch {
            val r = repo ?: return@launch
            val d = withContext(Dispatchers.IO) { r.loadDossier(topicPageId) }
            _dossier.value = DossierState(loading = false, dossier = d)
        }
    }

    fun openItem(type: String, refId: Long) {
        _item.value = ItemState(loading = true, card = null)
        viewModelScope.launch {
            val r = repo ?: return@launch
            val card = withContext(Dispatchers.IO) { r.loadCardByRef(type, refId) }
            _item.value = ItemState(loading = false, card = card)
        }
    }

    override fun onCleared() {
        repo?.close()
        super.onCleared()
    }
}
