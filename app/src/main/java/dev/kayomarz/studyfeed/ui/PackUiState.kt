package dev.kayomarz.studyfeed.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kayomarz.studyfeed.pack.PackCapabilities
import dev.kayomarz.studyfeed.pack.PackDatabase
import dev.kayomarz.studyfeed.pack.PackImporter
import dev.kayomarz.studyfeed.pack.PackRegistry
import dev.kayomarz.studyfeed.pack.SafPackSource
import dev.kayomarz.studyfeed.progress.FeedReader
import dev.kayomarz.studyfeed.progress.ProgressArchive
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for A1's screen: import a pack, see what it is, switch between exams, export/import progress.
 *
 * **Every user-visible label here originates in a pack** (§0 `multi_pack`, §8 A1). There is no exam name
 * in this file, no subject list, no tag — those are all read from `pack_manifest`/`subjects`/`tag_defs`
 * at runtime. That is what lets a future pack play on this same binary with no code change.
 *
 * **A ViewModel, so the import survives rotation but not the app.** That is the correct lifetime: §12
 * forbids background work, so a 370 MB copy must die when the user leaves. `viewModelScope` gives
 * exactly that — it outlives a configuration change and is cancelled when the screen is finished.
 */
class PackUiState(app: Application) : AndroidViewModel(app) {

    data class ActiveExam(
        val examCode: String,
        val examName: String,
        val packVersion: String,
        val subjects: List<PackDatabase.Subject>,
        val tags: List<PackDatabase.TagDef>,
        val capabilities: PackCapabilities,
        val feedSize: Int,
        val seenCount: Int,
    )

    data class Ui(
        val loading: Boolean = true,
        /** null = nothing imported yet. A normal state on a fresh install, not an error. */
        val active: ActiveExam? = null,
        val installed: List<PackRegistry.InstalledPack> = emptyList(),
        val importing: PackImporter.Progress? = null,
        val message: String? = null,
        val error: String? = null,
    )

    private val registry = PackRegistry(app)
    private val importer = PackImporter(app)
    private val archive = ProgressArchive(app)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var importJob: Job? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _ui.update { it.copy(loading = true) }
        val loaded = withContext(Dispatchers.IO) { readActive() }
        _ui.update {
            it.copy(loading = false, active = loaded, installed = withContextInstalled())
        }
    }

    private fun withContextInstalled(): List<PackRegistry.InstalledPack> =
        runCatching { registry.installed() }.getOrDefault(emptyList())

    private fun readActive(): ActiveExam? {
        val pack = registry.activePack() ?: return null
        val loaded = (PackDatabase.open(pack.dbFile) as? PackDatabase.Result.Ok)?.pack ?: return null
        // The ATTACH reader (D4) needs BOTH files to exist, and Room creates the progress file lazily —
        // so on a freshly imported pack there is nothing to attach and the feed would report 0 items
        // while holding tens of thousands. Create it up front; an empty progress DB is the correct
        // state for a pack nobody has studied yet.
        ProgressDatabase.ensureExists(getApplication(), pack.examCode)
        val progressFile = ProgressDatabase.fileFor(getApplication(), pack.examCode)
        var feedSize = 0
        var seen = 0
        FeedReader.open(progressFile, pack.dbFile)?.use { r ->
            feedSize = r.feedSize()
            seen = r.seenCount()
        }
        return ActiveExam(
            examCode = loaded.manifest.examCode,
            examName = loaded.manifest.examName,
            packVersion = loaded.manifest.packVersion,
            subjects = loaded.subjects,
            tags = loaded.tags,
            capabilities = loaded.capabilities,
            feedSize = feedSize,
            seenCount = seen,
        )
    }

    /** Import the pack directory the user picked. Cancellable; see [cancelImport]. */
    fun importFrom(treeUri: Uri) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _ui.update { it.copy(error = null, message = null) }
            val result = withContext(Dispatchers.IO) {
                importer.import(SafPackSource(getApplication(), treeUri)) { p ->
                    _ui.update { it.copy(importing = p) }
                }
            }
            _ui.update { it.copy(importing = null) }
            when (result) {
                is PackImporter.Result.Ok -> {
                    registry.setActive(result.manifest.examCode)
                    _ui.update { it.copy(message = "Imported ${result.manifest.examName}.") }
                    refresh()
                }
                is PackImporter.Result.Failed -> _ui.update { it.copy(error = result.message) }
            }
        }
    }

    /** The user backing out mid-copy is normal, not an error — and must leave nothing behind. */
    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _ui.update { it.copy(importing = null, message = "Import cancelled.") }
    }

    fun switchTo(examCode: String) {
        registry.setActive(examCode)
        refresh()
    }

    fun exportProgress(uri: Uri) = viewModelScope.launch {
        val code = _ui.value.active?.examCode ?: return@launch
        val result = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                archive.export(code, it)
            } ?: ProgressArchive.Result.Failed("Could not write to that location.")
        }
        when (result) {
            is ProgressArchive.Result.Ok ->
                _ui.update { it.copy(message = "Progress exported (${result.entries} files).") }
            is ProgressArchive.Result.Failed -> _ui.update { it.copy(error = result.message) }
        }
    }

    fun importProgress(uri: Uri) = viewModelScope.launch {
        val code = _ui.value.active?.examCode ?: return@launch
        val result = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                archive.import(code, it)
            } ?: ProgressArchive.Result.Failed("Could not read that file.")
        }
        when (result) {
            is ProgressArchive.Result.Ok -> {
                _ui.update { it.copy(message = "Progress restored.") }
                refresh()
            }
            is ProgressArchive.Result.Failed -> _ui.update { it.copy(error = result.message) }
        }
    }

    fun suggestedExportName(): String {
        val code = _ui.value.active?.examCode ?: "pack"
        // Date is formatted by the caller's clock; no exam-specific logic here.
        return archive.suggestedFileName(code, java.time.LocalDate.now().toString())
    }

    fun dismissMessage() = _ui.update { it.copy(message = null, error = null) }
}
