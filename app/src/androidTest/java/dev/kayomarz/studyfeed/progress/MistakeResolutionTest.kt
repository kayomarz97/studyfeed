package dev.kayomarz.studyfeed.progress

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A6: the mistakes re-drill resolution + the v1→v2 schema migration.
 *
 * The migration is tested by running its exact `ALTER TABLE` on a hand-built v1 `mistakes` table and
 * proving it is **additive and non-destructive** (existing rows survive, the new column defaults 0) —
 * `MigrationTestHelper` isn't used because `exportSchema=false`, but this validates the actual DDL. The
 * DAO flow is tested end-to-end on a fresh v2 DB.
 */
@RunWith(AndroidJUnit4::class)
class MistakeResolutionTest {

    private fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migration_1_to_2_adds_resolved_count_without_dropping_data() {
        val f = File(ctx().cacheDir, "mig_v1.db"); f.delete()
        val raw = SQLiteDatabase.openOrCreateDatabase(f, null)
        try {
            // v1 mistakes shape (pre-A6, no resolved_count).
            raw.execSQL("""CREATE TABLE mistakes (id INTEGER PRIMARY KEY AUTOINCREMENT, item_type TEXT,
                item_id INTEGER, subject_id INTEGER, chosen_idx INTEGER, ts TEXT, resolved INTEGER NOT NULL DEFAULT 0)""")
            raw.execSQL("INSERT INTO mistakes (item_type, item_id, ts, resolved) VALUES ('mcq', 42, '2026-07-18T00:00:00Z', 0)")

            // The exact MIGRATION_1_2 DDL.
            raw.execSQL("ALTER TABLE mistakes ADD COLUMN resolved_count INTEGER NOT NULL DEFAULT 0")

            raw.rawQuery("SELECT resolved, resolved_count FROM mistakes WHERE item_id = 42", null).use { c ->
                assertTrue("the pre-existing row survived", c.moveToFirst())
                assertEquals("resolved preserved", 0, c.getInt(0))
                assertEquals("new column defaults to 0", 0, c.getInt(1))
            }
        } finally { raw.close(); f.delete() }
    }

    @Test fun three_consecutive_corrects_resolve_a_mistake_and_a_wrong_resets_it() {
        val exam = "A6_REDRILL_TEST"
        File(ProgressDatabase.fileFor(ctx(), exam).path).delete()
        val db = ProgressDatabase.open(ctx(), exam)
        try {
            runBlocking {
                val dao = db.dao()
                dao.insertMistake(Mistake(item_type = "mcq", item_id = 7, subject_id = null,
                    chosen_idx = 1, ts = "2026-07-18T00:00:00Z", resolved = 0))
                assertEquals("in the pile", 1, dao.unresolvedMistakeCount())

                assertTrue(dao.advanceResolution("mcq", 7, 3) > 0)   // 1/3
                assertEquals(1, dao.unresolvedMistakeCount())
                dao.advanceResolution("mcq", 7, 3)                   // 2/3
                assertEquals("still unresolved at 2/3", 1, dao.unresolvedMistakeCount())
                dao.advanceResolution("mcq", 7, 3)                   // 3/3 → resolved
                assertEquals("resolved at 3/3", 0, dao.unresolvedMistakeCount())
                assertEquals("counts toward XP", 1, dao.resolvedMistakeCount())

                // A fresh wrong opens a new drill; two corrects then a wrong resets it.
                dao.insertMistake(Mistake(item_type = "mcq", item_id = 8, subject_id = null,
                    chosen_idx = 0, ts = "2026-07-18T01:00:00Z", resolved = 0))
                dao.advanceResolution("mcq", 8, 3)                   // 1/3
                dao.advanceResolution("mcq", 8, 3)                   // 2/3
                val reset = dao.resetUnresolved("mcq", 8, "2026-07-18T02:00:00Z", 2)  // wrong → back to 0/3
                assertTrue("an unresolved mistake was reset", reset > 0)
                dao.advanceResolution("mcq", 8, 3)                   // 1/3 again (not resolved)
                dao.advanceResolution("mcq", 8, 3)                   // 2/3
                assertEquals("reset means it takes 3 more, still unresolved", 1, dao.unresolvedMistakeCount())
            }
        } finally { db.close(); File(ProgressDatabase.fileFor(ctx(), exam).path).delete() }
    }

    // ── A7 cycle 3: v2 → v3 migration (mcq_attempts / line_marks / decks + sessions columns) ──────────

    @Test fun migration_2_to_3_adds_session_columns_and_new_tables_additively() {
        val f = File(ctx().cacheDir, "mig_v2.db"); f.delete()
        val raw = SQLiteDatabase.openOrCreateDatabase(f, null)
        try {
            // v1/v2 sessions shape (pre-cycle-3) + a pre-existing row.
            raw.execSQL("""CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, mode TEXT,
                started TEXT, ended TEXT, seen INTEGER, correct INTEGER)""")
            raw.execSQL("INSERT INTO sessions (mode, seen, correct) VALUES ('mixed', 5, 4)")

            // The EXACT MIGRATION_2_3 DDL.
            raw.execSQL("""CREATE TABLE IF NOT EXISTS mcq_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                item_id INTEGER NOT NULL, subject_id INTEGER, correct INTEGER NOT NULL, ts TEXT NOT NULL)""")
            raw.execSQL("CREATE INDEX IF NOT EXISTS idx_mcq_attempts_ts ON mcq_attempts(ts)")
            raw.execSQL("""CREATE TABLE IF NOT EXISTS line_marks (topic_key TEXT NOT NULL, section_ord INTEGER NOT NULL,
                line_hash TEXT NOT NULL, kind TEXT NOT NULL, ts TEXT NOT NULL, PRIMARY KEY (topic_key, section_ord, line_hash))""")
            raw.execSQL("""CREATE TABLE IF NOT EXISTS decks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL, config_json TEXT NOT NULL, ts TEXT NOT NULL)""")
            raw.execSQL("ALTER TABLE sessions ADD COLUMN name TEXT")
            raw.execSQL("ALTER TABLE sessions ADD COLUMN config_json TEXT")
            raw.execSQL("ALTER TABLE sessions ADD COLUMN cursor INTEGER NOT NULL DEFAULT 0")
            raw.execSQL("ALTER TABLE sessions ADD COLUMN updated TEXT")
            raw.execSQL("ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")

            // The pre-existing session survived; the new columns defaulted.
            raw.rawQuery("SELECT seen, correct, cursor, archived, name FROM sessions WHERE mode='mixed'", null).use { c ->
                assertTrue("pre-existing session survived", c.moveToFirst())
                assertEquals("seen preserved", 5, c.getInt(0))
                assertEquals("correct preserved", 4, c.getInt(1))
                assertEquals("cursor defaults 0", 0, c.getInt(2))
                assertEquals("archived defaults 0", 0, c.getInt(3))
                assertTrue("name defaults null", c.isNull(4))
            }
            // New tables + columns are usable.
            raw.execSQL("INSERT INTO mcq_attempts (item_id, subject_id, correct, ts) VALUES (1, 2, 1, 't')")
            raw.execSQL("INSERT INTO line_marks (topic_key, section_ord, line_hash, kind, ts) VALUES ('k',0,'h','star','t')")
            raw.execSQL("INSERT INTO decks (name, config_json, ts) VALUES ('d','{}','t')")
            raw.execSQL("UPDATE sessions SET config_json='{}', cursor=12, name='Cardio' WHERE mode='mixed'")
            raw.rawQuery("SELECT COUNT(*) FROM mcq_attempts", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        } finally { raw.close(); f.delete() }
    }

    @Test fun a_fresh_v3_room_db_creates_and_validates_the_new_tables() {
        // Opening the CURRENT Room DB on a fresh file creates the v3 schema and VALIDATES it — if any new
        // entity disagreed with the DDL Room generates, Room throws on open. A clean open is the proof.
        val exam = "V3_FRESH_TEST"
        File(ProgressDatabase.fileFor(ctx(), exam).path).delete()
        val db = ProgressDatabase.open(ctx(), exam)
        try {
            runBlocking { db.dao().insertMcqAttempt(McqAttempt(item_id = 9, subject_id = 3, correct = 1, ts = "t")) }
            val w = db.openHelper.writableDatabase
            w.execSQL("INSERT INTO line_marks (topic_key, section_ord, line_hash, kind, ts) VALUES ('k',1,'h','highlight','t')")
            w.execSQL("INSERT INTO decks (name, config_json, ts) VALUES ('Starred','{}','t')")
            w.execSQL("INSERT INTO sessions (mode, config_json, cursor, name) VALUES ('mixed','{}',7,'S')")
            w.query("SELECT cursor FROM sessions WHERE name='S'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(7, c.getInt(0))
            }
            w.query("SELECT COUNT(*) FROM line_marks").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        } finally { db.close(); File(ProgressDatabase.fileFor(ctx(), exam).path).delete() }
    }
}
