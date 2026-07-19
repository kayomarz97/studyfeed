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
import dev.kayomarz.studyfeed.feed.SessionConfig
import dev.kayomarz.studyfeed.pack.PackDatabase
import dev.kayomarz.studyfeed.pack.PackRegistry
import dev.kayomarz.studyfeed.progress.BlobRead
import dev.kayomarz.studyfeed.progress.FeedState
import dev.kayomarz.studyfeed.progress.FsrsBridge
import dev.kayomarz.studyfeed.progress.FsrsCard
import dev.kayomarz.studyfeed.progress.ItemTag
import dev.kayomarz.studyfeed.progress.McqAttempt
import dev.kayomarz.studyfeed.progress.Mistake
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import dev.kayomarz.studyfeed.progress.Review
import dev.kayomarz.studyfeed.progress.SettingsKeys
import dev.kayomarz.studyfeed.progress.Star
import dev.kayomarz.studyfeed.progress.StudySession
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
 * Drives the Study Feed (BUILD_PROMPT §8 A2–A3; A7 cycle 3 sessions): windows the feed forward, and on
 * each interaction writes FSRS + progress **off the main thread** so a rating never blocks the swipe.
 *
 * **A3 ordering engine ([FeedScheduler]).** The default ("Smart / For You") feed is composed by the
 * engine — a due-pool + importance-weighted new drip + variety, paced to an editable deadline. The moment
 * an explicit sort or filter is chosen, we fall back to A2's [FeedRepository.page] path ([usesEngine]).
 *
 * **A7 cycle 3 — sessions + resume.** Every entry into the feed carries a [sessionKey] (the resume
 * namespace): `"mixed"` (endless), a quick-tile key (`"mcq"`/`"cards"`/`"passages"`/`"review"`), or
 * `"session:<id>"` for a **named** session. The engine's memory lives in `feed_state` under
 * `engine:<key>`, and the filtered feed's scroll position under `pos:<key>` — so each session resumes
 * exactly where it was left, and a named session mirrors its cursor to the `sessions` row for display.
 */
class FeedViewModel(app: Application) : AndroidViewModel(app) {

    data class Ui(
        val loading: Boolean = true,
        val cards: List<FeedCard> = emptyList(),
        val total: Int = 0,
        val examName: String? = null,
        val hasPack: Boolean = true,
        val whySheetFor: FeedCard? = null,
        val query: FeedQuery = FeedQuery(),
        val subjects: List<PackDatabase.Subject> = emptyList(),
        val tags: List<PackDatabase.TagDef> = emptyList(),
        val marks: Map<Pair<String, Long>, CardMarks> = emptyMap(),
        val studyMode: StudyMode = StudyMode.MIXED,
        val advanceAfterRating: Boolean = true,
        /** The name of the session being studied (a named session, or the quick-start label). */
        val sessionName: String? = null,
        /** Content types this feed is locked to (a single-type tile/session); null = the mixed feed. */
        val lockedTypes: List<String>? = null,
        /** Mirrored user settings for the feed UI (keep screen awake, haptic tick on interaction). */
        val keepScreenOn: Boolean = false,
        val haptics: Boolean = true,
        /** Where the pager should sit after a (re)load — the resume index inside the loaded window. */
        val startAt: Int = 0,
        /** Bumped on each (re)load so the screen re-applies [startAt] even within the same session. */
        val loadToken: Int = 0,
    )

    /**
     * The A7 study modes (user backlog 2.5) — how the user wants to study, mapped onto the query:
     * MIXED = the smart engine; BY_SUBJECT = a subject-grouped sweep; RECALL = MCQs + flashcards;
     * SKIM = reels + passages. Only MIXED runs the A3 engine (SMART + no filter).
     */
    enum class StudyMode { MIXED, BY_SUBJECT, RECALL, SKIM }

    data class CardMarks(val starred: Boolean = false, val tagKeys: Set<String> = emptySet())

    private val registry = PackRegistry(app)
    private val fsrs = FsrsBridge()
    private var repo: FeedRepository? = null
    private var examCode: String? = null
    private var loaded = 0

    /** Content types the current feed is locked to (a single-type tile/session); null = mixed. */
    private var lockedTypes: List<String>? = null
    /** Bumped each (re)load, surfaced as [Ui.loadToken] so the screen re-applies the resume index. */
    private var loadSeq = 0

    // ---- tracking: "done" on the primary interaction (2026-07-19) -----------------------------------
    /** MCQ ids already answered this app-open — makes a scroll-back re-answer idempotent (no double
     *  attempt/mistake). Cleared with the VM (a new app-open legitimately re-studies a due card). */
    private val answeredThisOpen = HashSet<Long>()
    /** Prior FSRS state + review-row id for a card completed this appearance, so an explicit rating can
     *  refine the SAME review instead of adding a second "done". */
    private data class Pending(val prior: FsrsCard?, val reviewId: Long)
    private val pending = HashMap<Pair<String, Long>, Pending>()
    /** Serialises completion vs refine for one card so they cannot double-insert a review. */
    private val writeMutex = Mutex()

    // ---- A7 cycle 3: session resume namespace -------------------------------------------------------
    /** "mixed" | "mcq" | "cards" | "passages" | "review" | "session:<id>". Keys engine + scroll state. */
    private var sessionKey: String = "mixed"
    /** Non-null when this is a named session (so its cursor is mirrored to the `sessions` row). */
    private var sessionId: Long? = null
    /** Absolute offset of cards[0] in the filtered (A2) path — pager index i ⇒ position baseOffset + i. */
    private var baseOffset: Int = 0
    private fun engineKey() = "engine:$sessionKey"
    private fun posKey() = "pos:$sessionKey"

    // ---- A3 ordering engine -------------------------------------------------------------------------
    private var engineConfig = FeedScheduler.Config()
    private var redrillN = DEFAULT_REDRILL_N
    private val engineStore = MemStore()
    private var scheduler: FeedScheduler? = null
    private val engineMutex = Mutex()
    private var deadline: LocalDate? = null
    private var engineExhausted = false

    private fun usesEngine(q: FeedQuery): Boolean = q.sort == FeedQuery.Sort.SMART && q.filter.isAll

    private var progressDb: ProgressDatabase? = null
    private val dao get() = progressDb?.dao()

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init { start() }

    private fun start() = viewModelScope.launch {
        loadPack()
        reload(_ui.value.query, resume = true)
    }

    /**
     * Open the active pack's reader + progress DB. A no-op once [repo] is set, so it is safe to call
     * again. Invoked at init AND lazily from [reload] to **self-heal** a feed created before a pack was
     * imported — the fresh-install → import → "Study" path, where this activity-scoped VM had cached
     * "no pack" and the feed would otherwise bounce straight back to the import screen.
     */
    private suspend fun loadPack() {
        if (repo != null) return
        val pack = withContext(Dispatchers.IO) { registry.activePack() } ?: return
        examCode = pack.examCode
        val loadedPack = withContext(Dispatchers.IO) {
            ProgressDatabase.ensureExists(getApplication(), pack.examCode)
            progressDb = ProgressDatabase.open(getApplication(), pack.examCode)
            repo = FeedRepository.open(ProgressDatabase.fileFor(getApplication(), pack.examCode), pack.dbFile)
            loadSettings()
            (PackDatabase.open(pack.dbFile) as? PackDatabase.Result.Ok)?.pack
        }
        _ui.update {
            it.copy(examName = loadedPack?.manifest?.examName,
                subjects = loadedPack?.subjects.orEmpty(), tags = loadedPack?.tags.orEmpty())
        }
    }

    // ---- entering the feed (sessions / quick tiles) -------------------------------------------------

    /** Resume the endless mixed feed (the default session). */
    fun openMixed() = bindAndReload("mixed", null, FeedQuery(sort = FeedQuery.Sort.SMART), "Endless mix")

    /** Enter a quick-tile feed under a stable [key] so it resumes where it was left. */
    fun openQuick(key: String, query: FeedQuery, label: String) = bindAndReload(key, null, query, label)

    /** Resume a named session (restores its config + saved position). */
    fun openSession(session: StudySession) = bindAndReload(
        "session:${session.id}", session.id, SessionConfig.fromJson(session.config_json),
        session.name ?: "Session")

    /** Create a named session from [query], then open it. [onCreated] fires on the main thread. */
    fun createSession(query: FeedQuery, onCreated: () -> Unit = {}) = viewModelScope.launch {
        val d = dao
        if (d == null) { onCreated(); return@launch }
        val now = Instant.now().toString()
        val name = SessionConfig.autoName(query) { subjectNameOf(it) }
        val id = withContext(Dispatchers.IO) {
            d.insertSession(StudySession(id = 0, mode = query.sort.name, started = now, ended = null,
                seen = 0, correct = null, name = name, config_json = SessionConfig.toJson(query),
                cursor = 0, updated = now, archived = 0))
        }
        bindAndReload("session:$id", id, query, name)
        onCreated()
    }

    private fun bindAndReload(key: String, sid: Long?, query: FeedQuery, label: String) {
        sessionKey = key; sessionId = sid
        // A single-type tile/session (itemTypesIn set at entry) locks the feed to that type: the Filter
        // sheet then hides "Content type" and "Clear all" keeps you on the type. The mixed feed = null.
        lockedTypes = query.filter.itemTypesIn?.takeIf { it.isNotEmpty() }
        // Set loading=true + hasPack=true SYNCHRONOUSLY (before the router swaps to the feed) so the feed
        // shows a spinner — never a bounce back to import — while [reload] self-heals the pack on first
        // entry after a fresh-install import. If there's genuinely no pack, reload flips hasPack back off.
        _ui.update { it.copy(studyMode = modeForQuery(query), sessionName = label, loading = true,
            hasPack = true, lockedTypes = lockedTypes) }
        reload(query, resume = true)
    }

    private fun subjectNameOf(id: Long): String? = _ui.value.subjects.firstOrNull { it.id == id }?.name

    private fun modeForQuery(q: FeedQuery): StudyMode = when {
        q.sort == FeedQuery.Sort.SMART && q.filter.isAll -> StudyMode.MIXED
        q.sort == FeedQuery.Sort.SUBJECT -> StudyMode.BY_SUBJECT
        q.filter.itemTypesIn?.toSet() == setOf("mcq", "flashcard") -> StudyMode.RECALL
        q.filter.itemTypesIn?.toSet() == setOf("reel", "blob") -> StudyMode.SKIM
        else -> _ui.value.studyMode
    }

    /** (Re)load the feed for [query]. [resume] restores the saved scroll position (filtered feeds). */
    private fun reload(query: FeedQuery, resume: Boolean = false) = viewModelScope.launch {
        _ui.update { it.copy(loading = true, query = query) }
        if (repo == null) loadPack()   // self-heal if this VM was created before the pack was imported
        val r = repo
        if (r == null) { _ui.update { it.copy(loading = false, hasPack = false) }; return@launch }
        engineExhausted = false
        // startAt = where the pager should sit after the window loads (the resume index INSIDE it).
        data class Loaded(val cards: List<FeedCard>, val total: Int, val offset: Int, val startAt: Int)
        val res = withContext(Dispatchers.IO) {
            initEngine() // (re)builds the scheduler reading engine:<sessionKey>
            if (usesEngine(query)) {
                // The engine streams forward; resume = continue after the saved cursor. No back-buffer.
                Loaded(engineWindow(PAGE), r.size(), 0, 0)
            } else {
                val total = r.count(query)
                val pos = if (resume) readPos().coerceIn(0, (total - 1).coerceAtLeast(0)) else 0
                // Resume, but keep the last BACK cards ABOVE the resume point loaded, so the user can
                // swipe UP through what they've already seen (their request) — not just forward.
                val off = (pos - BACK).coerceAtLeast(0)
                Loaded(r.page(query, off, BACK + PAGE, shuffleSeed()), total, off, pos - off)
            }
        }
        baseOffset = res.offset
        loaded = res.cards.size
        if (usesEngine(query) && res.cards.size < PAGE) engineExhausted = true
        _ui.update {
            it.copy(loading = false, hasPack = true, cards = res.cards, total = res.total, query = query,
                lockedTypes = lockedTypes,
                startAt = res.startAt.coerceIn(0, (res.cards.size - 1).coerceAtLeast(0)),
                loadToken = ++loadSeq)
        }
    }

    fun setSort(sort: FeedQuery.Sort) = reload(_ui.value.query.copy(sort = sort))
    fun setFilter(filter: FeedFilter) = reload(_ui.value.query.copy(filter = filter))

    fun reapplySettings() = viewModelScope.launch {
        if (repo == null) return@launch
        withContext(Dispatchers.IO) { loadSettings() }
        reload(_ui.value.query, resume = true)
    }

    /**
     * Drop the feed's in-memory caches after the user resets progress in Settings, so the next entry
     * rebuilds fresh — a new shuffle seed, no stale completion/answer guards, empty engine state.
     */
    fun onExternalReset() {
        cachedSeed = null
        pending.clear()
        answeredThisOpen.clear()
        engineStore.set(FeedScheduler.FeedState())
        loaded = 0
    }

    private suspend fun loadSettings() {
        val d = dao
        redrillN = d?.setting(SettingsKeys.REDRILL_N)?.toIntOrNull()?.coerceIn(1, 10) ?: DEFAULT_REDRILL_N
        val defaults = FeedScheduler.Config()
        engineConfig = defaults.copy(
            newRatioPct = d?.setting(SettingsKeys.NEW_RATIO_PCT)?.toIntOrNull()?.coerceIn(5, 90) ?: defaults.newRatioPct,
            dueBurst = d?.setting(SettingsKeys.DUE_BURST)?.toIntOrNull()?.coerceIn(10, 300) ?: defaults.dueBurst,
        )
        val advance = d?.setting(SettingsKeys.ADVANCE_AFTER_RATING)?.toBooleanStrictOrNull() ?: true
        val keepOn = d?.setting(SettingsKeys.KEEP_SCREEN_ON)?.toBooleanStrictOrNull() ?: false
        val haptics = d?.setting(SettingsKeys.HAPTICS)?.toBooleanStrictOrNull() ?: true
        _ui.update { it.copy(advanceAfterRating = advance, keepScreenOn = keepOn, haptics = haptics) }
    }

    /**
     * The per-feed shuffle seed (keyed by [sessionKey], persisted in `feed_state`). Stable within a feed
     * so OFFSET windows + resume stay coherent; different per feed; regenerated when the user resets a
     * feed's place. Drives the "shuffle within importance tiers" order (filtered path + engine pools).
     */
    private var cachedSeed: Pair<String, Int>? = null
    private suspend fun shuffleSeed(): Int {
        cachedSeed?.let { if (it.first == sessionKey) return it.second }
        val d = dao
        val key = "seed:$sessionKey"
        val existing = d?.feedState(key)?.toIntOrNull()
        val seed = existing ?: ((System.nanoTime().toInt() and 0x7fffffff).let { if (it == 0) 1 else it })
        if (existing == null) d?.putFeedState(FeedState(key, seed.toString()))
        cachedSeed = sessionKey to seed
        return seed
    }

    /** Append the next window when within [PREFETCH] of the end; also persist the resume position. */
    fun onReachedIndex(index: Int) {
        val r = repo ?: return
        savePosition(index)
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
            if (baseOffset + loaded >= _ui.value.total) return
            viewModelScope.launch {
                val next = withContext(Dispatchers.IO) { r.page(query, baseOffset + loaded, PAGE, shuffleSeed()) }
                if (next.isNotEmpty()) {
                    loaded += next.size
                    _ui.update { it.copy(cards = it.cards + next) }
                }
            }
        }
    }

    /** Persist where the user is, so this session resumes here next time. Off-thread, one small write. */
    private fun savePosition(index: Int) {
        val d = dao ?: return
        val q = _ui.value.query
        viewModelScope.launch(Dispatchers.IO) {
            val now = Instant.now().toString()
            if (usesEngine(q)) {
                val seen = engineStore.get().globalIndex
                sessionId?.let { d.updateSessionProgress(it, seen, seen, now) }
            } else {
                val abs = baseOffset + index
                d.putFeedState(FeedState(posKey(), abs.toString()))
                sessionId?.let { d.updateSessionProgress(it, abs, abs, now) }
            }
        }
    }

    private suspend fun readPos(): Int = dao?.feedState(posKey())?.toIntOrNull() ?: 0

    // ---- A3 engine plumbing -------------------------------------------------------------------------

    private suspend fun initEngine() {
        val r = repo ?: return
        // Seed the engine's within-tier shuffle once (persisted); reuse the per-feed seed for coherence.
        val loaded0 = FeedStateJson.fromJson(dao?.feedState(engineKey()))
        engineStore.set(if (loaded0.shuffleSeed == 0) loaded0.copy(shuffleSeed = shuffleSeed()) else loaded0)
        deadline = resolveDeadline()
        scheduler = FeedScheduler(poolsAdapter(r), engineStore, engineConfig)
    }

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
        return days.coerceAtLeast(1)
    }

    private suspend fun resolveDeadline(): LocalDate {
        dao?.setting(SettingsKeys.DEADLINE)?.let { runCatching { return LocalDate.parse(it) } }
        val d = LocalDate.now().plusDays(engineConfig.defaultDeadlineDays.toLong())
        dao?.putSetting(dev.kayomarz.studyfeed.progress.Setting(SettingsKeys.DEADLINE, d.toString()))
        return d
    }

    private suspend fun flushEngineState() {
        dao?.putFeedState(FeedState(engineKey(), FeedStateJson.toJson(engineStore.get())))
    }

    private fun poolsAdapter(r: FeedRepository) = object : FeedScheduler.Pools {
        override fun due(nowIso: String, exclude: Set<Long>, limit: Int) = r.duePool(nowIso, exclude, limit)
        override fun newMcqs(afterImp: Int?, afterId: Long?, seed: Int, limit: Int) = r.newMcqs(afterImp, afterId, seed, limit)
        override fun newOthers(afterImp: Int?, afterId: Long?, seed: Int, limit: Int) = r.newOthers(afterImp, afterId, seed, limit)
        override fun filler(nowIso: String, exclude: Set<Long>, limit: Int) = r.fillerPool(nowIso, exclude, limit)
        override fun remainingNewMcqs() = r.remainingNewMcqs()
        override fun blobRef(blobId: Long) = r.blobRef(blobId)
    }

    private class MemStore : FeedScheduler.StateStore {
        @Volatile private var s = FeedScheduler.FeedState()
        @Synchronized override fun load() = s
        @Synchronized override fun save(state: FeedScheduler.FeedState) { s = state }
        @Synchronized fun set(state: FeedScheduler.FeedState) { s = state }
        @Synchronized fun get() = s
    }

    // ---- interactions: write off the main thread, never block the swipe ----------------------------

    /**
     * An explicit rating. If the card was just auto-completed this appearance (answered/revealed), REFINE
     * that same review — recompute FSRS from the pre-completion state and update the review row, so there
     * is exactly one "done" per card. Otherwise (a genuine re-rating later) record a fresh review.
     */
    fun rate(card: FeedCard, rating: Rating) = viewModelScope.launch(Dispatchers.IO) {
        val d = dao ?: return@launch
        val key = card.itemType to card.itemId
        val now = Instant.now()
        writeMutex.withLock {
            val p = pending[key]
            val prior = p?.prior ?: d.card(card.itemType, card.itemId)
            if (card.itemType != "blob") {
                d.upsertCard(fsrs.review(prior, card.itemType, card.itemId, rating.toFsrs(), now))
            }
            if (p != null) d.updateReviewRating(p.reviewId, rating.ordinal + 1, now.toString())
            else d.insertReview(Review(item_type = card.itemType, item_id = card.itemId,
                rating = rating.ordinal + 1, ts = now.toString(), elapsed_ms = null))
        }
    }

    /**
     * Record a card as *done* the moment its primary interaction happens (MCQ answered, flashcard/reel/
     * interlink revealed, passage read). Schedules it via FSRS (except blobs, which schedule through
     * `blob_reads`) and writes ONE review, remembering the prior state so an explicit rating can refine
     * it. Guarded so a scroll-back re-render of the same card doesn't double-count.
     */
    private suspend fun recordCompletion(card: FeedCard, grade: Rating) {
        val d = dao ?: return
        val key = card.itemType to card.itemId
        val now = Instant.now()
        writeMutex.withLock {
            if (pending.containsKey(key)) return@withLock  // already recorded this appearance
            val prior = d.card(card.itemType, card.itemId)
            if (card.itemType != "blob") {
                d.upsertCard(fsrs.review(prior, card.itemType, card.itemId, grade.toFsrs(), now))
            }
            val reviewId = d.insertReview(Review(item_type = card.itemType, item_id = card.itemId,
                rating = grade.ordinal + 1, ts = now.toString(), elapsed_ms = null))
            pending[key] = Pending(prior, reviewId)
        }
    }

    /**
     * A flashcard/reel/interlink was revealed — that reveal is the "done" event (default Good; an explicit
     * rating afterwards refines it). Idempotent per appearance via [recordCompletion].
     */
    fun onSeen(card: FeedCard) = viewModelScope.launch(Dispatchers.IO) { recordCompletion(card, Rating.GOOD) }

    fun onMcqAnswered(card: FeedCard.Mcq, chosenIdx: Int, correct: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val d = dao ?: return@launch
        // A scroll-back re-render lets the card be answered again; log the answer only once per app-open
        // so accuracy/mistakes aren't double-counted (the schedule is deduped separately in recordCompletion).
        if (!answeredThisOpen.add(card.itemId)) return@launch
        val now = Instant.now().toString()
        // A7 cycle 3: log every answer so accuracy (overall / by subject / over time) has a denominator.
        d.insertMcqAttempt(McqAttempt(item_id = card.itemId, subject_id = card.subjectId,
            correct = if (correct) 1 else 0, ts = now))
        if (correct) {
            d.advanceResolution("mcq", card.itemId, redrillN)
        } else {
            val reset = d.resetUnresolved("mcq", card.itemId, now, chosenIdx)
            if (reset == 0) d.insertMistake(
                Mistake(item_type = "mcq", item_id = card.itemId, subject_id = card.subjectId,
                    chosen_idx = chosenIdx, ts = now, resolved = 0)
            )
        }
        // The answer IS the "done" event: schedule + record a review NOW, auto-graded from correctness
        // (right→Good, wrong→Again), so the card counts toward coverage/streak/activity without a second
        // tap. An explicit rating afterwards refines this same review (see [rate]).
        recordCompletion(card, if (correct) Rating.GOOD else Rating.AGAIN)
    }

    fun markBlobRead(card: FeedCard.Blob) = viewModelScope.launch(Dispatchers.IO) {
        val now = Instant.now()
        dao?.putBlobRead(BlobRead(
            blob_id = card.itemId,
            read_at = now.toString(),
            quizzed_at = null,
            reread_due = now.plus(java.time.Duration.ofDays(1)).toString(),
        ))
        engineMutex.withLock {
            scheduler?.scheduleBlobQuiz(card.itemId)
            flushEngineState()
        }
        // Reading the passage is its "done" event — record a review so it counts toward activity/streak
        // (blobs schedule their re-read via blob_reads above, not FSRS, so recordCompletion skips FSRS).
        recordCompletion(card, Rating.GOOD)
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
        /** Cards kept loaded ABOVE the resume point so the user can swipe back up through what they saw. */
        const val BACK = 40
        const val PREFETCH = 10
        const val DEFAULT_REDRILL_N = 3
    }
}

private fun Rating.toFsrs(): FsrsRating = when (this) {
    Rating.AGAIN -> FsrsRating.AGAIN
    Rating.HARD -> FsrsRating.HARD
    Rating.GOOD -> FsrsRating.GOOD
    Rating.EASY -> FsrsRating.EASY
}
