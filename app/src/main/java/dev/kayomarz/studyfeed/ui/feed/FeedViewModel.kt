package dev.kayomarz.studyfeed.ui.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kayomarz.studyfeed.feed.FeedCard
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
    )

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
        val first = withContext(Dispatchers.IO) { repo?.page(0, PAGE) ?: emptyList() }
        loaded = first.size
        _ui.update {
            it.copy(loading = false, hasPack = true, examName = loadedPack?.manifest?.examName,
                total = repo?.size() ?: 0, cards = first)
        }
    }

    /** Append the next window when the user is within [PREFETCH] of the end. */
    fun onReachedIndex(index: Int) {
        val r = repo ?: return
        if (index < loaded - PREFETCH || loaded >= _ui.value.total) return
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { r.page(loaded, PAGE) }
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
        // §8 A2 step 9: record the read; A3 schedules the quiz-back + re-read from this. In-app only —
        // no notification, no alarm (the offline guard forbids background components).
        dao?.putBlobRead(BlobRead(blob_id = card.itemId, read_at = Instant.now().toString(),
            quizzed_at = null, reread_due = null))
    }

    fun toggleStar(card: FeedCard) = viewModelScope.launch(Dispatchers.IO) {
        val d = dao ?: return@launch
        if (d.isStarred(card.itemType, card.itemId)) d.unstar(card.itemType, card.itemId)
        else d.star(Star(card.itemType, card.itemId, Instant.now().toString()))
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
