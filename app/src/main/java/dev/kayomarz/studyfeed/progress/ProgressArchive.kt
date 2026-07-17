package dev.kayomarz.studyfeed.progress

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * §11e — export/import the user's progress as a ZIP (BUILD_PROMPT §11e revision; plan step 8).
 *
 * ## Why this is A1 scope and not a Settings nicety for later
 *
 * `allowBackup=false` (A0) means Android's Auto Backup will never copy this data anywhere — a deliberate
 * choice, because Auto Backup would ship study data to Google Drive, contradicting §0's fully-offline
 * stance. And per the PLAYBOOK's research, Auto Backup **does not survive a signing-key change** anyway,
 * so it was never insurance against the §11d keystore disaster it was proposed for.
 *
 * That leaves this file as **the only protection the user's revision history has**. Lose the phone, lose
 * the keystore, or uninstall the app, and without an export the progress is simply gone. The pack is
 * regenerable; six weeks of a physician's revision is not. The "safe to defer" argument died the moment
 * a real 32,957-item pack existed to study.
 *
 * ## Why a ZIP rather than copying progress.db
 *
 * PATCH 01: notes carry images, and images live on the filesystem beside the DB, not inside it. A plain
 * single-file copy of `progress.db` would silently lose every note image — the user would restore, see
 * their notes, and only discover the missing pictures later. The archive carries both.
 *
 * Per-pack, exactly like the progress DB itself: an export belongs to one `exam_code`.
 */
class ProgressArchive(private val context: Context) {

    sealed interface Result {
        data class Ok(val entries: Int, val bytes: Long) : Result
        data class Failed(val message: String, val cause: Throwable? = null) : Result
    }

    /**
     * Write `progress_<exam>.db` + `note_images/` into [out] as a ZIP.
     *
     * Room's WAL means recent writes may still live in `-wal`, so those sidecars are archived too —
     * without them a restore silently loses the most recent reviews, which are the ones the user most
     * expects to be there.
     */
    fun export(examCode: String, out: OutputStream): Result {
        val db = ProgressDatabase.fileFor(context, examCode)
        if (!db.isFile) return Result.Failed("There's no progress saved for this exam yet.")

        var entries = 0
        var bytes = 0L
        return try {
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write("""{"exam_code":"$examCode","format":$FORMAT_VERSION}""".toByteArray())
                zip.closeEntry()
                entries++

                // The DB and its WAL sidecars. -wal/-shm are absent on a checkpointed DB; that is normal.
                for (f in listOf(db, File("${db.path}-wal"), File("${db.path}-shm"))) {
                    if (!f.isFile) continue
                    zip.putNextEntry(ZipEntry("$DB_DIR/${f.name}"))
                    bytes += f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    entries++
                }

                val images = noteImagesDir(examCode)
                images.listFiles()?.filter { it.isFile }?.forEach { img ->
                    zip.putNextEntry(ZipEntry("$IMAGES_DIR/${img.name}"))
                    bytes += img.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    entries++
                }
            }
            Result.Ok(entries, bytes)
        } catch (e: Exception) {
            Result.Failed("Could not export progress: ${e.message ?: e::class.simpleName}", e)
        }
    }

    /**
     * Restore an export produced by [export].
     *
     * **Zip-slip is defended here, and it is not theoretical.** This unpacks a *user-supplied* archive:
     * an entry named `../../databases/something.db` would otherwise write outside the destination and let
     * a malicious file overwrite arbitrary app files. Google publishes this exact mitigation
     * (canonical-path containment); it is applied to **every** entry before a single byte is written.
     *
     * Restore is all-or-nothing into a staging dir, then swapped in: a failure halfway through must not
     * leave the user with neither their old progress nor their new.
     */
    fun import(examCode: String, input: InputStream): Result {
        val staging = File(context.cacheDir, "restore-${System.nanoTime()}").apply { mkdirs() }
        var entries = 0
        var bytes = 0L
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry: ZipEntry = zip.nextEntry ?: break

                    // ── zip-slip guard — see isWithin(). Applied BEFORE any byte is written. ────
                    if (!isWithin(staging, entry.name)) return Result.Failed(UNSAFE_MESSAGE)
                    // ────────────────────────────────────────────────────────────────────────────

                    val target = File(staging, entry.name)
                    if (entry.isDirectory) { target.mkdirs(); zip.closeEntry(); continue }
                    target.parentFile?.mkdirs()
                    bytes += target.outputStream().use { zip.copyTo(it) }
                    entries++
                    zip.closeEntry()
                }
            }

            val restoredDb = File(staging, "$DB_DIR/${ProgressDatabase.fileName(examCode)}")
            if (!restoredDb.isFile) {
                return Result.Failed(
                    "This file doesn't contain progress for this exam. Exports are per-exam — check you " +
                        "picked the right one."
                )
            }

            // Swap in. Sidecars are removed first: a stale -wal beside a restored DB would let SQLite
            // replay the OLD database's tail over the NEW one — silent, and catastrophic for the user.
            val dest = ProgressDatabase.fileFor(context, examCode)
            dest.parentFile?.mkdirs()
            listOf("${dest.path}-wal", "${dest.path}-shm").forEach { File(it).delete() }
            restoredDb.copyTo(dest, overwrite = true)
            File(staging, "$DB_DIR/${dest.name}-wal").takeIf { it.isFile }?.copyTo(File("${dest.path}-wal"), true)
            File(staging, "$DB_DIR/${dest.name}-shm").takeIf { it.isFile }?.copyTo(File("${dest.path}-shm"), true)

            val imagesSrc = File(staging, IMAGES_DIR)
            if (imagesSrc.isDirectory) {
                val imagesDest = noteImagesDir(examCode).apply { mkdirs() }
                imagesSrc.listFiles()?.forEach { it.copyTo(File(imagesDest, it.name), overwrite = true) }
            }
            return Result.Ok(entries, bytes)
        } catch (e: Exception) {
            return Result.Failed("Could not import progress: ${e.message ?: e::class.simpleName}", e)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Where note images live. Per-exam, beside the progress they belong to. */
    fun noteImagesDir(examCode: String): File =
        File(context.filesDir, "$NOTE_IMAGES_ROOT/$examCode").apply { mkdirs() }

    /** A dated, exam-qualified filename — so a user with several exports can tell them apart. */
    fun suggestedFileName(examCode: String, today: String): String = "studyfeed-progress-$examCode-$today.zip"

    companion object {
        private const val MANIFEST_ENTRY = "export.json"
        private const val DB_DIR = "db"
        private const val IMAGES_DIR = "note_images"
        private const val NOTE_IMAGES_ROOT = "note_images"
        private const val FORMAT_VERSION = 1

        const val UNSAFE_MESSAGE =
            "This progress file is not safe to import — it tries to write outside the app's own " +
                "storage. It may have been tampered with."

        /**
         * **The zip-slip guard** — Google's official mitigation (canonical-path containment), and the
         * only thing standing between a tampered export and arbitrary app files being overwritten.
         *
         * Kept as a pure, visible function for one specific reason. **Modern Android already rejects
         * `..` entries itself** — its `ZipInputStream` throws *"Invalid zip entry path"* before this
         * code is reached (verified on API 36, 2026-07-17). That is welcome defence in depth, but it
         * makes this guard **untestable through the public import path on a new device**: the platform
         * catches the attack first, so the guard would silently become dead code that has never once
         * executed, and nobody would notice if it were broken.
         *
         * **It must not be deleted as redundant.** `minSdk` is **26** — this app runs on Android
         * versions that predate the platform's own check, and on those devices this function is the
         * *only* protection. So it stays, and it is tested directly (`ProgressArchiveGuardTest`, a JVM
         * test) rather than through an import the platform would intercept.
         *
         * @return true only if [entryName] resolves to a location genuinely inside [destination].
         */
        fun isWithin(destination: File, entryName: String): Boolean = try {
            val canonicalDest = destination.canonicalPath + File.separator
            val resolved = File(destination, entryName).canonicalPath
            // startsWith on the canonical path with a trailing separator: "/data/x" must not match
            // "/data/xyz", and any ".." has already been collapsed by canonicalisation.
            resolved.startsWith(canonicalDest)
        } catch (e: Exception) {
            false // an unresolvable path is not a path we may write to
        }
    }
}
