package dev.kayomarz.studyfeed.ui.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kayomarz.studyfeed.feed.FeedCard
import dev.kayomarz.studyfeed.feed.FeedFilter
import dev.kayomarz.studyfeed.feed.FeedQuery
import dev.kayomarz.studyfeed.feed.FeedRepository
import dev.kayomarz.studyfeed.feed.FeedScheduler
import dev.kayomarz.studyfeed.feed.FeedStateJson
import dev.kayomarz.studyfeed.pack.PackDatabase
import dev.kayomarz.studyfeed.pack.PackRegistry
import dev.kayomarz.studyfeed.progress.BlobRead
import dev.kayomarz.studyfeed.progress.FsrsBridge
import dev.kayomarz.studyfeed.progress.ItemTag
import dev.kayomarz.studyfeed.progress.Mistake
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import dev.kayomarz.studyfeed.progress.Review
import dev.kayomarz.studyfeed.progress.SettingsKeys
import dev.kayomarz.studyfeed.progress.Star
import io.github.openspacedrepetition.Rating as FsrsRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Drives the Study Feed (BUILD_PROMPT §8 A2–A3): windows the feed forward, and on each interaction
 * writes FSRS + progress **off the main thread** so a rating never blocks the swipe (plan §3.5).
 *
 * **A3 ordering engine ([FeedScheduler]).** The default ("Smart / For You") feed is composed by the
 * engine — a due-pool + importance-weighted new drip + variety, paced to an editable deadline. The
 * moment an explicit sort or any filter is chosen, we fall back to A2's proven [FeedRepository.page]
 * path ([usesEngine] gates this), so Acceptance 4's filter/sort behaviour is untouched. The engine's
 * memory (ring buffer, drip counters, cursors, pending blob quiz-backs) lives in `feed_state` so it
 * survives restart and the §11e export/import.
 */
class FeedViewModel(app: Application) : AndroidViewModel(app) {

    data class Ui(
        val loading: Boolean = true,
        val cards: List<FeedCard> = emptyList(),
        val total: Int = 0,
        val examName: String? = null,
        /** null = no pack imported; the screen shows an empty state and defers to the pack screen. */
        val hasPack: Boolean = true,
        val whySheetFor: FeedCard? = null,
        val query: FeedQuery = FeedQuery(),
        /** subjects/tags for the filter dropdowns — read from the pack, of course. */
        val subjects: List<PackDatabase.Subject> = emptyList(),
        val tags: List<PackDatabase.TagDef> = emptyList(),
        /** per-card star/tag state, keyed by (item_type, item_id); loaded lazily as cards appear. */
        val marks: Map<Pair<String, Long>, CardMarks> = emptyMap(),
        /** The A7 study mode currently selected (drives the base query). */
        val studyMode: StudyMode = StudyMode.MIXED,
    )

    /**
     * The A7 study modes (user backlog 2.5) — how the user wants to study, mapped onto the query:
     * - [MIXED] the smart engine (due-led blend + deadline drip + variety) — the default.
     * - [BY_SUBJECT] a structured, subject-grouped sweep.
     * - [RECALL] active-recall only — MCQs + flashcards.
     * - [SKIM] passive skim — reels + passages.
     *
     * Only MIXED runs the A3 engine (SMART + no filter); the others use the A2 SQL page path.
     */
    enum class StudyMode { MIXED, BY_SUBJECT, RECALL, SKIM }

    private fun StudyMode.toQuery(): FeedQuery = when (this) {
        StudyMode.MIXED -> FeedQuery(sort = FeedQuery.Sort.SMART, filter = FeedFilter())
        StudyMode.BY_SUBJECT -> FeedQuery(sort = FeedQuery.Sort.SUBJECT, filter = FeedFilter())
        StudyMode.RECALL -> FeedQuery(sort = FeedQuery.Sort.IMPORTANCE_DESC,
            filter = FeedFilter(itemTypesIn = listOf("mcq", "flashcard")))
        StudyMode.SKIM -> FeedQuery(sort = FeedQuery.Sort.IMPORTANCE_DESC,
            filter = FeedFilter(itemTypesIn = listOf("reel", "blob")))
    }

    /** A card's star + applied tags (progress-side state, not pack content). */
    data class CardMarks(val starred: Boolean = false, val tagKeys: Set<String> = emptySet())

    private val registry = PackRegistry(app)
    private val fsrs = FsrsBridge()
    private var repo: FeedRepository? = null
    private var examCode: String? = null
    private var loaded = 0

    // ---- A3 ordering engine -------------------------------------------------------------------------
    /** Built from the user's Settings knobs (intensity → dueBurst/newRatioPct); re-read on reapply. */
    private var engineConfig = FeedScheduler.Config()
    /** A6 re-drill: correct this many times to resolve a mistake. Settings-tunable (A7). */
    private var redrillN = DEFAULT_REDRILL_N
    private val engineStore = MemStore()
    private var scheduler: FeedScheduler? = null
    /** Serialises engine windows — [FeedScheduler] is stateful, so overlapping windows would race. */
    private val engineMutex = Mutex()
    /** The (editable) MCQ finish line; loaded/defaulted in [start]. */
    private var deadline: LocalDate? = null
    /** True once the engine's stream has no more cards to give (fully studied + nothing due). */
    private var engineExhausted = false

    /** The engine drives ONLY the default feed; any explicit sort or filter uses the A2 SQL path. */
    private fun usesEngine(q: FeedQuery): Boolean = q.sort == FeedQuery.Sort.SMART && q.filter.isAll

    // ONE Room instance for the session, opened when the active exam is known — reopening it per rating
    // (as an earlier draft did) leaks connections and re-runs Room's open cost on every swipe.
    private var progressDb: ProgressDatabase? = null
    private val dao get() = progressDb?.dao()

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init { start() }

    private fun start() = viewModelScope.launch {
        val pack = withContext(Dispatchers.IO) { registry.activePack() }
        if (pack == null) { _ui.update { it.copy(loading = false, hasPack = false) }; return@launch }
        examCode = pack.examCode
        val loadedPack = withContext(Dispatchers.IO) {
            // ensureExists first — Room creates the progress file lazily, and FeedRepository needs BOTH
            // files present to ATTACH (the 0-items bug from A1; see ProgressDatabase.ensureExists KDoc).
            ProgressDatabase.ensureExists(getApplication(), pack.examCode)
            progressDb = ProgressDatabase.open(getApplication(), pack.examCode)
            repo = FeedRepository.open(ProgressDatabase.fileFor(getApplication(), pack.examCode), pack.dbFile)
            loadSettings()
            initEngine()
            (PackDatabase.open(pack.dbFile) as? PackDatabase.Result.Ok)?.pack
        }
        _ui.update {
            it.copy(examName = loadedPack?.manifest?.examName,
                subjects = loadedPack?.subjects.orEmpty(), tags = loadedPack?.tags.orEmpty())
        }
        reload(_ui.value.query)
    }

    /** (Re)load the feed from the top for [query] — on first load and whenever a filter/sort changes. */
    private fun reload(query: FeedQuery) = viewModelScope.launch {
        val r = repo
        if (r == null) { _ui.update { it.copy(loading = false, hasPack = false) }; return@launch }
        _ui.update { it.copy(loading = true, query = query) }
        engineExhausted = false
        val (first, total) = withContext(Dispatchers.IO) {
            if (usesEngine(query)) engineWindow(PAGE) to r.size()   // engine: total is a rough deck size
            else r.page(query, 0, PAGE) to r.count(query)
        }
        loaded = first.size
        if (usesEngine(query) && first.size < PAGE) engineExhausted = true
        _ui.update {
            it.copy(loading = false, hasPack = true, cards = first, total = total, query = query)
        }
    }

    fun setSort(sort: FeedQuery.Sort) = reload(_ui.value.query.copy(sort = sort))
    fun setFilter(filter: FeedFilter) = reload(_ui.value.query.copy(filter = filter))

    /** Switch A7 study mode (Mixed / By subject / Recall / Skim) — resets the query to that mode's base. */
    fun setStudyMode(mode: StudyMode) {
        _ui.update { it.copy(studyMode = mode) }
        reload(mode.toQuery())
    }

    /**
     * Re-read the Settings knobs (deadline, re-drill N, feed intensity) and rebuild the engine, then
     * reload. Called when the user returns from the Settings screen so a change takes effect immediately
     * (this VM is activity-scoped and would otherwise keep the values it read at first open).
     */
    fun reapplySettings() = viewModelScope.launch {
        if (repo == null) return@launch
        withContext(Dispatchers.IO) {
            loadSettings()
            initEngine() // rebuilds the scheduler with the new config + re-reads the deadline setting
        }
        reload(_ui.value.query)
    }

    /** Read the A7 Settings knobs into [engineConfig] + [redrillN]; falls back to the built-in defaults. */
    private suspend fun loadSettings() {
        val d = dao
        redrillN = d?.setting(SettingsKeys.REDRILL_N)?.toIntOrNull()?.coerceIn(1, 10) ?: DEFAULT_REDRILL_N
        val defaults = FeedScheduler.Config()
        engineConfig = defaults.copy(
            newRatioPct = d?.setting(SettingsKeys.NEW_RATIO_PCT)?.toIntOrNull()?.coerceIn(5, 90) ?: defaults.newRatioPct,
            dueBurst = d?.setting(SettingsKeys.DUE_BURST)?.toIntOrNull()?.coerceIn(10, 300) ?: defaults.dueBurst,
        )
    }

    /** Append the next window when the user is within [PREFETCH] of the end. */
    fun onReachedIndex(index: Int) {
        val r = repo ?: return
        if (index < loaded - PREFETCH) return
        val query = _ui.value.query
        if (usesEngine(query)) {
            if (engineExhausted) return
            viewModelScope.launch {
                val next = withContext(Dispatchers.IO) { engineWindow(PAGE) }
                if (next.isEmpty()) { engineExhausted = true; return@launch }
                loaded += next.size
                if (next.size < PAGE) engineExhausted = true
                _ui.update { it.copy(cards = it.cards + next) }
            }
        } else {
            if (loaded >= _ui.value.total) return
            viewModelScope.launch {
                val next = withContext(Dispatchers.IO) { r.page(query, loaded, PAGE) }
                if (next.isNotEmpty()) {
                    loaded += next.size
                    _ui.update { it.copy(cards = it.cards + next) }
                }
            }
        }
    }

    // ---- A3 engine plumbing -------------------------------------------------------------------------

    /** Build the scheduler for this app-open: hydrate `feed_state`, resolve the deadline setting. */
    private suspend fun initEngine() {
        val r = repo ?: return
        engineStore.set(FeedStateJson.fromJson(dao?.feedState(ENGINE_KEY)))
        deadline = resolveDeadline()
        scheduler = FeedScheduler(poolsAdapter(r), engineStore, engineConfig)
    }

    /**
     * Compose the next engine window and load its payloads, then flush the engine's state to
     * `feed_state`. Serialised by [engineMutex] because the scheduler is stateful. Runs on IO.
     */
    private suspend fun engineWindow(n: Int): List<FeedCard> = engineMutex.withLock {
        val sch = scheduler ?: return emptyList()
        val r = repo ?: return emptyList()
        val now = Instant.now()
        val refs = sch.window(n, now.toString(), LocalDate.now().toString(), daysUntilDeadline(now))
        val cards = r.loadCards(refs)
        flushEngineState()
        cards
    }

    private fun daysUntilDeadline(now: Instant): Int {
        val d = deadline ?: return engineConfig.defaultDeadlineDays
        val days = ChronoUnit.DAYS.between(LocalDate.now(), d).toInt()
        return days.coerceAtLeast(1)   // on/after the finish line → pace becomes "serve all remaining"
    }

    /** Read `feed.deadline` from settings; default to today + 8 weeks on first run and persist it. */
    private suspend fun resolveDeadline(): LocalDate {
        dao?.setting(SettingsKeys.DEADLINE)?.let { runCatching { return LocalDate.parse(it) } }
        val d = LocalDate.now().plusDays(engineConfig.defaultDeadlineDays.toLong())
        dao?.putSetting(dev.kayomarz.studyfeed.progress.Setting(SettingsKeys.DEADLINE, d.toString()))
        return d
    }

    private suspend fun flushEngineState() {
        dao?.putFeedState(dev.kayomarz.studyfeed.progress.FeedState(ENGINE_KEY, FeedStateJson.toJson(engineStore.get())))
    }

    private fun poolsAdapter(r: FeedRepository) = object : FeedScheduler.Pools {
        override fun due(nowIso: String, exclude: Set<Long>, limit: Int) = r.duePool(nowIso, exclude, limit)
        override fun newMcqs(afterScore: Double?, afterId: Long?, limit: Int) = r.newMcqs(afterScore, afterId, limit)
        override fun newOthers(afterScore: Double?, afterId: Long?, limit: Int) = r.newOthers(afterScore, afterId, limit)
        override fun filler(nowIso: String, exclude: Set<Long>, limit: Int) = r.fillerPool(nowIso, exclude, limit)
        override fun remainingNewMcqs() = r.remainingNewMcqs()
        override fun blobRef(blobId: Long) = r.blobRef(blobId)
    }

    /** In-memory mirror of `feed_state` the scheduler reads/writes synchronously; flushed to DB by us. */
    private class MemStore : FeedScheduler.StateStore {
        @Volatile private var s = FeedScheduler.FeedState()
        @Synchronized override fun load() = s
        @Synchronized override fun save(state: FeedScheduler.FeedState) { s = state }
        @Synchronized fun set(state: FeedScheduler.FeedState) { s = state }
        @Synchronized fun get() = s
    }

    // ---- interactions: write off the main thread, never block the swipe ----------------------------

    fun rate(card: FeedCard, rating: Rating) = viewModelScope.launch(Dispatchers.IO) {
        val d = dao ?: return@launch
        val now = Instant.now()
        val prior = d.card(card.itemType, card.itemId)
        val updated = fsrs.review(prior, card.itemType, card.itemId, rating.toFsrs(), now)
        d.upsertCard(updated)
        d.insertReview(Review(item_type = card.itemType, item_id = card.itemId,
            rating = rating.ordinal + 1, ts = now.toString(), elapsed_ms = null))
    }

    /**
     * An MCQ answer feeds the mistake re-drill (§8 A2 → A6). **Correct** → advance the item's pending
     * mistake toward resolution (resolved after [REDRILL_N] consecutive corrects). **Wrong** → restart
     * the drill: reset an existing unresolved mistake to 0/N, or open a fresh one. So answering a
     * mistaken item correctly N times in the Mistakes review clears it from the pile.
     */
    fun onMcqAnswered(card: FeedCard.Mcq, chosenIdx: Int, correct: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val d = dao ?: return@launch
        if (correct) {
            d.advanceResolution("mcq", card.itemId, redrillN)
        } else {
            val now = Instant.now().toString()
            val reset = d.resetUnresolved("mcq", card.itemId, now, chosenIdx)
            if (reset == 0) d.insertMistake(
                Mistake(item_type = "mcq", item_id = card.itemId, subject_id = null,
                    chosen_idx = chosenIdx, ts = now, resolved = 0)
            )
        }
    }

    fun markBlobRead(card: FeedCard.Blob) = viewModelScope.launch(Dispatchers.IO) {
        // §8 A2 step 9: record the read and schedule a re-read for NEXT SESSION. The resurfacing is
        // in-app on next open — never a notification or alarm (the offline guard forbids background
        // components). A3's ordering engine consumes reread_due to place the passage back in the feed.
        val now = Instant.now()
        dao?.putBlobRead(BlobRead(
            blob_id = card.itemId,
            read_at = now.toString(),
            quizzed_at = null,
            reread_due = now.plus(java.time.Duration.ofDays(1)).toString(),
        ))
        // A3: also land the quiz-back a few cards later in THIS session (A2 deferred the delayed insert).
        // Under the engine mutex so a concurrent window() can't overwrite the newly-pending quiz-back.
        engineMutex.withLock {
            scheduler?.scheduleBlobQuiz(card.itemId)
            flushEngineState()
        }
    }

    fun toggleStar(card: FeedCard) = viewModelScope.launch(Dispatchers.IO) {
        val d = dao ?: return@launch
        val nowStarred = if (d.isStarred(card.itemType, card.itemId)) {
            d.unstar(card.itemType, card.itemId); false
        } else {
            d.star(Star(card.itemType, card.itemId, Instant.now().toString())); true
        }
        updateMarks(card) { it.copy(starred = nowStarred) }
    }

    fun toggleTag(card: FeedCard, tagKey: String) = viewModelScope.launch(Dispatchers.IO) {
        val d = dao ?: return@launch
        val has = d.tagsFor(card.itemType, card.itemId).contains(tagKey)
        if (has) d.removeTag(card.itemType, card.itemId, tagKey)
        else d.addTag(ItemTag(card.itemType, card.itemId, tagKey, Instant.now().toString()))
        val tags = d.tagsFor(card.itemType, card.itemId).toSet()
        updateMarks(card) { it.copy(tagKeys = tags) }
    }

    /** Load the star/tag state for a card the first time it's shown (so the icons reflect the DB). */
    fun loadMarks(card: FeedCard) = viewModelScope.launch(Dispatchers.IO) {
        if (_ui.value.marks.containsKey(card.itemType to card.itemId)) return@launch
        val d = dao ?: return@launch
        val starred = d.isStarred(card.itemType, card.itemId)
        val tags = d.tagsFor(card.itemType, card.itemId).toSet()
        updateMarks(card) { CardMarks(starred, tags) }
    }

    private fun updateMarks(card: FeedCard, f: (CardMarks) -> CardMarks) {
        val key = card.itemType to card.itemId
        _ui.update { ui -> ui.copy(marks = ui.marks + (key to f(ui.marks[key] ?: CardMarks()))) }
    }

    fun showWhy(card: FeedCard) = _ui.update { it.copy(whySheetFor = card) }
    fun dismissWhy() = _ui.update { it.copy(whySheetFor = null) }

    override fun onCleared() {
        repo?.close()
        progressDb?.close()
        super.onCleared()
    }

    private companion object {
        const val PAGE = 40
        const val PREFETCH = 10
        const val ENGINE_KEY = "engine"          // feed_state row holding the serialised engine state
        const val DEFAULT_REDRILL_N = 3          // A6 default (A7-tunable via Settings; keys in SettingsKeys)
    }
}

private fun Rating.toFsrs(): FsrsRating = when (this) {
    Rating.AGAIN -> FsrsRating.AGAIN
    Rating.HARD -> FsrsRating.HARD
    Rating.GOOD -> FsrsRating.GOOD
    Rating.EASY -> FsrsRating.EASY
}
