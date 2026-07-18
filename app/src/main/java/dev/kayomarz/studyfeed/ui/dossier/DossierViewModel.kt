package dev.kayomarz.studyfeed.ui.dossier

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kayomarz.studyfeed.dossier.Dossier
import dev.kayomarz.studyfeed.dossier.DossierListItem
import dev.kayomarz.studyfeed.dossier.DossierRepository
import dev.kayomarz.studyfeed.dossier.LineText
import dev.kayomarz.studyfeed.feed.FeedCard
import dev.kayomarz.studyfeed.pack.PackRegistry
import dev.kayomarz.studyfeed.progress.LineMark
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import dev.kayomarz.studyfeed.progress.Star
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Drives the Topic Dossier reader (BUILD_PROMPT §8 A4; A7 cycle 3 stars). Loads the Library search
 * results, a full dossier, and the in-place related-item viewer. A7 cycle 3 also opens the **progress**
 * DB (read-write) for the topic ★ and per-line stars, and computes the approximate PYQ/5★ line
 * highlighting on load. The pack read stays via one [DossierRepository]; the progress DB is separate.
 */
class DossierViewModel(app: Application) : AndroidViewModel(app) {

    data class LibraryState(
        val loading: Boolean = true,
        val hasPack: Boolean = true,
        val query: String = "",
        val results: List<DossierListItem> = emptyList(),
        val filter: DossierRepository.DossierFilter = DossierRepository.DossierFilter(),
        val subjects: List<String> = emptyList(),
        /** A7 cycle 3: show only topics the user has starred (progress-side, not a pack filter). */
        val starredOnly: Boolean = false,
    )

    data class DossierState(
        val loading: Boolean = true,
        val dossier: Dossier? = null,
        val topicKey: String = "",
        val topicStarred: Boolean = false,
        /** hash → the user's own mark ("star"). */
        val userMarks: Map<String, String> = emptyMap(),
        /** hash → approximate auto highlight ("pyq" / "5star"). */
        val autoMarks: Map<String, String> = emptyMap(),
    ) {
        /** The winning mark for a line: the user's own beats the approximate highlight. */
        fun markKind(hash: String): String? = userMarks[hash] ?: autoMarks[hash]
    }

    data class ItemState(val loading: Boolean = true, val card: FeedCard? = null)

    private val registry = PackRegistry(app)
    private var repo: DossierRepository? = null
    private var progressDb: ProgressDatabase? = null
    private val dao get() = progressDb?.dao()

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
            ProgressDatabase.ensureExists(getApplication(), pack.examCode)
            progressDb = ProgressDatabase.open(getApplication(), pack.examCode)
            val opened = DossierRepository.open(pack.dbFile)
            Triple(opened, opened?.subjects().orEmpty(), opened?.list("") ?: emptyList())
        }
        repo = r
        _library.update { it.copy(loading = false, hasPack = r != null, results = top, subjects = subjects) }
    }

    fun search(query: String) {
        _library.update { it.copy(query = query, loading = true) }
        reloadList()
    }

    fun setLibraryFilter(filter: DossierRepository.DossierFilter) {
        _library.update { it.copy(filter = filter, loading = true) }
        reloadList()
    }

    fun toggleStarredOnly() {
        _library.update { it.copy(starredOnly = !it.starredOnly, loading = true) }
        reloadList()
    }

    private fun reloadList() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(180)
            val r = repo ?: return@launch
            val s = _library.value
            val results = withContext(Dispatchers.IO) {
                // A starred topic can sit anywhere in importance order, so widen the fetch then intersect
                // with the user's starred set (progress-side; the pack filter can't express "starred").
                val base = r.list(s.query, s.filter, limit = if (s.starredOnly) 2000 else 60)
                if (s.starredOnly) {
                    val starred = dao?.starredIds("topic")?.toSet().orEmpty()
                    base.filter { it.topicPageId in starred }.take(200)
                } else base
            }
            _library.update { it.copy(loading = false, results = results) }
        }
    }

    fun openDossier(topicPageId: Long) {
        _dossier.value = DossierState(loading = true, dossier = null)
        viewModelScope.launch {
            val r = repo ?: return@launch
            val state = withContext(Dispatchers.IO) {
                val d = r.loadDossier(topicPageId) ?: return@withContext DossierState(loading = false, dossier = null)
                val auto = buildAuto(d, r.highYield(topicPageId))
                val dd = dao
                val starred = dd?.isStarred("topic", topicPageId) ?: false
                val userMarks = dd?.lineMarks(d.stableKey)?.associate { it.line_hash to it.kind }.orEmpty()
                DossierState(loading = false, dossier = d, topicKey = d.stableKey,
                    topicStarred = starred, userMarks = userMarks, autoMarks = auto)
            }
            _dossier.value = state
        }
    }

    /** Approximate PYQ/5★ highlight: a body line lit if it shares ≥4 salient tokens with a related
     *  PYQ / 5★ MCQ stem. Conservative (a miss beats a false flag). Hash matches the renderer's block.raw. */
    private fun buildAuto(d: Dossier, hy: DossierRepository.HighYield): Map<String, String> {
        if (hy.pyq.isEmpty() && hy.fiveStar.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        val bodies = buildList { add(d.bodyMd); d.sections.forEach { add(it.bodyMd) } }
        for (md in bodies) {
            for (line in md.split("\n")) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val content = if (t.startsWith("- ")) t.removePrefix("- ")
                    else if (t.startsWith("* ") && !t.startsWith("**")) t.removePrefix("* ") else t
                val toks = LineText.tokens(content)
                if (toks.size < 4) continue
                val kind = when {
                    hy.pyq.any { (it intersect toks).size >= 4 } -> "pyq"
                    hy.fiveStar.any { (it intersect toks).size >= 4 } -> "5star"
                    else -> null
                } ?: continue
                out[LineText.hash(t)] = kind
            }
        }
        return out
    }

    fun toggleTopicStar() {
        val st = _dossier.value
        val d = st.dossier ?: return
        viewModelScope.launch {
            val newStarred = withContext(Dispatchers.IO) {
                val dd = dao ?: return@withContext st.topicStarred
                if (st.topicStarred) { dd.unstar("topic", d.topicPageId); false }
                else { dd.star(Star("topic", d.topicPageId, Instant.now().toString())); true }
            }
            _dossier.update { it.copy(topicStarred = newStarred) }
        }
    }

    fun toggleLineMark(sectionOrd: Int, rawLine: String) {
        val st = _dossier.value
        val key = st.topicKey.ifBlank { return }
        val hash = LineText.hash(rawLine)
        val exists = st.userMarks.containsKey(hash)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dd = dao ?: return@withContext
                if (exists) dd.removeLineMark(key, sectionOrd, hash)
                else dd.addLineMark(LineMark(key, sectionOrd, hash, "star", Instant.now().toString()))
            }
            _dossier.update {
                val m = it.userMarks.toMutableMap()
                if (exists) m.remove(hash) else m[hash] = "star"
                it.copy(userMarks = m)
            }
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
        progressDb?.close()
        super.onCleared()
    }
}
