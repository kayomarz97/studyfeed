package dev.kayomarz.studyfeed.progress

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
    ],
    version = 1,
    exportSchema = false, // A1: no migrations to test against yet. Revisit when version 2 lands.
)
abstract class ProgressDatabase : RoomDatabase() {
    abstract fun dao(): ProgressDao

    companion object {
        /**
         * Opens (creating on first use) the progress DB for [examCode].
         *
         * **No `fallbackToDestructiveMigration`, ever.** That flag silently drops the user's data when a
         * migration is missing — precisely the catastrophe this whole two-DB design exists to prevent.
         * A missing migration must crash loudly in development, not wipe six weeks of revision in the
         * user's hand.
         */
        fun open(context: Context, examCode: String): ProgressDatabase =
            Room.databaseBuilder(context, ProgressDatabase::class.java, fileName(examCode)).build()

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

/** `sessions` in the spec; named StudySession in Kotlin — `Session` is overloaded to the point of noise. */
@Entity(tableName = "sessions")
data class StudySession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String?,
    val started: String?,
    val ended: String?,
    val seen: Int?,
    val correct: Int?,
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
}
