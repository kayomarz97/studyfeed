package dev.kayomarz.studyfeed.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
            db.close()
            Ui(loading = false, hasPack = true, deadline = deadline, redrillN = redrill,
                intensity = intensityFor(burst), advanceAfterRating = advance)
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

    private fun put(key: String, value: String) = viewModelScope.launch(Dispatchers.IO) {
        val code = examCode ?: return@launch
        val db = ProgressDatabase.open(getApplication(), code)
        db.dao().putSetting(Setting(key, value))
        db.close()
    }
}
