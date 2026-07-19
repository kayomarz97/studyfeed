package dev.kayomarz.studyfeed.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dev.kayomarz.studyfeed.pack.PackRegistry
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import dev.kayomarz.studyfeed.progress.Setting
import dev.kayomarz.studyfeed.progress.SettingsKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The A7 Settings screen state. Reads/writes the `settings` table the feed engine consumes:
 * the editable **deadline** (`feed.deadline`), the **re-drill N** (`redrill.n`), and the **feed
 * intensity** preset (mapped to `feed.newRatioPct` + `feed.dueBurst`). Writes are immediate; the feed
 * picks them up via `FeedViewModel.reapplySettings()` when the user returns.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    /** Feed intensity presets → the engine's newRatioPct + dueBurst knobs. */
    enum class Intensity(val newRatioPct: Int, val dueBurst: Int, val label: String) {
        GENTLE(20, 30, "Gentle"),
        BALANCED(30, 60, "Balanced"),
        INTENSE(45, 120, "Intense"),
    }

    data class Ui(
        val loading: Boolean = true,
        val hasPack: Boolean = true,
        val deadline: LocalDate? = null,
        val redrillN: Int = 3,
        val intensity: Intensity = Intensity.BALANCED,
        val advanceAfterRating: Boolean = true,
        val readerScale: Float = 1f,
        val keepScreenOn: Boolean = false,
        val haptics: Boolean = true,
        /** A transient confirmation shown after a reset, then dismissed. */
        val message: String? = null,
    )

    private val registry = PackRegistry(app)
    private var examCode: String? = null

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val pack = withContext(Dispatchers.IO) { registry.activePack() }
        if (pack == null) { _ui.value = Ui(loading = false, hasPack = false); return@launch }
        examCode = pack.examCode
        val ui = withContext(Dispatchers.IO) {
            ProgressDatabase.ensureExists(getApplication(), pack.examCode)
            val db = ProgressDatabase.open(getApplication(), pack.examCode)
            val dao = db.dao()
            val deadline = dao.setting(SettingsKeys.DEADLINE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val redrill = dao.setting(SettingsKeys.REDRILL_N)?.toIntOrNull()?.coerceIn(1, 10) ?: 3
            val burst = dao.setting(SettingsKeys.DUE_BURST)?.toIntOrNull()
            val advance = dao.setting(SettingsKeys.ADVANCE_AFTER_RATING)?.toBooleanStrictOrNull() ?: true
            val readerScale = dao.setting(SettingsKeys.READER_TEXT_SCALE)?.toFloatOrNull()?.coerceIn(0.8f, 1.6f) ?: 1f
            val keepOn = dao.setting(SettingsKeys.KEEP_SCREEN_ON)?.toBooleanStrictOrNull() ?: false
            val haptics = dao.setting(SettingsKeys.HAPTICS)?.toBooleanStrictOrNull() ?: true
            db.close()
            Ui(loading = false, hasPack = true, deadline = deadline, redrillN = redrill,
                intensity = intensityFor(burst), advanceAfterRating = advance,
                readerScale = readerScale, keepScreenOn = keepOn, haptics = haptics)
        }
        _ui.value = ui
    }

    private fun intensityFor(dueBurst: Int?): Intensity =
        Intensity.entries.firstOrNull { it.dueBurst == dueBurst } ?: Intensity.BALANCED

    fun setDeadline(date: LocalDate) {
        _ui.value = _ui.value.copy(deadline = date)
        put(SettingsKeys.DEADLINE, date.toString())
    }

    fun setRedrillN(n: Int) {
        val v = n.coerceIn(1, 10)
        _ui.value = _ui.value.copy(redrillN = v)
        put(SettingsKeys.REDRILL_N, v.toString())
    }

    fun setIntensity(intensity: Intensity) {
        _ui.value = _ui.value.copy(intensity = intensity)
        put(SettingsKeys.NEW_RATIO_PCT, intensity.newRatioPct.toString())
        put(SettingsKeys.DUE_BURST, intensity.dueBurst.toString())
    }

    fun setAdvanceAfterRating(on: Boolean) {
        _ui.value = _ui.value.copy(advanceAfterRating = on)
        put(SettingsKeys.ADVANCE_AFTER_RATING, on.toString())
    }

    fun setReaderScale(scale: Float) {
        val v = scale.coerceIn(0.8f, 1.6f)
        _ui.value = _ui.value.copy(readerScale = v)
        put(SettingsKeys.READER_TEXT_SCALE, v.toString())
    }

    fun setKeepScreenOn(on: Boolean) {
        _ui.value = _ui.value.copy(keepScreenOn = on)
        put(SettingsKeys.KEEP_SCREEN_ON, on.toString())
    }

    fun setHaptics(on: Boolean) {
        _ui.value = _ui.value.copy(haptics = on)
        put(SettingsKeys.HAPTICS, on.toString())
    }

    /** What a reset clears — the user picks exactly one (their request: reset must be fine-grained). */
    enum class ResetKind(val label: String, val confirm: String) {
        FEED_POSITIONS("Feed positions & shuffle",
            "Every feed starts from the top again and reshuffles. Your reviews, mistakes and stars are kept."),
        STUDY_HISTORY("Study history & schedule",
            "Clears what you've studied and the spaced-repetition schedule — every card becomes new again. Stars and notes are kept."),
        MISTAKES("Mistakes", "Empties your mistakes pile."),
        STARS("Stars, tags & highlights", "Removes all your stars, tags and line highlights."),
        EVERYTHING("Everything",
            "Wipes ALL of your progress for this exam. The pack itself is untouched. This cannot be undone."),
    }

    /**
     * Run a reset (rows only — never drops a table), then [onDone] on the main thread so the caller can
     * refresh the feed/dashboard. A multi-table reset runs in one transaction, so it is all-or-nothing.
     */
    fun reset(kind: ResetKind, onDone: () -> Unit = {}) = viewModelScope.launch {
        val code = examCode ?: run { onDone(); return@launch }
        withContext(Dispatchers.IO) {
            val db = ProgressDatabase.open(getApplication(), code)
            try {
                val dao = db.dao()
                when (kind) {
                    ResetKind.FEED_POSITIONS -> dao.clearFeedState()
                    ResetKind.MISTAKES -> dao.clearMistakes()
                    ResetKind.STUDY_HISTORY -> db.withTransaction {
                        dao.clearFsrsCards(); dao.clearReviews(); dao.clearBlobReads(); dao.clearMcqAttempts()
                    }
                    ResetKind.STARS -> db.withTransaction {
                        dao.clearStars(); dao.clearItemTags(); dao.clearLineMarks()
                    }
                    ResetKind.EVERYTHING -> db.withTransaction {
                        dao.clearFsrsCards(); dao.clearReviews(); dao.clearBlobReads(); dao.clearMcqAttempts()
                        dao.clearMistakes(); dao.clearStars(); dao.clearItemTags(); dao.clearLineMarks()
                        dao.clearFeedState(); dao.clearSessions(); dao.clearDecks(); dao.clearSubjectMastery()
                    }
                }
            } finally { db.close() }
        }
        _ui.value = _ui.value.copy(message = "Reset done: ${kind.label.lowercase()}.")
        onDone()
    }

    fun dismissMessage() { _ui.value = _ui.value.copy(message = null) }

    private fun put(key: String, value: String) = viewModelScope.launch(Dispatchers.IO) {
        val code = examCode ?: return@launch
        val db = ProgressDatabase.open(getApplication(), code)
        db.dao().putSetting(Setting(key, value))
        db.close()
    }
}
