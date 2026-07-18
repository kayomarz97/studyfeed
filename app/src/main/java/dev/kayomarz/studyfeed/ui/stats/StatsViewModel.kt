package dev.kayomarz.studyfeed.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kayomarz.studyfeed.feed.FeedRepository
import dev.kayomarz.studyfeed.pack.PackRegistry
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import dev.kayomarz.studyfeed.progress.SettingsKeys
import dev.kayomarz.studyfeed.stats.CurvePoint
import dev.kayomarz.studyfeed.stats.GapItem
import dev.kayomarz.studyfeed.stats.StatsRepository
import dev.kayomarz.studyfeed.stats.SubjectStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * Assembles the A6 Progress screen (BUILD_PROMPT §8 A6): streak / daily goal / XP, the mistake pile,
 * per-subject mastery, the forgetting curve, and high-importance gaps — all derived from the user's own
 * progress DB (+ the pack for subject/importance). Recomputed each time the screen opens.
 */
class StatsViewModel(app: Application) : AndroidViewModel(app) {

    data class Ui(
        val loading: Boolean = true,
        val hasPack: Boolean = true,
        val hasData: Boolean = false,      // any reviews yet? (fresh install shows an empty state)
        val streakDays: Int = 0,
        val reviewsToday: Int = 0,
        /** Today's new-MCQ target = the A3 deadline pace (remaining new ÷ days left). 0 = none remaining. */
        val dailyGoal: Int = 0,
        val xp: Int = 0,
        val accuracy: Double = 0.0,
        val unresolvedMistakes: Int = 0,
        val resolvedMistakes: Int = 0,
        val subjects: List<SubjectStat> = emptyList(),
        val gaps: List<GapItem> = emptyList(),
        val curve: List<CurvePoint> = emptyList(),
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
            val totalReviews = dao.totalReviews()
            val lapses = dao.totalLapses()
            val resolved = dao.resolvedMistakeCount()
            val unresolved = dao.unresolvedMistakeCount()
            val deadlineStr = dao.setting(SettingsKeys.DEADLINE)
            pdb.close()

            val stats = StatsRepository.open(ProgressDatabase.fileFor(getApplication(), pack.examCode), pack.dbFile)
            val subjects = stats?.subjectMastery().orEmpty()
            val gaps = stats?.highImportanceGaps().orEmpty()
            val curve = stats?.forgettingCurve().orEmpty()
            stats?.close()

            // Daily goal = the A3 deadline pace, not a fixed number (A7): remaining new MCQs ÷ days left.
            val feedRepo = FeedRepository.open(ProgressDatabase.fileFor(getApplication(), pack.examCode), pack.dbFile)
            val remainingNew = feedRepo?.remainingNewMcqs() ?: 0
            feedRepo?.close()

            val days = timestamps.mapNotNull { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }.toHashSet()
            val today = LocalDate.now()
            val deadline = deadlineStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val daysLeft = deadline?.let { ChronoUnit.DAYS.between(today, it).coerceAtLeast(1) } ?: 56L
            val dailyGoal = if (remainingNew <= 0) 0 else ceil(remainingNew / daysLeft.toDouble()).toInt()
            Ui(
                loading = false, hasPack = true, hasData = totalReviews > 0,
                streakDays = streak(days, today),
                reviewsToday = timestamps.count { it.take(10) == today.toString() },
                dailyGoal = dailyGoal,
                xp = totalReviews * 10 + resolved * 25,
                accuracy = if (totalReviews > 0) 1.0 - lapses.toDouble() / totalReviews else 0.0,
                unresolvedMistakes = unresolved, resolvedMistakes = resolved,
                subjects = subjects, gaps = gaps, curve = curve,
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
