package dev.kayomarz.studyfeed.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kayomarz.studyfeed.feed.FeedRepository
import dev.kayomarz.studyfeed.pack.PackDatabase
import dev.kayomarz.studyfeed.pack.PackRegistry
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import dev.kayomarz.studyfeed.progress.SettingsKeys
import dev.kayomarz.studyfeed.stats.StatsRepository
import dev.kayomarz.studyfeed.stats.SubjectStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * The A7 **dashboard home** (user choice "Dashboard home first") — the landing page that shows "what's
 * left" before the user dives into the feed: due today, new remaining + today's target (the A3 deadline
 * pace), streak, and per-subject progress. Everything is derived from the user's own progress DB (+ the
 * pack for subject names/importance). Recomputed each time Home opens.
 *
 * **No exam string is hardcoded** — [Ui.examName] comes from `pack_manifest` (§0 multi_pack).
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    data class Ui(
        val loading: Boolean = true,
        val hasPack: Boolean = true,
        val examName: String? = null,
        val dueToday: Int = 0,
        val newRemaining: Int = 0,
        val dailyTarget: Int = 0,
        val streakDays: Int = 0,
        val reviewsToday: Int = 0,
        val subjects: List<SubjectStat> = emptyList(),
        // A7 content-type tiles: "X / total done" per type.
        val mcq: FeedRepository.TypeProgress = FeedRepository.TypeProgress(0, 0),
        val cards: FeedRepository.TypeProgress = FeedRepository.TypeProgress(0, 0), // flashcards + reels
        val passages: FeedRepository.TypeProgress = FeedRepository.TypeProgress(0, 0),
        val mistakes: Int = 0,
        val starred: Int = 0,
    )

    private val registry = PackRegistry(app)
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _ui.value = Ui(loading = true)
        val pack = withContext(Dispatchers.IO) { registry.activePack() }
        if (pack == null) { _ui.value = Ui(loading = false, hasPack = false); return@launch }

        val ui = withContext(Dispatchers.IO) {
            ProgressDatabase.ensureExists(getApplication(), pack.examCode)
            val pdb = ProgressDatabase.open(getApplication(), pack.examCode)
            val dao = pdb.dao()
            val timestamps = dao.reviewTimestamps()
            val deadlineStr = dao.setting(SettingsKeys.DEADLINE)
            val mistakes = dao.unresolvedMistakeCount()
            val starred = dao.starCount()
            pdb.close()

            val examName = (PackDatabase.open(pack.dbFile) as? PackDatabase.Result.Ok)?.pack?.manifest?.examName

            val feedRepo = FeedRepository.open(ProgressDatabase.fileFor(getApplication(), pack.examCode), pack.dbFile)
            val nowIso = Instant.now().toString()
            val due = feedRepo?.dueCount(nowIso) ?: 0
            val remainingNew = feedRepo?.remainingNewMcqs() ?: 0
            val prog = feedRepo?.progressByType().orEmpty()
            feedRepo?.close()

            val zero = FeedRepository.TypeProgress(0, 0)
            val flash = prog["flashcard"] ?: zero
            val reel = prog["reel"] ?: zero
            val mcq = prog["mcq"] ?: zero
            val cards = FeedRepository.TypeProgress(flash.total + reel.total, flash.done + reel.done)
            val passages = prog["blob"] ?: zero

            val stats = StatsRepository.open(ProgressDatabase.fileFor(getApplication(), pack.examCode), pack.dbFile)
            val subjects = stats?.subjectMastery().orEmpty()
            stats?.close()

            val today = LocalDate.now()
            val days = timestamps.mapNotNull { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }.toHashSet()
            val deadline = deadlineStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val daysLeft = deadline?.let { ChronoUnit.DAYS.between(today, it).coerceAtLeast(1) } ?: 56L
            Ui(
                loading = false, hasPack = true, examName = examName,
                dueToday = due, newRemaining = remainingNew,
                dailyTarget = if (remainingNew <= 0) 0 else ceil(remainingNew / daysLeft.toDouble()).toInt(),
                streakDays = streak(days, today),
                reviewsToday = timestamps.count { it.take(10) == today.toString() },
                subjects = subjects,
                mcq = mcq, cards = cards, passages = passages,
                mistakes = mistakes, starred = starred,
            )
        }
        _ui.value = ui
    }

    /** Consecutive calendar days with ≥1 review, ending today (or yesterday if today's not studied yet). */
    private fun streak(days: Set<LocalDate>, today: LocalDate): Int {
        var d = if (days.contains(today)) today else today.minusDays(1)
        var n = 0
        while (days.contains(d)) { n++; d = d.minusDays(1) }
        return n
    }
}
