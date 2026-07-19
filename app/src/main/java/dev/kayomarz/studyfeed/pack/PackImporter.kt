package dev.kayomarz.studyfeed.pack

import android.content.Context
import android.os.storage.StorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Imports a content pack into app-private storage (BUILD_PROMPT §8 A1; plan D2, D3).
 *
 * **Why importing means copying ~370 MB:** SQLite cannot open a `content://` URI — no
 * `SQLiteDatabase.openDatabase` overload accepts one, only `String`/`File`. So a pack the user merely
 * *pointed at* through the picker has to be physically brought into storage we own. That is not a
 * preference; it is the API.
 *
 * **Destination is `filesDir` (D2).** The pack derives from copyrighted sources, and app-private
 * storage is the only location other apps cannot read.
 *
 * **No background work, by construction (§5/§12).** This is a plain suspending function: it runs while
 * the user is looking at it and dies when they leave. It must never become a Service or WorkManager job
 * — A0's guard fails the build if anyone tries, which is the guard working, not an obstacle.
 *
 * **Identity comes from `pack_manifest`, never a filename (D3).** "same filename ≠ same file" is already
 * in this repo's mistakes ledger from C1, where it misclassified an entire source drop.
 */
class PackImporter(private val context: Context) {

    /** Progress for the UI. A multi-minute copy with no feedback is indistinguishable from a hang. */
    data class Progress(val phase: Phase, val bytesCopied: Long, val bytesTotal: Long, val fileLabel: String) {
        val fraction: Float get() = if (bytesTotal > 0) (bytesCopied.toFloat() / bytesTotal) else 0f
    }

    enum class Phase { READING_MANIFEST, CHECKING_SPACE, COPYING_DATABASE, VERIFYING, COPYING_FIGURES, FINALISING }

    sealed interface Result {
        data class Ok(val manifest: PackManifest, val installedAt: File) : Result
        /** Refused. [message] is user-facing and explains what to do, not just what broke. */
        data class Failed(val message: String, val cause: Throwable? = null) : Result
    }

    /**
     * Copy + verify + install a pack from [source].
     *
     * Ordering is deliberate and is most of the value in this function:
     *  1. **manifest.json first** (3.6 KB) — it declares `schema_version`, so a pack this app can never
     *     play is refused **before** copying 370 MB of it. A cheap pre-gate; the DB row stays
     *     authoritative (SPEC.md §3) and is checked at step 5.
     *  2. **space check before writing** — failing at 90% with "no space" is a cruel way to learn.
     *  3. **copy the DB (~135 MB)**, then
     *  4. **verify its sha256 against `manifest.json.checksum`** — the FILE hash. NOT
     *     `pack_manifest.checksum`, which is a content hash over stable_keys and would never match
     *     (§3.3 of the plan; the two are different values with the same name).
     *  5. **open + gate the real DB** — authoritative.
     *  6. **only then copy figures (~235 MB)** — no point moving a quarter-gigabyte of images for a
     *     pack we are about to reject.
     *  7. **atomic rename into place** — until this instant, nothing exists at the real path, so a
     *     crash/cancel/power-loss can never leave a half-pack that *looks* importable.
     */
    suspend fun import(source: PackSource, onProgress: (Progress) -> Unit = {}): Result {
        val staging = File(packsRoot(), ".importing-${System.nanoTime()}")
        try {
            // ---- 1. manifest.json: the cheap pre-gate -------------------------------------------
            onProgress(Progress(Phase.READING_MANIFEST, 0, 0, "manifest.json"))
            val manifestText = source.open(MANIFEST_NAME)?.use { it.readBytes().decodeToString() }
                ?: return Result.Failed(
                    "That folder doesn't look like a StudyFeed pack — it has no $MANIFEST_NAME. " +
                        "Pick the folder that contains the pack database and its manifest."
                )
            val fileManifest = parseFileManifest(manifestText)
                ?: return Result.Failed("This pack's $MANIFEST_NAME could not be read. It may be corrupt.")

            when (val pre = SchemaVersion.evaluate(fileManifest.schemaVersion, fileManifest.schemaVersion)) {
                is SchemaVersion.GateResult.TooNew -> return Result.Failed(pre.message)
                is SchemaVersion.GateResult.Malformed -> return Result.Failed(pre.message)
                is SchemaVersion.GateResult.Ok -> Unit
            }

            // Identity from the manifest, never the filename (D3).
            val dbName = source.list().firstOrNull { it.startsWith(DB_PREFIX) && it.endsWith(DB_SUFFIX) }
                ?: return Result.Failed(
                    "That folder has no pack database (a ${DB_PREFIX}*${DB_SUFFIX} file). " +
                        "Pick the folder that contains it."
                )

            // ---- 2. space ------------------------------------------------------------------------
            onProgress(Progress(Phase.CHECKING_SPACE, 0, 0, dbName))
            val figuresSource = source.child(FIGURES_DIR)
            val needed = estimateBytes(source, dbName, figuresSource)
            requireSpace(needed)?.let { return it }

            staging.mkdirs()

            // ---- 3. the database -----------------------------------------------------------------
            val dbTotal = source.sizeOf(dbName).coerceAtLeast(0)
            val stagedDb = File(staging, dbName)
            val digest = MessageDigest.getInstance("SHA-256")
            source.open(dbName)?.use { input ->
                copyStream(input, stagedDb, digest) { copied ->
                    onProgress(Progress(Phase.COPYING_DATABASE, copied, dbTotal, dbName))
                }
            } ?: return Result.Failed("Could not read $dbName from that folder.")

            // ---- 4. FILE hash vs manifest.json.checksum -------------------------------------------
            onProgress(Progress(Phase.VERIFYING, dbTotal, dbTotal, dbName))
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!fileManifest.checksum.equals(actual, ignoreCase = true)) {
                return Result.Failed(
                    "This pack appears to be damaged or incompletely copied — its contents don't match " +
                        "its own checksum. Copy it again and retry.\n\n" +
                        "expected ${fileManifest.checksum.take(16)}…\nactual   ${actual.take(16)}…"
                )
            }

            // ---- 5. the authoritative gate, on the real DB ----------------------------------------
            val loaded = when (val opened = PackDatabase.open(stagedDb)) {
                is PackDatabase.Result.Rejected -> return Result.Failed(opened.message, opened.cause)
                is PackDatabase.Result.Ok -> opened.pack
            }

            // ---- 6. figures — only now that the pack is known good --------------------------------
            if (figuresSource != null) {
                val names = figuresSource.list()
                val figTotal = names.sumOf { figuresSource.sizeOf(it).coerceAtLeast(0) }
                var done = 0L
                val figDir = File(staging, FIGURES_DIR).apply { mkdirs() }
                for ((i, name) in names.withIndex()) {
                    currentCoroutineContext().ensureActive() // cancellation lands between files
                    figuresSource.open(name)?.use { input ->
                        copyStream(input, File(figDir, name), null) { copied ->
                            onProgress(Progress(Phase.COPYING_FIGURES, done + copied, figTotal,
                                "${i + 1} of ${names.size}"))
                        }
                    }
                    done += figuresSource.sizeOf(name).coerceAtLeast(0)
                }
            }
            // No figures directory is NORMAL, not an error — a future pack may ship without images
            // (D5). Capability is resolved from the DB's rows, not from this directory's presence.

            // ---- 7. atomic rename -----------------------------------------------------------------
            onProgress(Progress(Phase.FINALISING, 1, 1, loaded.manifest.examName))
            File(staging, MANIFEST_NAME).writeText(manifestText)
            val dest = File(packsRoot(), loaded.manifest.examCode)
            if (dest.exists()) dest.deleteRecursively() // re-import replaces content wholesale (SPEC.md §2)
            if (!staging.renameTo(dest)) {
                return Result.Failed("Could not finish installing the pack — the app's storage may be full.")
            }
            return Result.Ok(loaded.manifest, dest)

        } catch (e: CancellationException) {
            staging.deleteRecursively() // never leave a half-pack that looks importable
            throw e
        } catch (e: Exception) {
            staging.deleteRecursively()
            return Result.Failed("Importing this pack failed: ${e.message ?: e::class.simpleName}", e)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** Where installed packs live. One directory per `exam_code` — that is what makes packs swappable. */
    fun packsRoot(): File = File(context.filesDir, PACKS_DIR).apply { mkdirs() }

    /** Installed packs, by `exam_code`. The registry behind the "switch exam" screen. */
    fun installedExamCodes(): List<String> =
        packsRoot().listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name }?.sorted()
            .orEmpty()

    private fun estimateBytes(source: PackSource, dbName: String, figures: PackSource?): Long {
        val db = source.sizeOf(dbName).coerceAtLeast(0)
        val figs = figures?.list()?.sumOf { figures.sizeOf(it).coerceAtLeast(0) } ?: 0L
        return db + figs
    }

    /**
     * Refuse politely, with a real number, before writing a byte.
     *
     * Uses `StorageManager.getAllocatableBytes()` (API 26+ — exactly our minSdk floor) rather than
     * `File.getUsableSpace()`: per Google's javadoc it returns a larger, truer figure because it counts
     * space reclaimable from other apps' caches. `getUsableSpace()` would refuse imports that would
     * actually have succeeded.
     */
    private fun requireSpace(needed: Long): Result.Failed? = try {
        val sm = context.getSystemService(StorageManager::class.java)
        val uuid = sm.getUuidForPath(context.filesDir)
        val allocatable = sm.getAllocatableBytes(uuid)
        val headroom = (needed * 1.1).toLong() // staging + rename needs slack, not an exact fit
        if (allocatable < headroom) {
            Result.Failed(
                "Not enough free space to import this pack. It needs about ${needed / 1_000_000} MB " +
                    "and there's about ${allocatable / 1_000_000} MB available. Free some space and retry."
            )
        } else null
    } catch (e: Exception) {
        null // If the OS won't tell us, proceed — a real ENOSPC surfaces as a normal failure below.
    }

    /** Copies with cancellation checks per chunk, hashing as it goes so the file is read only once. */
    private suspend fun copyStream(
        input: InputStream,
        dest: File,
        digest: MessageDigest?,
        onBytes: (Long) -> Unit,
    ) {
        var copied = 0L
        var sinceTick = 0L
        val buf = ByteArray(1 shl 16)
        dest.outputStream().use { out ->
            while (true) {
                currentCoroutineContext().ensureActive() // cancel lands within a chunk, not after a file
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                digest?.update(buf, 0, n)
                copied += n
                sinceTick += n
                if (sinceTick >= PROGRESS_TICK_BYTES) { onBytes(copied); sinceTick = 0 }
            }
            out.flush()
        }
        onBytes(copied)
    }

    private fun parseFileManifest(text: String): PackFileManifest? = try {
        val o = JSONObject(text)
        PackFileManifest(
            // manifest.json stores schema_version as a JSON STRING ("1"); pack_manifest stores TEXT;
            // PRAGMA user_version is an INT. Three representations — always parse (§3.3).
            schemaVersion = SchemaVersion.parse(o.optString("schema_version")) ?: return null,
            packVersion = o.optString("pack_version"),
            examCode = o.optString("exam_code"),
            examName = o.optString("exam_name"),
            // The FILE sha256 — the transfer-integrity hash. NOT pack_manifest.checksum.
            checksum = o.optString("checksum"),
        )
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val PACKS_DIR = "packs"
        const val MANIFEST_NAME = "manifest.json"
        const val FIGURES_DIR = "figures"
        const val DB_PREFIX = "pack_"
        const val DB_SUFFIX = ".db"
        const val PROGRESS_TICK_BYTES = 1L shl 20 // ~1MB: smooth enough to watch, cheap enough to ignore
    }
}
