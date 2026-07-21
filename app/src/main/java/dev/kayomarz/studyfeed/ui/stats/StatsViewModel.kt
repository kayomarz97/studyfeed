package dev.kayomarz.studyfeed.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kayomarz.studyfeed.feed.FeedRepository
import dev.kayomarz.studyfeed.pack.PackRegistry
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import dev.kayomarz.studyfeed.progress.SettingsKeys
import dev.kayomarz.studyfeed.stats.CoreCoverage
import dev.kayomarz.studyfeed.stats.CurvePoint
import dev.kayomarz.studyfeed.stats.GapItem
import dev.kayomarz.studyfeed.stats.StatsRepository
import dev.kayomarz.studyfeed.stats.SubjectCoverage
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
 * Assembles the data-rich Progress screen (A6, rebuilt in A7 cycle 3): today (streak / goal / XP / **MCQ**
 * accuracy), MCQ coverage + accuracy by subject, an activity heatmap, weekly accuracy + the forgetting
 * curve, a due forecast, and 5★/PYQ core coverage — all from the user's own progress (+ the pack for
 * subject/importance/PYQ). Recomputed each time the screen opens.
 */
class StatsViewModel(app: Application) : AndroidViewModel(app) {

    /** One day in the activity heatmap. */
    data class HeatDay(val date: String, val count: Int)

    /** One bar in the due forecast. */
    data class DayBar(val label: String, val count: Int)

    data class Ui(
        val loading: Boolean = true,
        val hasPack: Boolean = true,
        /** Does the loaded pack contain anything scorable at all? false ⇒ an ABSENT (not "no progress
         *  yet") empty state — a lean pack the app simply can't build stats from. */
        val packHasContent: Boolean = true,
        val hasData: Boolean = false,
        val streakDays: Int = 0,
        val reviewsToday: Int = 0,
        /** Today's new-MCQ target = the A3 deadline pace (remaining new ÷ days left). 0 = none remaining. */
        val dailyGoal: Int = 0,
        val xp: Int = 0,
        /** MCQ accuracy = correct ÷ answered (null until the first MCQ is answered). Not blended with reels. */
        val mcqAccuracy: Double? = null,
        val mcqAnswered: Int = 0,
        val unresolvedMistakes: Int = 0,
        val subjects: List<SubjectCoverage> = emptyList(),
        val gaps: List<GapItem> = emptyList(),
        val curve: List<CurvePoint> = emptyList(),
        /** [HEAT_WEEKS]×7 days, oldest first (Monday-aligned columns handled by the screen). */
        val heat: List<HeatDay> = emptyList(),
        val heatMax: Int = 0,
        /** Weekly MCQ accuracy, oldest → newest ([ACC_WEEKS] entries); null = a week with no attempts. */
        val weekly: List<Double?> = emptyList(),
        /** Overdue + the next 7 days. */
        val forecast: List<DayBar> = emptyList(),
        val core: CoreCoverage = CoreCoverage(0, 0, 0, 0),
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
            val resolved = dao.resolvedMistakeCount()
            val unresolved = dao.unresolvedMistakeCount()
            val deadlineStr = dao.setting(SettingsKeys.DEADLINE)
            pdb.close()

            val stats = StatsRepository.open(ProgressDatabase.fileFor(getApplication(), pack.examCode), pack.dbFile)
            val totals = stats?.mcqTotals()
            val subjects = stats?.subjectCoverage().orEmpty()
            val gaps = stats?.highImportanceGaps().orEmpty()
            val curve = stats?.forgettingCurve().orEmpty()
            val core = stats?.coreCoverage() ?: CoreCoverage(0, 0, 0, 0)

            val today = LocalDate.now()
            // Activity heatmap — last HEAT_WEEKS*7 days ending today.
            val heatStart = today.minusDays((HEAT_WEEKS * 7 - 1).toLong())
            val activity = stats?.activityCounts(heatStart.toString()).orEmpty()
            val heat = (0 until HEAT_WEEKS * 7).map { i ->
                val d = heatStart.plusDays(i.toLong()); HeatDay(d.toString(), activity[d.toString()] ?: 0)
            }
            // Weekly MCQ accuracy — last ACC_WEEKS weeks.
            val accStart = today.minusDays((ACC_WEEKS * 7 - 1).toLong())
            val daily = stats?.accuracyDaily(accStart.toString()).orEmpty()
            val wAtt = IntArray(ACC_WEEKS); val wCor = IntArray(ACC_WEEKS)
            daily.forEach { da ->
                val d = runCatching { LocalDate.parse(da.date) }.getOrNull() ?: return@forEach
                val wi = (ChronoUnit.DAYS.between(accStart, d) / 7).toInt().coerceIn(0, ACC_WEEKS - 1)
                wAtt[wi] += da.attempts; wCor[wi] += da.correct
            }
            val weekly = (0 until ACC_WEEKS).map { if (wAtt[it] > 0) wCor[it].toDouble() / wAtt[it] else null }
            // Due forecast — overdue + next 7 days.
            val dueB = stats?.dueBuckets().orEmpty()
            val todayStr = today.toString()
            val overdue = dueB.entries.sumOf { if (it.key < todayStr) it.value else 0 }
            val forecast = listOf(DayBar("!", overdue)) + (0..6).map { i ->
                val d = today.plusDays(i.toLong())
                DayBar(if (i == 0) "Now" else d.dayOfWeek.name.take(1), dueB[d.toString()] ?: 0)
            }
            stats?.close()

            val feedRepo = FeedRepository.open(ProgressDatabase.fileFor(getApplication(), pack.examCode), pack.dbFile)
            val remainingNew = feedRepo?.remainingNewMcqs() ?: 0
            val packHasContent = (feedRepo?.size() ?: 0) > 0
            feedRepo?.close()

            val days = timestamps.mapNotNull { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }.toHashSet()
            val deadline = deadlineStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val daysLeft = deadline?.let { ChronoUnit.DAYS.between(today, it).coerceAtLeast(1) } ?: 56L
            Ui(
                loading = false, hasPack = true, packHasContent = packHasContent,
                hasData = totalReviews > 0 || (totals?.answered ?: 0) > 0,
                streakDays = streak(days, today),
                reviewsToday = timestamps.count { it.take(10) == today.toString() },
                dailyGoal = if (remainingNew <= 0) 0 else ceil(remainingNew / daysLeft.toDouble()).toInt(),
                xp = totalReviews * 10 + resolved * 25,
                mcqAccuracy = totals?.accuracy, mcqAnswered = totals?.answered ?: 0,
                unresolvedMistakes = unresolved,
                subjects = subjects, gaps = gaps, curve = curve,
                heat = heat, heatMax = heat.maxOfOrNull { it.count } ?: 0,
                weekly = weekly, forecast = forecast, core = core,
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

    private companion object {
        const val HEAT_WEEKS = 16
        const val ACC_WEEKS = 8
    }
}
