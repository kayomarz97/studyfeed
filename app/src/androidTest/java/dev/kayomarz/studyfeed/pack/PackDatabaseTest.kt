package dev.kayomarz.studyfeed.pack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for [PackDatabase] — real SQLite, real Android (plan D6: the emulator is a real
 * Android image, so correctness proven here is genuine).
 *
 * **The headline is [a_trimmed_pack_offers_less_and_a_full_pack_offers_more]** — D5's "both directions"
 * requirement, and the test that actually proves the database is swappable. Everything else in A1 could
 * work perfectly and the app could still be a single-exam app if that one fails.
 *
 * Packs here are built in-code, deliberately: they are tiny, they run anywhere, and — crucially — the
 * REAL pack cannot prove graceful degradation, because it has nothing missing to degrade from. A
 * synthetic trimmed pack is the only way to test the absent case (plan §3.1a).
 */
@RunWith(AndroidJUnit4::class)
class PackDatabaseTest {

    private lateinit var ctx: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        dir = File(ctx.filesDir, "packtest").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    // ---- fixtures ----------------------------------------------------------------------------------

    /**
     * Builds a pack.
     *
     * @param full when true, every asset table gets a row (today's MRCP Part 1 pack). When false, the
     *   asset tables exist but are EMPTY — a lean future pack (say a Part 2 with no blobs or figures).
     * @param examCode/examName deliberately parameterised: a test that hardcoded one exam would be the
     *   very bug these tests exist to catch.
     */
    private fun makePack(
        name: String,
        schemaVersion: String = "1",
        userVersion: Int = 1,
        full: Boolean = true,
        examCode: String = "TEST_EXAM_A",
        examName: String = "Test Exam A",
        flagsJson: String? = null,
        withManifestRow: Boolean = true,
    ): File {
        val f = File(dir, name)
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("PRAGMA user_version = $userVersion")
            db.execSQL("""CREATE TABLE pack_manifest (schema_version TEXT, pack_version TEXT,
                exam_code TEXT, exam_name TEXT, built_at TEXT, checksum TEXT,
                feature_flags_json TEXT, counts_json TEXT, importance_weights_json TEXT,
                importance_histogram_json TEXT)""")
            db.execSQL("CREATE TABLE subjects (id INTEGER PRIMARY KEY, name TEXT, blueprint_weight REAL, ord INTEGER)")
            db.execSQL("CREATE TABLE tag_defs (id INTEGER PRIMARY KEY, key TEXT, label TEXT, color TEXT, description TEXT, ord INTEGER)")
            for (t in listOf("flashcards", "reels", "text_blobs", "interlink_quizzes", "figures",
                "topic_pages", "concept_edges", "mcqs")) {
                db.execSQL("CREATE TABLE $t (id INTEGER PRIMARY KEY)")
                if (full) db.execSQL("INSERT INTO $t (id) VALUES (1)")
            }
            val flags = flagsJson ?: """{"has_flashcards":$full,"has_reels":$full,"has_blobs":$full,
                "has_interlink":$full,"has_figures":$full,"has_dossiers":$full,
                "has_concept_map":$full,"has_generated_mcqs":$full}""".trimIndent().replace("\n", "")
            if (withManifestRow) {
                db.execSQL(
                    "INSERT INTO pack_manifest VALUES (?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(schemaVersion, "0.1.0", examCode, examName, "2026-07-17T00:00:00Z",
                        "content-checksum-not-the-file-hash", flags, """{"feed_items":42}""", "{}", "{}")
                )
            }
            db.execSQL("INSERT INTO subjects VALUES (1,'Subject One',14.0,1),(2,'Subject Two',25.0,2)")
            db.execSQL("INSERT INTO tag_defs VALUES (1,'high_yield','High yield','#FF0000','',1)")
        }
        return f
    }

    // ---- THE headline: both directions, same binary (D5) --------------------------------------------

    @Test
    fun a_trimmed_pack_offers_less_and_a_full_pack_offers_more() {
        // THIS is the swappability contract. Same code path, two packs, different capabilities — and no
        // crash, no dead UI, on the lean one. If this ever fails, the app has silently become a
        // single-exam app, which is the only way §0 multi_pack ever fails.
        val fullPack = (PackDatabase.open(makePack("full.db", full = true)) as PackDatabase.Result.Ok).pack
        val leanPack = (PackDatabase.open(makePack("lean.db", full = false)) as PackDatabase.Result.Ok).pack

        // Today's shape: everything present (C11b filled blobs/interlink/figures — use them).
        assertTrue(fullPack.capabilities.hasBlobs)
        assertTrue(fullPack.capabilities.hasInterlink)
        assertTrue(fullPack.capabilities.hasFigures)
        assertTrue(fullPack.capabilities.hasFlashcards)

        // A lean future pack: those types simply are not offered. Absent != broken.
        assertFalse(leanPack.capabilities.hasBlobs)
        assertFalse(leanPack.capabilities.hasInterlink)
        assertFalse(leanPack.capabilities.hasFigures)
        assertFalse(leanPack.capabilities.hasFlashcards)

        // Both loaded successfully. The lean pack is a VALID pack, not an error.
        assertEquals("TEST_EXAM_A", leanPack.manifest.examCode)
    }

    @Test
    fun rows_are_authoritative_when_the_packs_own_flags_lie() {
        // A flag claiming data that isn't there is exactly what produces a dead UI element with no
        // error. Rows win; the lie is surfaced rather than swallowed.
        val f = makePack("liar.db", full = false, flagsJson = """{"has_blobs":true,"has_figures":true}""")
        val pack = (PackDatabase.open(f) as PackDatabase.Result.Ok).pack

        assertFalse("rows are authoritative — the flag lied", pack.capabilities.hasBlobs)
        assertFalse(pack.capabilities.hasFigures)
        assertTrue("the disagreement must be surfaced, not swallowed",
            pack.capabilities.declarationMismatches.isNotEmpty())
        assertTrue(pack.capabilities.declarationMismatches.any { it.contains("has_blobs") })
    }

    @Test
    fun a_well_formed_pack_reports_no_mismatches() {
        val pack = (PackDatabase.open(makePack("clean.db", full = true)) as PackDatabase.Result.Ok).pack
        assertEquals(emptyList<String>(), pack.capabilities.declarationMismatches)
    }

    // ---- the gate, against real SQLite ------------------------------------------------------------

    @Test
    fun a_v1_pack_loads_and_configures_the_app_entirely_from_the_pack() {
        val pack = (PackDatabase.open(makePack("v1.db")) as PackDatabase.Result.Ok).pack

        assertEquals(1, pack.manifest.schemaVersion)
        assertEquals("TEST_EXAM_A", pack.manifest.examCode)
        assertEquals("Test Exam A", pack.manifest.examName)
        assertEquals(2, pack.subjects.size)
        assertEquals("Subject One", pack.subjects[0].name)
        assertEquals(1, pack.tags.size)
        assertEquals("high_yield", pack.tags[0].key)
        assertEquals(42L, pack.manifest.counts["feed_items"])
    }

    @Test
    fun the_app_reads_a_pack_for_an_exam_it_has_never_heard_of() {
        // §0 multi_pack, stated as a test: a completely different exam must work with no code change.
        // Nothing in app/ knows this exam exists.
        val f = makePack("other.db", examCode = "SOME_FUTURE_EXAM_2031", examName = "A Different Exam Entirely")
        val pack = (PackDatabase.open(f) as PackDatabase.Result.Ok).pack

        assertEquals("SOME_FUTURE_EXAM_2031", pack.manifest.examCode)
        assertEquals("A Different Exam Entirely", pack.manifest.examName)
    }

    @Test
    fun a_newer_schema_is_refused_with_both_versions_named_and_no_crash() {
        val f = makePack("v2.db", schemaVersion = "2", userVersion = 2)
        val result = PackDatabase.open(f)

        assertTrue("a future pack must be refused", result is PackDatabase.Result.Rejected)
        val msg = (result as PackDatabase.Result.Rejected).message
        assertTrue("must name the pack's version", msg.contains("2"))
        assertTrue("must name what we support", msg.contains("1"))
    }

    @Test
    fun a_self_contradicting_pack_is_refused() {
        // pack_manifest says 1, PRAGMA user_version says 2. SPEC.md §4 says they mirror; they don't.
        val f = makePack("mismatch.db", schemaVersion = "1", userVersion = 2)
        assertTrue(PackDatabase.open(f) is PackDatabase.Result.Rejected)
    }

    @Test
    fun a_pack_with_no_manifest_row_is_refused_not_assumed_fine() {
        val f = makePack("nomanifest.db", withManifestRow = false)
        assertTrue(PackDatabase.open(f) is PackDatabase.Result.Rejected)
    }

    @Test
    fun a_non_database_file_is_refused_rather_than_crashing() {
        // The realistic hostile case: user picks the wrong file, or a copy was truncated mid-transfer.
        val junk = File(dir, "notadb.db").apply { writeText("this is definitely not a sqlite database") }
        val result = PackDatabase.open(junk)
        assertTrue("garbage must be refused, never crash", result is PackDatabase.Result.Rejected)
    }

    @Test
    fun a_missing_file_is_refused_rather_than_crashing() {
        assertTrue(PackDatabase.open(File(dir, "does_not_exist.db")) is PackDatabase.Result.Rejected)
    }

    @Test
    fun an_empty_sqlite_file_with_no_tables_is_refused() {
        val f = File(dir, "empty.db")
        SQLiteDatabase.openOrCreateDatabase(f, null).close()
        assertTrue(PackDatabase.open(f) is PackDatabase.Result.Rejected)
    }
}
