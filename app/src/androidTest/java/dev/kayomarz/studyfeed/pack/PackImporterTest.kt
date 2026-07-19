package dev.kayomarz.studyfeed.pack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Instrumented tests for [PackImporter], driven through [FilePackSource].
 *
 * **Why a fake source rather than SAF:** a `content://` tree URI requires a human tapping a file picker,
 * so the paths that actually matter — a corrupted pack, a cancelled import, a full disk, the atomic
 * rename — could otherwise only ever be tested by hand, which means never. [PackSource] exists so they
 * can be driven here. The SAF-specific part left untested is one `DocumentsContract` query in
 * [SafPackSource]; everything downstream of it is exercised.
 *
 * The invariant most of these defend: **an interrupted import must leave NOTHING that looks importable.**
 * A half-copied 141 MB database that appears installed is worse than a clean failure, because the user
 * would find out at the worst possible moment — mid-study, or mid-exam-prep.
 */
@RunWith(AndroidJUnit4::class)
class PackImporterTest {

    private lateinit var ctx: Context
    private lateinit var srcDir: File
    private lateinit var importer: PackImporter

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        importer = PackImporter(ctx)
        importer.packsRoot().deleteRecursively()
        srcDir = File(ctx.cacheDir, "src").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        srcDir.deleteRecursively()
        importer.packsRoot().deleteRecursively()
    }

    /** Builds a valid, self-consistent pack directory: db + manifest.json (+ optional figures). */
    private fun buildSource(
        examCode: String = "TEST_EXAM_A",
        schemaVersion: String = "1",
        userVersion: Int = 1,
        figures: Int = 0,
        corruptChecksum: Boolean = false,
        padDbBytes: Int = 0,
        full: Boolean = true,
    ): File {
        val db = File(srcDir, "pack_$examCode.db")
        SQLiteDatabase.openOrCreateDatabase(db, null).use { d ->
            d.execSQL("PRAGMA user_version = $userVersion")
            d.execSQL("""CREATE TABLE pack_manifest (schema_version TEXT, pack_version TEXT,
                exam_code TEXT, exam_name TEXT, built_at TEXT, checksum TEXT,
                feature_flags_json TEXT, counts_json TEXT, importance_weights_json TEXT,
                importance_histogram_json TEXT)""")
            d.execSQL("CREATE TABLE subjects (id INTEGER PRIMARY KEY, name TEXT, blueprint_weight REAL, ord INTEGER)")
            d.execSQL("CREATE TABLE tag_defs (id INTEGER PRIMARY KEY, key TEXT, label TEXT, color TEXT, description TEXT, ord INTEGER)")
            for (t in listOf("flashcards", "reels", "text_blobs", "interlink_quizzes", "figures",
                "topic_pages", "concept_edges", "mcqs")) {
                d.execSQL("CREATE TABLE $t (id INTEGER PRIMARY KEY)")
                if (full) d.execSQL("INSERT INTO $t (id) VALUES (1)")
            }
            d.execSQL("INSERT INTO pack_manifest VALUES (?,?,?,?,?,?,?,?,?,?)", arrayOf(
                schemaVersion, "0.1.0", examCode, "Exam $examCode", "2026-07-17T00:00:00Z",
                "CONTENT-checksum-over-stable-keys-NOT-the-file-hash",
                """{"has_blobs":$full,"has_figures":$full}""", """{"feed_items":7}""", "{}", "{}"))
            d.execSQL("INSERT INTO subjects VALUES (1,'Subject One',14.0,1)")
            d.execSQL("INSERT INTO tag_defs VALUES (1,'high_yield','High yield','#F00','',1)")
        }
        // Make the db big enough that a cancel can land mid-copy rather than after it.
        if (padDbBytes > 0) db.appendBytes(ByteArray(padDbBytes) { (it % 251).toByte() })

        val fileHash = MessageDigest.getInstance("SHA-256").digest(db.readBytes())
            .joinToString("") { "%02x".format(it) }

        File(srcDir, "manifest.json").writeText(
            """{"schema_version":"$schemaVersion","pack_version":"0.1.0","exam_code":"$examCode",
               "exam_name":"Exam $examCode",
               "checksum":"${if (corruptChecksum) "0".repeat(64) else fileHash}"}""".trimIndent()
        )
        if (figures > 0) {
            File(srcDir, "figures").mkdirs()
            repeat(figures) { i -> File(srcDir, "figures/fig_$i.png").writeBytes(ByteArray(2048) { i.toByte() }) }
        }
        return srcDir
    }

    // ---- the happy path -----------------------------------------------------------------------------

    @Test
    fun a_valid_pack_imports_and_lands_under_its_exam_code_from_the_manifest() = runBlocking {
        val result = importer.import(FilePackSource(buildSource(figures = 3)))

        assertTrue("import should succeed: $result", result is PackImporter.Result.Ok)
        result as PackImporter.Result.Ok
        // Identity from pack_manifest, NEVER the filename (D3).
        assertEquals("TEST_EXAM_A", result.manifest.examCode)
        assertEquals(File(importer.packsRoot(), "TEST_EXAM_A"), result.installedAt)
        assertTrue(File(result.installedAt, "pack_TEST_EXAM_A.db").isFile)
        assertTrue(File(result.installedAt, "manifest.json").isFile)
        assertEquals(3, File(result.installedAt, "figures").listFiles()?.size)
        assertEquals(listOf("TEST_EXAM_A"), importer.installedExamCodes())
    }

    @Test
    fun a_pack_with_no_figures_directory_is_normal_not_an_error() = runBlocking {
        // A future pack may legitimately ship without images (D5). Absent != broken.
        val result = importer.import(FilePackSource(buildSource(figures = 0, full = false)))
        assertTrue("a pack without figures must import fine: $result", result is PackImporter.Result.Ok)
        assertFalse(File((result as PackImporter.Result.Ok).installedAt, "figures").exists())
    }

    @Test
    fun two_different_exams_coexist_which_is_what_switch_exam_needs() = runBlocking {
        importer.import(FilePackSource(buildSource(examCode = "EXAM_ONE")))
        srcDir.deleteRecursively(); srcDir.mkdirs()
        importer.import(FilePackSource(buildSource(examCode = "EXAM_TWO")))

        assertEquals(listOf("EXAM_ONE", "EXAM_TWO"), importer.installedExamCodes())
    }

    @Test
    fun reimporting_the_same_exam_replaces_its_content_wholesale() = runBlocking {
        // SPEC.md §2: content is "replaced wholesale on re-import". Progress lives in a separate DB and
        // is untouched by this — that separation is the entire point of the two-DB design.
        importer.import(FilePackSource(buildSource(examCode = "EXAM_ONE")))
        val stray = File(importer.packsRoot(), "EXAM_ONE/stale-leftover.txt").apply { writeText("old") }
        assertTrue(stray.exists())

        srcDir.deleteRecursively(); srcDir.mkdirs()
        val again = importer.import(FilePackSource(buildSource(examCode = "EXAM_ONE")))

        assertTrue(again is PackImporter.Result.Ok)
        assertFalse("re-import must not leave stale files behind", stray.exists())
        assertEquals(listOf("EXAM_ONE"), importer.installedExamCodes())
    }

    // ---- refusals: nothing may be installed -------------------------------------------------------

    @Test
    fun a_corrupted_pack_is_refused_by_checksum_and_installs_nothing() = runBlocking {
        // THE case this check exists for: a truncated/damaged transfer. Without it, corruption surfaces
        // much later as inexplicable SQLite errors, long after the cause is forgettable.
        val result = importer.import(FilePackSource(buildSource(corruptChecksum = true)))

        assertTrue(result is PackImporter.Result.Failed)
        assertTrue("must say it's damaged, in the user's terms",
            (result as PackImporter.Result.Failed).message.contains("damaged", ignoreCase = true))
        assertEquals("nothing may be installed", emptyList<String>(), importer.installedExamCodes())
        assertNoStagingLeft()
    }

    @Test
    fun a_newer_schema_is_refused_before_the_370MB_copy_not_after() = runBlocking {
        // manifest.json is read FIRST precisely so an unplayable pack costs zero copying.
        val result = importer.import(FilePackSource(buildSource(schemaVersion = "99", userVersion = 99)))

        assertTrue(result is PackImporter.Result.Failed)
        val msg = (result as PackImporter.Result.Failed).message
        assertTrue("must name the pack's version", msg.contains("99"))
        assertTrue("must name what we support", msg.contains("1"))
        assertEquals(emptyList<String>(), importer.installedExamCodes())
        assertNoStagingLeft()
    }

    @Test
    fun a_folder_with_no_manifest_is_refused_with_a_message_that_says_what_to_do() = runBlocking {
        File(srcDir, "pack_X.db").writeText("not really a db")
        val result = importer.import(FilePackSource(srcDir))

        assertTrue(result is PackImporter.Result.Failed)
        // The realistic user error is picking the wrong folder — the message must help them fix it.
        assertTrue((result as PackImporter.Result.Failed).message.contains("manifest.json"))
        assertNoStagingLeft()
    }

    @Test
    fun a_folder_with_a_manifest_but_no_database_is_refused() = runBlocking {
        File(srcDir, "manifest.json").writeText("""{"schema_version":"1","checksum":"x","exam_code":"A"}""")
        val result = importer.import(FilePackSource(srcDir))
        assertTrue(result is PackImporter.Result.Failed)
        assertNoStagingLeft()
    }

    // ---- cancellation: the invariant that matters most --------------------------------------------

    @Test
    fun cancelling_mid_copy_leaves_nothing_installed_and_no_staging_behind() = runBlocking {
        // The user backgrounds the app 60% through a 370MB import. There must be no trace: a half-pack
        // that LOOKS importable is worse than no pack, because it fails later and mysteriously.
        val source = FilePackSource(buildSource(padDbBytes = 6 shl 20)) // ~6MB, enough to cancel inside
        val started = CompletableDeferred<Unit>()

        val job = async(Dispatchers.IO) {
            importer.import(source) { p ->
                if (p.phase == PackImporter.Phase.COPYING_DATABASE && p.bytesCopied > 0) {
                    started.complete(Unit)
                }
            }
        }
        started.await()
        job.cancel()
        runCatching { job.await() } // cancellation propagates; we only care about the aftermath

        withContext(Dispatchers.IO) {
            assertEquals("a cancelled import must install nothing",
                emptyList<String>(), importer.installedExamCodes())
            assertNoStagingLeft()
        }
    }

    @Test
    fun progress_is_reported_and_reaches_the_end() = runBlocking {
        // Without progress a multi-minute copy is indistinguishable from a hang.
        val seen = mutableListOf<PackImporter.Progress>()
        val result = importer.import(FilePackSource(buildSource(figures = 2, padDbBytes = 2 shl 20))) {
            seen += it
        }

        assertTrue(result is PackImporter.Result.Ok)
        assertTrue("must report copying the database", seen.any { it.phase == PackImporter.Phase.COPYING_DATABASE })
        assertTrue("must report verifying", seen.any { it.phase == PackImporter.Phase.VERIFYING })
        assertTrue("must report copying figures", seen.any { it.phase == PackImporter.Phase.COPYING_FIGURES })
        assertTrue("must finish", seen.any { it.phase == PackImporter.Phase.FINALISING })
        assertTrue("fractions must stay sane", seen.all { it.fraction in 0f..1f })
    }

    /** No `.importing-*` directory may survive any outcome — success, refusal, or cancellation. */
    private fun assertNoStagingLeft() {
        val leftovers = importer.packsRoot().listFiles()?.filter { it.name.startsWith(".importing") }.orEmpty()
        assertEquals("staging dirs must never survive: $leftovers", emptyList<File>(), leftovers)
    }
}
