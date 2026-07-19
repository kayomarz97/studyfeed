package dev.kayomarz.studyfeed.spike

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ⚠ SPIKE — throwaway. Delete once A1's real data layer lands (plan step 1, D4, D6).
 *
 * **This test exists to FALSIFY a claim, not to confirm one.** Everything in A1's data layer rests on
 * plan D4, and D4 rests on a *documented gap*: no official Room doc addresses Room + `ATTACH` at all.
 * The known-hostile facts:
 *
 *  - **SQLite scopes `ATTACH` to ONE connection.** Room **pools** connections ⇒ an ATTACH issued on one
 *    pooled connection is invisible to a query served by another.
 *  - **Android's `SQLiteDatabase.enableWriteAheadLogging()` javadoc, verbatim:** *"If the database has
 *    any attached databases, then execution of queries in parallel is not possible."* Room enables WAL
 *    by default.
 *
 * **The claim under test (D4):** *a dedicated, non-pooled, READ-ONLY connection with the pack ATTACHed
 * can read `progress.db` while Room's WAL pool writes it — without error and without stale reads.*
 *
 * Pass ⇒ build A1's data layer on D4. **Fail ⇒ join in Kotlin** (`fsrs_cards` is sparse; 32,957 rows fit
 * in memory) and amend `SPEC.md` §2. **Never** "fix" a failure by disabling WAL: that would degrade
 * every write in the app to buy back one read path.
 *
 * Runs on the emulator (D6). Correctness here is genuine — same framework, same libsqlite. This says
 * NOTHING about performance; those numbers need the real device and must never be quoted from here.
 */
@RunWith(AndroidJUnit4::class)
class AttachSpikeTest {

    // ---- A minimal stand-in for the real thing -----------------------------------------------------
    // Deliberately mirrors SPEC.md §2's shape (fsrs_cards keyed by (item_type,item_id)) so the spike
    // tests the SAME access pattern A1 will use, not a toy that proves nothing about it.

    @Entity(tableName = "fsrs_cards", primaryKeys = ["item_type", "item_id"])
    data class FsrsCard(val item_type: String, val item_id: Long, val stability: Double)

    @Dao
    interface ProgressDao {
        @Insert fun insert(card: FsrsCard)
        @Query("SELECT COUNT(*) FROM fsrs_cards") fun count(): Int
    }

    @Database(entities = [FsrsCard::class], version = 1, exportSchema = false)
    abstract class ProgressDb : RoomDatabase() {
        abstract fun dao(): ProgressDao
    }

    @Entity(tableName = "dummy") data class Dummy(@PrimaryKey val id: Long)

    private lateinit var ctx: Context
    private lateinit var progressFile: File
    private lateinit var packFile: File
    private var room: ProgressDb? = null
    private var reader: SQLiteDatabase? = null

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        progressFile = ctx.getDatabasePath("spike_progress.db")
        packFile = File(ctx.filesDir, "spike_pack.db")
        listOf(progressFile, packFile).forEach { f ->
            f.delete(); File("${f.path}-wal").delete(); File("${f.path}-shm").delete()
            f.parentFile?.mkdirs()
        }

        // Build a stand-in "pack": read-only content, keyed like feed_items.
        SQLiteDatabase.openOrCreateDatabase(packFile, null).use { pack ->
            pack.execSQL("CREATE TABLE feed_items (item_type TEXT, item_id INTEGER, importance INTEGER)")
            pack.execSQL("INSERT INTO feed_items VALUES ('mcq', 1, 5), ('mcq', 2, 3), ('reel', 3, 4)")
        }
    }

    @After
    fun tearDown() {
        reader?.close()
        room?.close()
    }

    private fun openRoom(): ProgressDb =
        Room.databaseBuilder(ctx, ProgressDb::class.java, progressFile.name).build().also { room = it }

    /**
     * Opens the dedicated reader exactly as D4 specifies: non-pooled, read-only, pack ATTACHed.
     *
     * NOTE it opens `progress.db` READ-ONLY. That is the crux of D4 — a reader that never writes cannot
     * contend with Room's writer, so the two coexist by design rather than by luck.
     */
    private fun openDedicatedReader(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(progressFile.path, null, SQLiteDatabase.OPEN_READONLY).also {
            it.execSQL("ATTACH DATABASE '${packFile.path}' AS pack")
            reader = it
        }

    // ---- The falsification attempts ----------------------------------------------------------------

    @Test
    fun room_creates_the_db_and_uses_WAL_by_default() {
        // Establishes the hostile precondition rather than assuming it: if Room ISN'T using WAL, the
        // whole D4 concern is moot and this spike would be testing nothing.
        val db = openRoom()
        db.dao().insert(FsrsCard("mcq", 1, 1.0)) // forces creation
        val mode = db.openHelper.readableDatabase
            .query("PRAGMA journal_mode").use { c -> c.moveToFirst(); c.getString(0) }
        assertEquals("Room should default to WAL — if this changed, revisit D4", "wal", mode.lowercase())
    }

    @Test
    fun dedicated_readonly_connection_can_ATTACH_and_JOIN_across_both_DBs() {
        // THE claim. A cross-DB join is the thing SPEC.md §2 asks for and the thing Room cannot do.
        val db = openRoom()
        db.dao().insert(FsrsCard("mcq", 1, 10.0))
        db.dao().insert(FsrsCard("mcq", 2, 20.0))

        val rows = openDedicatedReader().rawQuery(
            """
            SELECT f.item_id, f.importance, p.stability
            FROM pack.feed_items f
            JOIN main.fsrs_cards p ON p.item_type = f.item_type AND p.item_id = f.item_id
            ORDER BY f.item_id
            """.trimIndent(), null
        ).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getInt(1), c.getDouble(2))) }
        }

        assertEquals("cross-DB join must return both matched rows", 2, rows.size)
        assertEquals(Triple(1L, 5, 10.0), rows[0])
        assertEquals(Triple(2L, 3, 20.0), rows[1])
    }

    @Test
    fun the_reader_sees_rooms_writes_committed_AFTER_the_ATTACH() {
        // The stale-read half of the claim, and the one most likely to fail: the reader is opened and
        // ATTACHed FIRST, then Room writes. Under WAL a reader can pin an old snapshot — if it does,
        // the feed would silently show stale progress, which is worse than an error.
        val db = openRoom()
        db.dao().insert(FsrsCard("mcq", 1, 1.0))

        val readerDb = openDedicatedReader()
        fun countViaReader() = readerDb.rawQuery("SELECT COUNT(*) FROM main.fsrs_cards", null)
            .use { c -> c.moveToFirst(); c.getInt(0) }

        assertEquals(1, countViaReader())

        db.dao().insert(FsrsCard("mcq", 2, 2.0)) // Room writes AFTER the reader attached
        assertEquals("reader must observe Room's later commit, not a pinned snapshot", 2, countViaReader())

        db.dao().insert(FsrsCard("reel", 3, 3.0))
        assertEquals(3, countViaReader())
    }

    @Test
    fun room_can_still_write_while_the_attached_reader_is_open() {
        // The javadoc warns attached DBs disable PARALLEL query execution. That should be a throughput
        // note, not a lock-up. This asserts Room's writer isn't blocked/erroring while the reader lives.
        val db = openRoom()
        db.dao().insert(FsrsCard("mcq", 1, 1.0))
        val readerDb = openDedicatedReader()

        readerDb.rawQuery("SELECT COUNT(*) FROM pack.feed_items", null).use { it.moveToFirst() }
        repeat(50) { i -> db.dao().insert(FsrsCard("bulk", i.toLong(), i.toDouble())) }

        assertEquals(51, db.dao().count())
        assertEquals(51, readerDb.rawQuery("SELECT COUNT(*) FROM main.fsrs_cards", null)
            .use { c -> c.moveToFirst(); c.getInt(0) })
    }

    @Test
    fun the_ATTACH_survives_many_queries_on_the_dedicated_connection() {
        // The pooling failure mode, stated directly: on Room's POOL an ATTACH would vanish when another
        // connection served the query. On a connection we own and never hand back, it must persist.
        val db = openRoom()
        db.dao().insert(FsrsCard("mcq", 1, 1.0))
        val readerDb = openDedicatedReader()

        repeat(100) { i ->
            val n = readerDb.rawQuery("SELECT COUNT(*) FROM pack.feed_items", null)
                .use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals("ATTACH vanished on query #$i — D4 is falsified", 3, n)
        }
    }

    @Test
    fun the_reader_cannot_write_which_is_why_it_cannot_contend() {
        // D4's safety argument depends on the reader being genuinely read-only, not merely
        // well-behaved. Prove the OS enforces it rather than trusting our own discipline.
        val db = openRoom()
        db.dao().insert(FsrsCard("mcq", 1, 1.0))
        val readerDb = openDedicatedReader()

        val threw = try {
            readerDb.execSQL("INSERT INTO main.fsrs_cards VALUES ('x', 99, 1.0)"); false
        } catch (e: Exception) { true }

        assertTrue("a READONLY connection must refuse writes — D4's no-contention argument rests on it", threw)
    }
}
