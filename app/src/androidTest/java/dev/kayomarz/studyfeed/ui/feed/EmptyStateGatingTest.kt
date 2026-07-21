package dev.kayomarz.studyfeed.ui.feed

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kayomarz.studyfeed.pack.PackCapabilities
import dev.kayomarz.studyfeed.pack.PackDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The empty-state gating proven end-to-end on the SAME binary against TWO packs — the D5 "both
 * directions" requirement, applied to the honest-empty-states work: a section a pack can't fill must
 * resolve to [EmptyCase.ABSENT], and the same section on a full pack must NOT (it's caught-up/matches).
 *
 * This is the integration the JVM [EmptyStatesTest] can't reach: it runs real SQLite, resolves real
 * [PackCapabilities] (rows-authoritative) from each pack, and feeds those into [EmptyStates.decide] —
 * so a regression that made a lean pack render a blank screen would fail here.
 */
@RunWith(AndroidJUnit4::class)
class EmptyStateGatingTest {

    private lateinit var ctx: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        dir = File(ctx.filesDir, "emptystatetest").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    /** Builds a pack whose asset tables are populated ([full]=true) or exist-but-empty ([full]=false). */
    private fun makePack(name: String, full: Boolean): PackCapabilities {
        val f = File(dir, name)
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("PRAGMA user_version = 1")
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
            val flags = """{"has_flashcards":$full,"has_reels":$full,"has_blobs":$full,
                "has_interlink":$full,"has_figures":$full,"has_dossiers":$full,
                "has_concept_map":$full,"has_generated_mcqs":$full}""".trimIndent().replace("\n", "")
            // exam_code/name parameterised & non-MRCP on purpose: a hardcoded exam string is the bug the
            // whole multi_pack contract exists to prevent.
            db.execSQL("INSERT INTO pack_manifest VALUES (?,?,?,?,?,?,?,?,?,?)",
                arrayOf("1", "0.1.0", "TEST_EXAM_X", "Test Exam X", "2026-07-21T00:00:00Z",
                    "content-checksum", flags, """{"feed_items":10}""", "{}", "{}"))
            db.execSQL("INSERT INTO subjects VALUES (1,'Subject One',14.0,1)")
        }
        return (PackDatabase.open(f) as PackDatabase.Result.Ok).pack.capabilities
    }

    @Test
    fun a_full_pack_never_shows_ABSENT_for_its_sections() {
        val caps = makePack("full.db", full = true)
        // Concept map / topic pages / passages all present → caught-up or matches, never "not in pack".
        assertEquals(EmptyCase.NOTHING_DUE, EmptyStates.decide(caps.hasConceptMap, filterActive = false))
        assertEquals(EmptyCase.NOTHING_DUE, EmptyStates.decide(caps.hasDossiers, filterActive = false))
        assertEquals(EmptyCase.NOTHING_DUE, EmptyStates.decide(caps.hasBlobs, filterActive = false))
        assertEquals(EmptyCase.NOTHING_MATCHES, EmptyStates.decide(caps.hasBlobs, filterActive = true))
    }

    @Test
    fun a_trimmed_pack_shows_ABSENT_for_the_sections_it_lacks() {
        val caps = makePack("lean.db", full = false)
        // The lean pack is VALID; these sections simply aren't in it → honest ABSENT, not a blank screen.
        assertEquals(EmptyCase.ABSENT, EmptyStates.decide(caps.hasConceptMap, filterActive = false))
        assertEquals(EmptyCase.ABSENT, EmptyStates.decide(caps.hasDossiers, filterActive = false))
        assertEquals(EmptyCase.ABSENT, EmptyStates.decide(caps.hasBlobs, filterActive = false))
        // ABSENT wins even with a filter on — the pack never had it.
        assertEquals(EmptyCase.ABSENT, EmptyStates.decide(caps.hasInterlink, filterActive = true))
    }
}
