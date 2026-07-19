package dev.kayomarz.studyfeed.pack

import android.content.Context
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import java.io.File

/**
 * Which packs are installed, and which one is being studied (BUILD_PROMPT §8 A1: *"'Switch exam' screen
 * lists imported packs"*; spec/SPEC.md §2: *"the app keeps a registry of imported packs to list/switch
 * exams"*).
 *
 * **The list is derived from the filesystem, not stored.** A stored list is a second source of truth
 * that drifts: delete a pack directory and a stored registry still advertises it; a crash mid-import
 * and it lists a pack that never landed. The directories under `filesDir/packs/` **are** the registry,
 * so it cannot disagree with reality. Only the *active* exam — which is genuinely app state, not a fact
 * about the disk — is persisted, and even that is validated on read.
 *
 * **Every pack is described by reading its own manifest**, so a "switch exam" list is built entirely
 * from pack data. Nothing here knows an exam exists until it finds one on disk (§0 `multi_pack`).
 */
class PackRegistry(private val context: Context) {

    /** One row of the switch-exam screen. Everything user-visible here came out of the pack. */
    data class InstalledPack(
        val examCode: String,
        val examName: String,
        val packVersion: String,
        val dir: File,
        val dbFile: File,
        /** True once the user has any progress for this exam — i.e. it is worth protecting (§11e). */
        val hasProgress: Boolean,
    )

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun packsRoot(): File = File(context.filesDir, PACKS_DIR).apply { mkdirs() }

    /**
     * Installed packs, newest-listed-first by exam name.
     *
     * Reads each pack's manifest rather than trusting the directory name: the directory is named after
     * `exam_code`, but the *label* must come from `pack_manifest` (D3 — identity is never a filename).
     * A directory whose DB won't open is skipped, not crashed on: a half-deleted or corrupted pack
     * should make the others unusable.
     */
    fun installed(): List<InstalledPack> = packsRoot().listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.mapNotNull { dir -> describe(dir) }
        ?.sortedBy { it.examName.lowercase() }
        .orEmpty()

    private fun describe(dir: File): InstalledPack? {
        val db = dir.listFiles { f -> f.name.startsWith("pack_") && f.name.endsWith(".db") }
            ?.firstOrNull() ?: return null
        val loaded = (PackDatabase.open(db) as? PackDatabase.Result.Ok)?.pack ?: return null
        return InstalledPack(
            examCode = loaded.manifest.examCode,
            examName = loaded.manifest.examName,
            packVersion = loaded.manifest.packVersion,
            dir = dir,
            dbFile = db,
            hasProgress = ProgressDatabase.fileFor(context, loaded.manifest.examCode).isFile,
        )
    }

    /**
     * The exam currently being studied, or null when nothing is imported yet.
     *
     * Validated against the disk on every read: a stored code whose pack has since been deleted must not
     * be returned, or the app would try to open a pack that isn't there. Falls back to the only
     * installed pack when there is exactly one — the overwhelmingly common case, and asking the user to
     * "choose" between one option is a worse experience than just opening it.
     */
    fun activePack(): InstalledPack? {
        val all = installed()
        if (all.isEmpty()) return null
        val stored = prefs.getString(KEY_ACTIVE, null)
        return all.firstOrNull { it.examCode == stored } ?: all.singleOrNull()
    }

    /** Switch exams. Progress is per-`exam_code`, so this changes what is shown and touches no data. */
    fun setActive(examCode: String) {
        prefs.edit().putString(KEY_ACTIVE, examCode).apply()
    }

    /**
     * Delete a pack's CONTENT, deliberately leaving its progress intact.
     *
     * **This asymmetry is the whole point of the two-DB design** (SPEC.md §2). A pack is 370 MB of
     * regenerable content; progress is the user's own irreplaceable history. Removing a pack to reclaim
     * space must never cost revision history — re-import the same `exam_code` later and the progress is
     * still there, matched by `(item_type, item_id)`.
     *
     * Deleting progress is a separate, explicit act, and A7 owns its confirmation UI.
     */
    fun deletePackContent(examCode: String): Boolean {
        val pack = installed().firstOrNull { it.examCode == examCode } ?: return false
        val ok = pack.dir.deleteRecursively()
        if (prefs.getString(KEY_ACTIVE, null) == examCode) prefs.edit().remove(KEY_ACTIVE).apply()
        return ok
    }

    private companion object {
        const val PACKS_DIR = "packs"
        const val PREFS = "studyfeed.registry"
        const val KEY_ACTIVE = "active_exam_code"
    }
}
