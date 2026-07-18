package dev.kayomarz.studyfeed.progress

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.Closeable
import java.io.File

/**
 * The cross-database read path: the pack `ATTACH`ed beside the progress DB, joined in SQL
 * (spec/SPEC.md §2; plan D4, proven by `AttachSpikeTest`).
 *
 * ## Why this class exists at all, and why it must not be "simplified" into Room
 *
 * SPEC.md §2 requires the app to ATTACH the read-only content DB beside the read-write progress DB and
 * join across them — that is how `priority = importance × weakness` gets composed on the phone without
 * loading 32,957 rows into memory. But:
 *
 *  - **SQLite scopes `ATTACH` to a single connection.** Room **pools** connections, so an ATTACH issued
 *    on one pooled connection is invisible to a query served by another. It would work in testing and
 *    fail under load, which is the worst possible failure shape.
 *  - **Android's own `SQLiteDatabase.enableWriteAheadLogging()` javadoc:** *"If the database has any
 *    attached databases, then execution of queries in parallel is not possible."* Room enables WAL by
 *    default.
 *  - **No official Room documentation addresses Room + ATTACH at all** — a confirmed gap, not an
 *    oversight in our reading.
 *
 * So this holds **one connection we own and never hand back to a pool** — the ATTACH cannot vanish —
 * and it is **opened READ-ONLY**, so it cannot contend with Room's writer. Room owns every write; this
 * owns every cross-DB read. Under WAL, concurrent readers coexist with a single writer by design rather
 * than by luck.
 *
 * **Verified on a real Android image** (`AttachSpikeTest`, 6/6): WAL confirmed, the JOIN works, the
 * reader sees Room's commits made *after* the ATTACH (no stale snapshot), Room writes freely while this
 * is open, the ATTACH survives 100 queries, and the OS refuses writes on this connection.
 *
 * ⚠ **If this ever misbehaves, do NOT disable WAL to "fix" it** — that degrades every write in the app
 * to buy back one read path. The sanctioned fallback is joining in Kotlin (`fsrs_cards` is sparse) plus
 * a spec amendment.
 *
 * ⚠ **Throughput is unmeasured.** The javadoc's "no parallel queries" warning is a real throughput note
 * about this connection; the spike proves correctness only, on an emulator. Measure on a real device
 * before assuming the feed scrolls smoothly at 32,957 items.
 */
class FeedReader private constructor(private val db: SQLiteDatabase) : Closeable {

    companion object {
        private const val PACK_ALIAS = "pack"

        /**
         * Opens progress read-only with [packDb] attached.
         *
         * Read-only is load-bearing, not caution: it is *why* this cannot contend with Room's writer.
         * The OS enforces it (proven in the spike), so the guarantee does not rest on our discipline.
         *
         * @return null if either file is missing or the ATTACH fails — the caller shows an empty feed
         *   rather than crashing. A missing pack is a normal state (fresh install, nothing imported).
         */
        fun open(progressDb: File, packDb: File): FeedReader? {
            if (!progressDb.isFile || !packDb.isFile) return null
            return try {
                val db = SQLiteDatabase.openDatabase(progressDb.path, null, SQLiteDatabase.OPEN_READONLY)
                // Bound parameters are not permitted in ATTACH; the path is app-private and
                // app-constructed (filesDir), never user-supplied text, so it cannot be injected.
                db.execSQL("ATTACH DATABASE '${packDb.path.replace("'", "''")}' AS $PACK_ALIAS")
                FeedReader(db)
            } catch (e: SQLiteException) {
                null
            }
        }
    }

    /**
     * A feed item joined with whatever progress exists for it.
     *
     * [stability] is null for an item never seen — which is the common case early on and is exactly why
     * `fsrs_cards` is sparse and a LEFT JOIN is the right shape.
     */
    data class FeedRow(
        val itemType: String,
        val itemId: Long,
        val importance: Int,
        val importanceScore: Double,
        val subjectId: Long?,
        val stability: Double?,
        val due: String?,
    )

    /**
     * The join SPEC.md §2 describes, and the reason this class exists.
     *
     * LEFT JOIN because most items have no progress row yet: an inner join would show a new user an
     * empty feed, which is precisely backwards.
     */
    fun feed(limit: Int = 50, offset: Int = 0): List<FeedRow> = try {
        db.rawQuery(
            """
            SELECT f.item_type, f.id, f.importance, f.importance_score, f.subject_id,
                   c.stability, c.due
            FROM $PACK_ALIAS.feed_items f
            LEFT JOIN main.fsrs_cards c
                   ON c.item_type = f.item_type AND c.item_id = f.id
            ORDER BY f.importance DESC, f.importance_score DESC, f.id
            LIMIT ? OFFSET ?
            """.trimIndent(),
            arrayOf(limit.toString(), offset.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(FeedRow(
                        itemType = c.getString(0).orEmpty(),
                        itemId = c.getLong(1),
                        importance = c.getInt(2),
                        importanceScore = c.getDouble(3),
                        subjectId = if (c.isNull(4)) null else c.getLong(4),
                        stability = if (c.isNull(5)) null else c.getDouble(5),
                        due = c.getString(6),
                    ))
                }
            }
        }
    } catch (e: SQLiteException) {
        emptyList()
    }

    /** Total playable items in the attached pack. Proves the ATTACH is live; A2 uses it for paging. */
    fun feedSize(): Int = try {
        db.rawQuery("SELECT COUNT(*) FROM $PACK_ALIAS.feed_items", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    } catch (e: SQLiteException) {
        0
    }

    /** How many items the user has actually touched — the cross-DB fact that justifies the ATTACH. */
    fun seenCount(): Int = try {
        db.rawQuery(
            """
            SELECT COUNT(*) FROM $PACK_ALIAS.feed_items f
            JOIN main.fsrs_cards c ON c.item_type = f.item_type AND c.item_id = f.id
            """.trimIndent(), null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    } catch (e: SQLiteException) {
        0
    }

    override fun close() {
        runCatching { db.close() }
    }
}
