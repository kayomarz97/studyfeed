package dev.kayomarz.studyfeed.ui.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kayomarz.studyfeed.feed.FeedCard
import dev.kayomarz.studyfeed.feed.FeedFilter
import dev.kayomarz.studyfeed.feed.FeedQuery
import dev.kayomarz.studyfeed.feed.FeedRepository
import dev.kayomarz.studyfeed.pack.PackDatabase
import dev.kayomarz.studyfeed.pack.PackRegistry
import dev.kayomarz.studyfeed.progress.BlobRead
import dev.kayomarz.studyfeed.progress.FsrsBridge
import dev.kayomarz.studyfeed.progress.ItemTag
import dev.kayomarz.studyfeed.progress.Mistake
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import dev.kayomarz.studyfeed.progress.Review
import dev.kayomarz.studyfeed.progress.Star
import io.github.openspacedrepetition.Rating as FsrsRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Drives the Study Feed (BUILD_PROMPT §8 A2): windows the feed forward, and on each interaction writes
 * FSRS + progress **off the main thread** so a rating never blocks the swipe (plan §3.5).
 *
 * **No ordering engine here — that is A3** (standing order #2). This serves the pack's own
 * importance-first order via [FeedRepository.page] and simply appends the next window as the user nears
 * the end. A3 replaces the paging call with its due-pool/variety engine; nothing else here changes.
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
    )

    /** A card's star + applied tags (progress-side state, not pack content). */
    data class CardMarks(val starred: Boolean = false, val tagKeys: Set<String> = emptySet())

    private val registry = PackRegistry(app)
    private val fsrs = FsrsBridge()
    private var repo: FeedRepository? = null
    private var examCode: String? = null
    private var loaded = 0

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
        val (first, total) = withContext(Dispatchers.IO) { r.page(query, 0, PAGE) to r.count(query) }
        loaded = first.size
        _ui.update {
            it.copy(loading = false, hasPack = true, cards = first, total = total, query = query)
        }
    }

    fun setSort(sort: FeedQuery.Sort) = reload(_ui.value.query.copy(sort = sort))
    fun setFilter(filter: FeedFilter) = reload(_ui.value.query.copy(filter = filter))

    /** Append the next window when the user is within [PREFETCH] of the end. */
    fun onReachedIndex(index: Int) {
        val r = repo ?: return
        if (index < loaded - PREFETCH || loaded >= _ui.value.total) return
        val query = _ui.value.query
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { r.page(query, loaded, PAGE) }
            if (next.isNotEmpty()) {
                loaded += next.size
                _ui.update { it.copy(cards = it.cards + next) }
            }
        }
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

    /** A wrong MCQ answer → the mistake ledger (§8 A2 → A6). */
    fun recordMistake(card: FeedCard.Mcq, chosenIdx: Int) = viewModelScope.launch(Dispatchers.IO) {
        dao?.insertMistake(
            Mistake(item_type = "mcq", item_id = card.itemId, subject_id = null,
                chosen_idx = chosenIdx, ts = Instant.now().toString(), resolved = 0)
        )
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
    }
}

private fun Rating.toFsrs(): FsrsRating = when (this) {
    Rating.AGAIN -> FsrsRating.AGAIN
    Rating.HARD -> FsrsRating.HARD
    Rating.GOOD -> FsrsRating.GOOD
    Rating.EASY -> FsrsRating.EASY
}
