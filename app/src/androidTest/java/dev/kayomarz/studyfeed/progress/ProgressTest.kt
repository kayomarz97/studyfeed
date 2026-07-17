package dev.kayomarz.studyfeed.progress

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kayomarz.studyfeed.pack.FilePackSource
import dev.kayomarz.studyfeed.pack.PackImporter
import dev.kayomarz.studyfeed.pack.PackRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The progress DB, the ATTACH reader, the registry, and §11e — tested together, because the guarantee
 * they exist to provide is a property of the whole set, not of any one class.
 *
 * **The headline is [reimporting_a_pack_does_not_wipe_progress]** — the invariant that justifies the
 * entire two-database design (spec/SPEC.md §2). A pack is 370 MB of regenerable content; progress is the
 * user's irreplaceable revision history. If that test ever fails, the app has become capable of
 * destroying the only thing in it that cannot be rebuilt.
 */
@RunWith(AndroidJUnit4::class)
class ProgressTest {

    private lateinit var ctx: Context
    private lateinit var importer: PackImporter
    private lateinit var registry: PackRegistry
    private lateinit var srcDir: File
    private val exam = "TEST_EXAM_P"

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        importer = PackImporter(ctx)
        registry = PackRegistry(ctx)
        importer.packsRoot().deleteRecursively()
        ctx.getDatabasePath("x").parentFile?.listFiles()
            ?.filter { it.name.startsWith("progress_") }?.forEach { it.delete() }
        srcDir = File(ctx.cacheDir, "psrc").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        srcDir.deleteRecursively()
        importer.packsRoot().deleteRecursively()
    }

    private fun buildPack(examCode: String = exam, feedItems: Int = 5): File {
        srcDir.deleteRecursively(); srcDir.mkdirs()
        val db = File(srcDir, "pack_$examCode.db")
        SQLiteDatabase.openOrCreateDatabase(db, null).use { d ->
            d.execSQL("PRAGMA user_version = 1")
            d.execSQL("""CREATE TABLE pack_manifest (schema_version TEXT, pack_version TEXT,
                exam_code TEXT, exam_name TEXT, built_at TEXT, checksum TEXT,
                feature_flags_json TEXT, counts_json TEXT, importance_weights_json TEXT,
                importance_histogram_json TEXT)""")
            d.execSQL("CREATE TABLE subjects (id INTEGER PRIMARY KEY, name TEXT, blueprint_weight REAL, ord INTEGER)")
            d.execSQL("CREATE TABLE tag_defs (id INTEGER PRIMARY KEY, key TEXT, label TEXT, color TEXT, description TEXT, ord INTEGER)")
            for (t in listOf("flashcards", "reels", "text_blobs", "interlink_quizzes", "figures",
                "topic_pages", "concept_edges", "mcqs")) {
                d.execSQL("CREATE TABLE $t (id INTEGER PRIMARY KEY)"); d.execSQL("INSERT INTO $t (id) VALUES (1)")
            }
            d.execSQL("""CREATE TABLE feed_items (id INTEGER PRIMARY KEY, item_type TEXT, ref_id INTEGER,
                subject_id INTEGER, importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0,
                is_pyq INTEGER DEFAULT 0)""")
            repeat(feedItems) { i ->
                d.execSQL("INSERT INTO feed_items VALUES (${i + 1},'mcq',${i + 1},1,${(i % 5) + 1},0.${i}0,0)")
            }
            d.execSQL("INSERT INTO pack_manifest VALUES (?,?,?,?,?,?,?,?,?,?)", arrayOf(
                "1", "0.1.0", examCode, "Exam $examCode", "2026-07-17T00:00:00Z", "content-hash",
                """{"has_blobs":true}""", """{"feed_items":$feedItems}""", "{}", "{}"))
            d.execSQL("INSERT INTO subjects VALUES (1,'Subject One',14.0,1)")
            d.execSQL("INSERT INTO tag_defs VALUES (1,'high_yield','High yield','#F00','',1)")
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(db.readBytes())
            .joinToString("") { "%02x".format(it) }
        File(srcDir, "manifest.json").writeText(
            """{"schema_version":"1","pack_version":"0.1.0","exam_code":"$examCode",
               "exam_name":"Exam $examCode","checksum":"$hash"}"""
        )
        return srcDir
    }

    // ---- THE invariant -----------------------------------------------------------------------------

    @Test
    fun reimporting_a_pack_does_not_wipe_progress() = runBlocking {
        // SPEC.md §2, the reason content and progress are separate files at all. If this fails, the app
        // can destroy six weeks of revision to deliver a content update.
        importer.import(FilePackSource(buildPack()))

        val db = ProgressDatabase.open(ctx, exam)
        db.dao().upsertCard(FsrsCard("mcq", 1, 3.5, 5.0, "2026-08-01", "2026-07-17", 2, 4, 1))
        db.dao().insertReview(Review(item_type = "mcq", item_id = 1, rating = 3, ts = "2026-07-17", elapsed_ms = 1200))
        db.dao().star(Star("mcq", 1, "2026-07-17"))
        assertEquals(1, db.dao().cardCount())
        db.close()

        // A new content build of the SAME exam arrives — the everyday case.
        importer.import(FilePackSource(buildPack(feedItems = 9)))

        val after = ProgressDatabase.open(ctx, exam)
        assertEquals("progress must survive a re-import", 1, after.dao().cardCount())
        assertEquals(1, after.dao().reviewCount())
        assertEquals(1, after.dao().starCount())
        val card = after.dao().card("mcq", 1)
        assertNotNull(card)
        assertEquals(3.5, card!!.stability!!, 0.0001)   // the actual FSRS state, not just a row count
        assertEquals(4, card.reps)
        after.close()
    }

    @Test
    fun progress_is_namespaced_per_exam_so_a_second_exam_cannot_touch_the_first() = runBlocking {
        // What makes packs swappable without risk: importing Part 2 must not disturb Part 1.
        importer.import(FilePackSource(buildPack(examCode = "EXAM_A")))
        importer.import(FilePackSource(buildPack(examCode = "EXAM_B")))

        val a = ProgressDatabase.open(ctx, "EXAM_A")
        a.dao().upsertCard(FsrsCard("mcq", 1, 1.0, 1.0, null, null, 1, 1, 0))
        a.close()

        val b = ProgressDatabase.open(ctx, "EXAM_B")
        assertEquals("EXAM_B's progress must be its own", 0, b.dao().cardCount())
        b.dao().upsertCard(FsrsCard("mcq", 1, 9.9, 9.9, null, null, 1, 7, 0))
        b.close()

        val aAgain = ProgressDatabase.open(ctx, "EXAM_A")
        assertEquals(1, aAgain.dao().cardCount())
        assertEquals("EXAM_A's data must be untouched by EXAM_B", 1, aAgain.dao().card("mcq", 1)!!.reps)
        aAgain.close()
    }

    @Test
    fun deleting_a_packs_content_keeps_its_progress() = runBlocking {
        // Reclaiming 370MB must never cost revision history — the pack is regenerable, progress isn't.
        importer.import(FilePackSource(buildPack()))
        val db = ProgressDatabase.open(ctx, exam)
        db.dao().upsertCard(FsrsCard("mcq", 1, 2.0, 2.0, null, null, 1, 3, 0))
        db.close()

        assertTrue(registry.deletePackContent(exam))
        assertEquals(emptyList<String>(), importer.installedExamCodes())

        val after = ProgressDatabase.open(ctx, exam)
        assertEquals("progress must outlive the pack content", 1, after.dao().cardCount())
        after.close()
    }

    // ---- the registry ------------------------------------------------------------------------------

    @Test
    fun the_registry_lists_installed_packs_and_labels_them_from_their_own_manifest() = runBlocking {
        importer.import(FilePackSource(buildPack(examCode = "EXAM_A")))
        importer.import(FilePackSource(buildPack(examCode = "EXAM_B")))

        val all = registry.installed()
        assertEquals(2, all.size)
        // The label came out of pack_manifest, not out of the directory name (D3).
        assertEquals(listOf("Exam EXAM_A", "Exam EXAM_B"), all.map { it.examName })
    }

    @Test
    fun a_single_installed_pack_is_active_without_the_user_choosing() = runBlocking {
        importer.import(FilePackSource(buildPack(examCode = "ONLY_ONE")))
        assertEquals("ONLY_ONE", registry.activePack()?.examCode)
    }

    @Test
    fun switching_exams_changes_only_what_is_shown() = runBlocking {
        importer.import(FilePackSource(buildPack(examCode = "EXAM_A")))
        importer.import(FilePackSource(buildPack(examCode = "EXAM_B")))

        registry.setActive("EXAM_B")
        assertEquals("EXAM_B", registry.activePack()?.examCode)
        registry.setActive("EXAM_A")
        assertEquals("EXAM_A", registry.activePack()?.examCode)
    }

    @Test
    fun an_active_pack_that_was_deleted_is_not_returned() = runBlocking {
        // A stored code whose pack is gone must not be handed back, or the app opens a missing file.
        importer.import(FilePackSource(buildPack(examCode = "EXAM_A")))
        registry.setActive("EXAM_A")
        registry.deletePackContent("EXAM_A")
        assertNull(registry.activePack())
    }

    @Test
    fun nothing_installed_means_no_active_pack_not_a_crash() {
        assertNull("a fresh install is a normal state", registry.activePack())
        assertEquals(emptyList<PackRegistry.InstalledPack>(), registry.installed())
    }

    // ---- the ATTACH reader, in production form (D4) -------------------------------------------------

    @Test
    fun the_feed_reader_joins_pack_content_to_progress_across_two_databases() = runBlocking {
        importer.import(FilePackSource(buildPack(feedItems = 5)))
        val db = ProgressDatabase.open(ctx, exam)
        db.dao().upsertCard(FsrsCard("mcq", 2, 7.5, 5.0, "2026-08-01", "2026-07-17", 2, 3, 0))
        db.close()

        val pack = registry.installed().first { it.examCode == exam }
        val reader = FeedReader.open(ProgressDatabase.fileFor(ctx, exam), pack.dbFile)
        assertNotNull("the ATTACH must open", reader)
        reader!!.use { r ->
            assertEquals(5, r.feedSize())
            assertEquals("exactly one item has been seen", 1, r.seenCount())

            val rows = r.feed(limit = 10)
            assertEquals(5, rows.size)
            // Unseen items must still appear — a new user's feed cannot be empty (LEFT JOIN, not INNER).
            assertTrue("unseen items appear with null progress", rows.any { it.stability == null })
            val seen = rows.first { it.itemId == 2L }
            assertEquals("progress joined across the ATTACH", 7.5, seen.stability!!, 0.0001)
            assertEquals("2026-08-01", seen.due)
            // Ordering is importance-first: that is what the pack computed and the app must not recompute.
            assertTrue(rows.first().importance >= rows.last().importance)
        }
    }

    @Test
    fun the_feed_reader_returns_null_rather_than_crashing_when_nothing_is_imported() {
        assertNull(FeedReader.open(File(ctx.filesDir, "nope.db"), File(ctx.filesDir, "alsonope.db")))
    }

    @Test
    fun a_freshly_imported_pack_reports_its_full_size_before_any_progress_exists() = runBlocking {
        // REGRESSION. This shipped as a real bug and every test missed it: the others all wrote progress
        // first, which created progress_<EXAM>.db as a side effect and hid the gap. Room creates that
        // file LAZILY, so on a fresh import there was nothing to ATTACH — FeedReader.open() returned
        // null and the app displayed "0 items" while holding 32,957. Caught only by running the APK.
        //
        // The fresh-install path is the one every user meets exactly once, on first launch. It is worth
        // its own test precisely because no other test naturally reaches it.
        importer.import(FilePackSource(buildPack(feedItems = 5)))
        val pack = registry.installed().first { it.examCode == exam }

        // Deliberately NO progress written — this is a brand-new import.
        assertFalse("precondition: the progress file must not exist yet",
            ProgressDatabase.fileFor(ctx, exam).isFile)

        ProgressDatabase.ensureExists(ctx, exam)
        assertTrue("ensureExists must actually create the file",
            ProgressDatabase.fileFor(ctx, exam).isFile)

        val reader = FeedReader.open(ProgressDatabase.fileFor(ctx, exam), pack.dbFile)
        assertNotNull("the reader must open on a pack with no progress yet", reader)
        reader!!.use { r ->
            assertEquals("a new pack must report its FULL size, not zero", 5, r.feedSize())
            assertEquals("nothing seen yet, which is correct", 0, r.seenCount())
            assertEquals("and every item must still be offered", 5, r.feed(limit = 100).size)
        }
    }

    // ---- §11e: the only insurance progress has ------------------------------------------------------

    @Test
    fun progress_round_trips_through_export_and_import_including_a_note_image() = runBlocking {
        // BUILD_PROMPT §11e acceptance test 12: export -> wipe -> import -> the note AND its image return.
        // The image is why this is a ZIP: a plain progress.db copy would silently lose it.
        importer.import(FilePackSource(buildPack()))
        val archive = ProgressArchive(ctx)

        val db = ProgressDatabase.open(ctx, exam)
        db.dao().upsertCard(FsrsCard("mcq", 1, 4.25, 6.0, "2026-09-01", "2026-07-17", 2, 5, 2))
        db.dao().insertReview(Review(item_type = "mcq", item_id = 1, rating = 4, ts = "2026-07-17", elapsed_ms = 900))
        db.dao().putSetting(Setting("note:mcq:1", "my note about this question"))
        db.close()
        val img = File(archive.noteImagesDir(exam), "note1.png")
            .apply { writeBytes(ByteArray(512) { 7 }) }

        val bytes = ByteArrayOutputStream()
        val exported = archive.export(exam, bytes)
        assertTrue("export must succeed: $exported", exported is ProgressArchive.Result.Ok)

        // Wipe: the phone was lost, or the app reinstalled.
        ProgressDatabase.fileFor(ctx, exam).delete()
        File("${ProgressDatabase.fileFor(ctx, exam).path}-wal").delete()
        img.delete()
        assertFalse(img.exists())
        val wiped = ProgressDatabase.open(ctx, exam)
        assertEquals(0, wiped.dao().cardCount())
        wiped.close()

        val restored = archive.import(exam, ByteArrayInputStream(bytes.toByteArray()))
        assertTrue("import must succeed: $restored", restored is ProgressArchive.Result.Ok)

        val after = ProgressDatabase.open(ctx, exam)
        assertEquals("the card came back", 1, after.dao().cardCount())
        val card = after.dao().card("mcq", 1)!!
        assertEquals(4.25, card.stability!!, 0.0001)  // the FSRS state itself, not just the row
        assertEquals(5, card.reps)
        assertEquals(2, card.lapses)
        assertEquals("the note came back", "my note about this question", after.dao().setting("note:mcq:1"))
        after.close()
        assertTrue("THE IMAGE came back — this is why it's a zip", img.isFile)
        assertEquals(512, img.length())
    }

    @Test
    fun a_zip_slip_archive_is_refused_and_writes_nothing_outside_the_app() {
        // Import unpacks a USER-SUPPLIED archive, so path traversal is a real attack. This asserts the
        // OUTCOME — refused, nothing escapes — deliberately NOT which layer caught it.
        //
        // Why: on this API level ANDROID ITSELF rejects the entry first ("Invalid zip entry path",
        // verified API 36, 2026-07-17), so our own canonical-path guard never executes here. Asserting
        // on our guard's message would therefore fail on a modern device even though the app is safe.
        //
        // That platform check is defence in depth, NOT a reason to drop our guard: minSdk is 26, and on
        // Android versions predating it OUR guard is the only protection. It is proven directly, where
        // no platform layer can intercept the input, in ProgressArchiveGuardTest (a JVM test) — see
        // ProgressArchive.isWithin's KDoc.
        val evil = ByteArrayOutputStream()
        ZipOutputStream(evil).use { z ->
            z.putNextEntry(ZipEntry("../../../../data/data/dev.kayomarz.studyfeed/pwned.txt"))
            z.write("owned".toByteArray())
            z.closeEntry()
        }

        val result = ProgressArchive(ctx).import(exam, ByteArrayInputStream(evil.toByteArray()))

        assertTrue("a traversal archive must be refused, by SOME layer", result is ProgressArchive.Result.Failed)
        assertFalse("nothing may be written outside the destination — this is the claim that matters",
            File(ctx.applicationInfo.dataDir, "pwned.txt").exists())
        assertFalse("and no progress may be installed from it",
            ProgressDatabase.fileFor(ctx, exam).isFile)
    }

    @Test
    fun importing_an_export_for_the_wrong_exam_is_refused() {
        val junk = ByteArrayOutputStream()
        ZipOutputStream(junk).use { z ->
            z.putNextEntry(ZipEntry("db/progress_SOMETHING_ELSE.db")); z.write(ByteArray(8)); z.closeEntry()
        }
        val result = ProgressArchive(ctx).import(exam, ByteArrayInputStream(junk.toByteArray()))
        assertTrue(result is ProgressArchive.Result.Failed)
        assertTrue((result as ProgressArchive.Result.Failed).message.contains("per-exam", ignoreCase = true))
    }

    @Test
    fun exporting_an_exam_with_no_progress_says_so_rather_than_producing_an_empty_file() {
        val result = ProgressArchive(ctx).export("NEVER_STUDIED", ByteArrayOutputStream())
        assertTrue(result is ProgressArchive.Result.Failed)
    }
}
