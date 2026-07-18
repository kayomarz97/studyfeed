package dev.kayomarz.studyfeed.progress

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * `progress_<EXAM>.db` — **the user's own data, and the only irreplaceable thing in this app**
 * (spec/SPEC.md §2; BUILD_PROMPT §8 A1).
 *
 * **The two-database rule, and why it is not negotiable:** content and progress are separate SQLite
 * files. The pack is **replaced wholesale on re-import**; progress is **never wiped**. A pack can always
 * be rebuilt by the compiler — six weeks of a physician's revision cannot. Everything about this file's
 * design follows from that asymmetry.
 *
 * **Room owns this file and nothing else (plan D1).** Room's value is exactly what progress needs and
 * the pack doesn't: a typed schema, compile-checked DAOs, and real migrations for a shape that WILL
 * evolve as A2–A7 land. The pack is read with raw SQLite and `ATTACH`ed for joins (plan D4).
 *
 * **Per `exam_code`, which is what makes packs swappable:** each exam gets its own progress file, so
 * importing an MRCP Part 2 pack cannot touch Part 1's progress, and switching back finds it intact.
 * `exam_code` comes from `pack_manifest` — never from a filename (D3).
 *
 * ⚠ **The schema below is spec/SPEC.md §2's DDL, transcribed deliberately.** Do not "improve" a column
 * name or type here without changing the spec in the same commit: this file and §11e's export are a
 * contract with the user's own historical data, and a rename strands it.
 */
@Database(
    entities = [
        FsrsCard::class, Review::class, Mistake::class, Star::class, ItemTag::class,
        SubjectMastery::class, StudySession::class, BlobRead::class, FeedState::class, Setting::class,
        McqAttempt::class, LineMark::class, Deck::class,
    ],
    version = 3,
    exportSchema = false, // migrations are hand-written + tested (MigrationTest); no schema-JSON needed.
)
abstract class ProgressDatabase : RoomDatabase() {
    abstract fun dao(): ProgressDao

    companion object {

        /**
         * v1 → v2 (A6): add `mistakes.resolved_count` for the "re-drill correct N times → resolved"
         * counter. A plain additive `ALTER TABLE` — existing rows default to 0, nothing is dropped. This
         * is the non-destructive migration the two-DB design demands (NEVER `fallbackToDestructiveMigration`).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mistakes ADD COLUMN resolved_count INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 → v3 (A7 cycle 3): the progress features of this cycle, all **additive** — no column is
         * dropped or repurposed, so a re-import still never wipes progress.
         *
         * - `mcq_attempts`: one row per MCQ answer (correct 0/1 + denormalised subject), so accuracy has
         *   a real denominator (before this only *wrong* answers were stored) — powers subject accuracy
         *   and accuracy-over-time.
         * - `line_marks`: a user's per-line star/highlight inside a topic dossier, anchored to the topic's
         *   `stable_key` + a hash of the line (survives a pack rebuild, §5j).
         * - `decks`: a named saved filter (custom decks / P4).
         * - `sessions` gains `name` / `config_json` / `cursor` / `updated` / `archived` for resumable,
         *   renamable named study sessions (P2). The original columns are untouched.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS mcq_attempts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        item_id INTEGER NOT NULL, subject_id INTEGER,
                        correct INTEGER NOT NULL, ts TEXT NOT NULL)""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_mcq_attempts_ts ON mcq_attempts(ts)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS line_marks (
                        topic_key TEXT NOT NULL, section_ord INTEGER NOT NULL, line_hash TEXT NOT NULL,
                        kind TEXT NOT NULL, ts TEXT NOT NULL,
                        PRIMARY KEY (topic_key, section_ord, line_hash))""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS decks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL, config_json TEXT NOT NULL, ts TEXT NOT NULL)""".trimIndent()
                )
                // sessions: additive columns (the table already exists from v1).
                db.execSQL("ALTER TABLE sessions ADD COLUMN name TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN config_json TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN cursor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN updated TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }
        /**
         * Opens (creating on first use) the progress DB for [examCode].
         *
         * **No `fallbackToDestructiveMigration`, ever.** That flag silently drops the user's data when a
         * migration is missing — precisely the catastrophe this whole two-DB design exists to prevent.
         * A missing migration must crash loudly in development, not wipe six weeks of revision in the
         * user's hand.
         */
        fun open(context: Context, examCode: String): ProgressDatabase =
            Room.databaseBuilder(context, ProgressDatabase::class.java, fileName(examCode))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /**
         * Force the progress file into existence, creating an empty schema if it isn't there.
         *
         * **Why this is needed, and the bug it fixes:** Room creates the file lazily, on first query —
         * so a freshly imported pack has no `progress_<EXAM>.db` at all until the user reviews something.
         * [FeedReader] `ATTACH`es progress and pack together and needs *both* files to exist, so without
         * this it returned null on a new pack and **the feed reported 0 items** while holding 32,957.
         *
         * Caught by running the real APK, not by a test: every test wrote progress first, which created
         * the file as a side effect and hid the gap. The fresh-install path — the one every user meets
         * exactly once, on their first launch — was the only one that exposed it.
         */
        fun ensureExists(context: Context, examCode: String) {
            // RoomDatabase is not Closeable, so no `use` — close it explicitly or the connection leaks.
            val db = open(context, examCode)
            try {
                db.openHelper.writableDatabase // opening the helper is what actually creates the file
            } finally {
                db.close()
            }
        }

        /** Progress files are namespaced by exam_code — see the class KDoc. */
        fun fileName(examCode: String) = "progress_$examCode.db"

        /** The on-disk file, for §11e export and for the ATTACH reader (D4). */
        fun fileFor(context: Context, examCode: String): File =
            context.getDatabasePath(fileName(examCode))
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Entities — spec/SPEC.md §2 verbatim. `item_type`/`item_id` reference the PACK's feed_items/assets
// across the ATTACH seam; there are deliberately no foreign keys, because the pack is a different
// database and may be swapped underneath this one.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "fsrs_cards", primaryKeys = ["item_type", "item_id"])
data class FsrsCard(
    val item_type: String,
    val item_id: Long,
    val stability: Double?,
    val difficulty: Double?,
    /** ISO-8601. Text, per the spec — sortable, timezone-explicit, and readable in an export. */
    val due: String?,
    val last_review: String?,
    val state: Int?,
    val reps: Int?,
    val lapses: Int?,
)

@Entity(tableName = "reviews")
data class Review(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val item_type: String,
    val item_id: Long,
    val rating: Int,
    val ts: String,
    val elapsed_ms: Long?,
)

@Entity(tableName = "mistakes")
data class Mistake(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val item_type: String,
    val item_id: Long,
    val subject_id: Long?,
    val chosen_idx: Int?,
    val ts: String,
    val resolved: Int = 0,
    /**
     * A6: consecutive correct re-drills toward resolution. Reset to 0 on a wrong answer; the item is
     * `resolved = 1` once it reaches the re-drill threshold N (added in schema v2, MIGRATION_1_2).
     */
    val resolved_count: Int = 0,
)

@Entity(tableName = "stars", primaryKeys = ["item_type", "item_id"])
data class Star(val item_type: String, val item_id: Long, val ts: String)

@Entity(tableName = "item_tags", primaryKeys = ["item_type", "item_id", "tag_key"])
data class ItemTag(val item_type: String, val item_id: Long, val tag_key: String, val ts: String)

@Entity(tableName = "subject_mastery")
data class SubjectMastery(
    @PrimaryKey val subject_id: Long,
    val mastery: Double?,
    val updated: String?,
)

/**
 * `sessions` in the spec; named StudySession in Kotlin — `Session` is overloaded to the point of noise.
 * A7 cycle 3 (P2) grows it into a **named, resumable study session**: [name] (auto → editable),
 * [config_json] (the picked filters/mode), [cursor] (resume position), [updated], [archived].
 * The original v1 columns are untouched.
 */
@Entity(tableName = "sessions")
data class StudySession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String?,
    val started: String?,
    val ended: String?,
    val seen: Int?,
    val correct: Int?,
    val name: String? = null,
    val config_json: String? = null,
    // defaultValue matches the migration's `DEFAULT 0`, so a fresh-install schema is byte-identical to a
    // migrated one (Room tolerates a mismatch, but keeping them equal removes a real footgun).
    @ColumnInfo(defaultValue = "0") val cursor: Int = 0,
    val updated: String? = null,
    @ColumnInfo(defaultValue = "0") val archived: Int = 0,
)

/**
 * One MCQ answer (A7 cycle 3): [correct] 0/1 and the [subject_id] denormalised at answer time (the
 * pack attributes a subject to some MCQs; null otherwise). This is the accuracy **denominator** the
 * old code lacked — before this, only *wrong* answers were stored (as `mistakes`), so "% correct" had
 * no base. Powers subject accuracy and accuracy-over-time. Append-only.
 */
@Entity(tableName = "mcq_attempts")
data class McqAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val item_id: Long,
    val subject_id: Long?,
    val correct: Int,
    val ts: String,
)

/**
 * A user's per-line mark inside a topic dossier (A7 cycle 3, P3). Anchored to the topic's content
 * [topic_key] (`topic_pages.stable_key`) + a [line_hash] of the normalised line — NOT a character
 * offset — so a rebuilt/re-imported pack keeps the mark attached even if the body reflows slightly.
 * [kind] = `star` | `highlight`.
 */
@Entity(tableName = "line_marks", primaryKeys = ["topic_key", "section_ord", "line_hash"])
data class LineMark(
    val topic_key: String,
    val section_ord: Int,
    val line_hash: String,
    val kind: String,
    val ts: String,
)

/** A named saved filter — a custom deck (A7 cycle 3, P4). [config_json] is a serialised FeedFilter. */
@Entity(tableName = "decks")
data class Deck(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val config_json: String,
    val ts: String,
)

@Entity(tableName = "blob_reads")
data class BlobRead(
    @PrimaryKey val blob_id: Long,
    val read_at: String?,
    val quizzed_at: String?,
    val reread_due: String?,
)

@Entity(tableName = "feed_state")
data class FeedState(@PrimaryKey val key: String, val value: String?)

@Entity(tableName = "settings")
data class Setting(@PrimaryKey val key: String, val value: String?)

/**
 * A1 needs only enough of this to prove the DB exists, persists, and survives a re-import.
 * A2–A7 grow it; the schema above is already complete per the spec, so growth is DAO-only.
 */
@Dao
interface ProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCard(card: FsrsCard)

    @Query("SELECT * FROM fsrs_cards WHERE item_type = :type AND item_id = :id")
    suspend fun card(type: String, id: Long): FsrsCard?

    @Query("SELECT COUNT(*) FROM fsrs_cards")
    suspend fun cardCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReview(review: Review)

    @Query("SELECT COUNT(*) FROM reviews")
    suspend fun reviewCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun star(star: Star)

    @Query("SELECT COUNT(*) FROM stars")
    suspend fun starCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(setting: Setting)

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun setting(key: String): String?

    // ---- A2: mistakes, stars, tags -------------------------------------------------------------

    /** A wrong MCQ answer (§8 A2 → A6's ledger). Written the moment the user picks a wrong option. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMistake(mistake: Mistake)

    /** A7 cycle 3: log an MCQ answer (right or wrong) so accuracy has a real denominator. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMcqAttempt(attempt: McqAttempt)

    @Query("DELETE FROM stars WHERE item_type = :type AND item_id = :id")
    suspend fun unstar(type: String, id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM stars WHERE item_type = :type AND item_id = :id)")
    suspend fun isStarred(type: String, id: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTag(tag: ItemTag)

    @Query("DELETE FROM item_tags WHERE item_type = :type AND item_id = :id AND tag_key = :tagKey")
    suspend fun removeTag(type: String, id: Long, tagKey: String)

    @Query("SELECT tag_key FROM item_tags WHERE item_type = :type AND item_id = :id")
    suspend fun tagsFor(type: String, id: Long): List<String>

    /** Blob read-tracking (§8 A2 step 9: schedule the quiz-back + a re-read). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBlobRead(read: BlobRead)

    @Query("SELECT * FROM blob_reads WHERE blob_id = :blobId")
    suspend fun blobRead(blobId: Long): BlobRead?

    // ---- A3: feed-ordering engine state (the recent-type ring buffer, drip counters, cursors) -------

    @Query("SELECT value FROM feed_state WHERE key = :key")
    suspend fun feedState(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFeedState(state: FeedState)

    // ---- A6: mistake re-drill resolution + stats aggregates ----------------------------------------

    /**
     * A correct re-drill: advance the item's single unresolved mistake toward resolution; mark it
     * `resolved` once it reaches [n] consecutive corrects. Returns rows affected (0 = item wasn't a
     * pending mistake). CASE reads the OLD `resolved_count`, so new_count = old+1 and resolved = (old+1≥n).
     */
    @Query(
        """UPDATE mistakes SET resolved_count = resolved_count + 1,
              resolved = CASE WHEN resolved_count + 1 >= :n THEN 1 ELSE 0 END
           WHERE item_type = :type AND item_id = :id AND resolved = 0"""
    )
    suspend fun advanceResolution(type: String, id: Long, n: Int): Int

    /** A wrong answer restarts the drill: reset an existing unresolved mistake to 0/N. Returns rows hit. */
    @Query("UPDATE mistakes SET resolved_count = 0, ts = :ts, chosen_idx = :chosen WHERE item_type = :type AND item_id = :id AND resolved = 0")
    suspend fun resetUnresolved(type: String, id: Long, ts: String, chosen: Int?): Int

    @Query("SELECT COUNT(*) FROM mistakes WHERE resolved = 0")
    suspend fun unresolvedMistakeCount(): Int

    /** Resolved mistakes — a "recovered weak spot", worth XP. */
    @Query("SELECT COUNT(*) FROM mistakes WHERE resolved = 1")
    suspend fun resolvedMistakeCount(): Int

    /** All review timestamps (ISO), newest first — the streak + daily-goal source. */
    @Query("SELECT ts FROM reviews ORDER BY ts DESC")
    suspend fun reviewTimestamps(): List<String>

    @Query("SELECT COUNT(*) FROM reviews")
    suspend fun totalReviews(): Int

    /** Lapses (rated Again = 1) — the accuracy denominator's failures. */
    @Query("SELECT COUNT(*) FROM reviews WHERE rating = 1")
    suspend fun totalLapses(): Int

    // ---- A7 cycle 3: named study sessions (P2) -----------------------------------------------------

    /** Create a session; returns its new row id (used as the resume key). */
    @Insert
    suspend fun insertSession(session: StudySession): Long

    /** Live sessions, most-recently-touched first (the Home "Your sessions" list). */
    @Query("SELECT * FROM sessions WHERE archived = 0 ORDER BY updated DESC, id DESC")
    suspend fun activeSessions(): List<StudySession>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun sessionById(id: Long): StudySession?

    /** Persist a session's resume position (cursor) + seen count as the user studies it. */
    @Query("UPDATE sessions SET cursor = :cursor, seen = :seen, updated = :updated WHERE id = :id")
    suspend fun updateSessionProgress(id: Long, cursor: Int, seen: Int, updated: String)

    @Query("UPDATE sessions SET name = :name, updated = :updated WHERE id = :id")
    suspend fun renameSession(id: Long, name: String, updated: String)

    @Query("UPDATE sessions SET archived = 1, updated = :updated WHERE id = :id")
    suspend fun archiveSession(id: Long, updated: String)

    // ---- A7 cycle 3: custom decks (P4) --------------------------------------------------------------

    @Insert
    suspend fun insertDeck(deck: Deck): Long

    @Query("SELECT * FROM decks ORDER BY ts DESC")
    suspend fun decks(): List<Deck>

    @Query("DELETE FROM decks WHERE id = :id")
    suspend fun deleteDeck(id: Long)

    // ---- A7 cycle 3: topic + line marks in the Library (P3) ----------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addLineMark(mark: LineMark)

    @Query("DELETE FROM line_marks WHERE topic_key = :topicKey AND section_ord = :sectionOrd AND line_hash = :lineHash")
    suspend fun removeLineMark(topicKey: String, sectionOrd: Int, lineHash: String)

    @Query("SELECT * FROM line_marks WHERE topic_key = :topicKey")
    suspend fun lineMarks(topicKey: String): List<LineMark>

    /** Ids of starred items of a type — e.g. the "★ Starred" topics filter in the Library. */
    @Query("SELECT item_id FROM stars WHERE item_type = :type")
    suspend fun starredIds(type: String): List<Long>

    /** Leech detection (P4): items lapsed at least [n] times — the cards you keep failing. */
    @Query("SELECT COUNT(*) FROM fsrs_cards WHERE lapses >= :n")
    suspend fun leechCount(n: Int): Int
}
