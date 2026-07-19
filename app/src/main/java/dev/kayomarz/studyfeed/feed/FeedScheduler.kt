package dev.kayomarz.studyfeed.feed

import kotlin.math.min

/**
 * A3 — the feed **ordering engine** (BUILD_PROMPT §8 A3). A2 ordered the feed with a stateless SQL
 * `ORDER BY … LIMIT/OFFSET`; A3's rules are **stateful** and cannot be one SQL scan:
 *
 *  1. **FSRS due pool**, cross-type, most-urgent first.
 *  2. **Importance-weighted new items** (5★/PYQ first) for the among-equal / never-seen material.
 *  3. **Variety**: never more than [Config.maxSameTypeRun] of one type in a row; land a blob's
 *     quiz-back a few cards after it's read; sprinkle reels for pacing.
 *  4. **Daily new-item drip**: MCQs paced to an editable deadline (`remaining ÷ days-left`); non-MCQ
 *     new types ride along on a lighter budget.
 *  5. **Explicit sort/filter overrides this engine** — that path stays on [FeedRepository.page]. This
 *     class only composes the default (Smart / "For You") stream.
 *  6. A **recent-type ring buffer** + drip counters + keyset cursors persist in `feed_state`.
 *
 * ## Purity (so it tests without a device)
 *
 * This holds **no Android and no SQL**. It reads candidate pools through [Pools] and its persisted
 * state through [StateStore] — both injected. In `testDebugUnitTest` those are in-memory fakes, so the
 * whole engine (blend ratio, variety invariant, drip caps, blob-quiz delay) is proven on the JVM,
 * deterministically. In the app they are backed by [FeedRepository] and the progress DB.
 *
 * ## Locked policy (user, 2026-07-17) — see `.claude/plans/2026-07-17-a3-feed-ordering-engine.md`
 *
 * Blended **due-led** (~70/30 due:new, adaptive) · new MCQs paced to an **editable 8-week deadline** ·
 * overdue backlog **capped per app-open** ([Config.dueBurst]). All three numbers are A7-tunable knobs.
 */
class FeedScheduler(
    private val pools: Pools,
    private val store: StateStore,
    private val config: Config = Config(),
) {

    /**
     * A lightweight ordering unit. The heavy payload is loaded later ([FeedRepository.loadCards]) — the
     * engine only sorts, so it carries just what ordering needs plus the ids to fetch the payload.
     */
    data class Ref(
        val itemType: String,
        /** `feed_items.id` — the progress-side key (what FSRS/stars/tags are keyed by). */
        val itemId: Long,
        /** the type-table id (`mcqs.id`, `reels.id`, …) — the payload key. */
        val refId: Long,
        val importance: Int,
        val importanceScore: Double,
        val subjectId: Long?,
        /** Due pool only: how many days overdue (drives urgency; 0 for new/filler). */
        val overdueDays: Double = 0.0,
        val source: Source = Source.NEW_MCQ,
        /** A re-surfaced blob whose quiz-back is now landing (§8 A3 rule 3). */
        val openAtQuiz: Boolean = false,
    )

    enum class Source { DUE, NEW_MCQ, NEW_OTHER, FILLER, BLOB_QUIZ }

    /** Candidate pools — SQL in production, in-memory lists in tests. All ordering happens above this. */
    interface Pools {
        /** Due items (`fsrs_cards.due ≤ now` ∪ blob `reread_due ≤ now`), urgency-first, minus [exclude]. */
        fun due(nowIso: String, exclude: Set<Long>, limit: Int): List<Ref>
        /** Never-seen MCQs after the keyset cursor: importance tier first, then the [seed] within-tier order. */
        fun newMcqs(afterImp: Int?, afterId: Long?, seed: Int, limit: Int): List<Ref>
        /** Never-seen non-MCQ items after the keyset cursor: importance tier, then the within-tier [seed] order. */
        fun newOthers(afterImp: Int?, afterId: Long?, seed: Int, limit: Int): List<Ref>
        /** Seen-but-not-yet-due items (only used when due+new run dry, so the feed never dead-ends). */
        fun filler(nowIso: String, exclude: Set<Long>, limit: Int): List<Ref>
        /** Count of never-seen MCQs — the numerator of the deadline pace. */
        fun remainingNewMcqs(): Int
        /** A blob resolved as a Ref, to re-surface its quiz-back a few cards after reading. */
        fun blobRef(blobId: Long): Ref?
    }

    /** Persisted engine state (`feed_state` rows). */
    interface StateStore {
        fun load(): FeedState
        fun save(state: FeedState)
    }

    data class FeedState(
        val recentTypes: List<String> = emptyList(),
        val dripDate: String? = null,
        val newMcqToday: Int = 0,
        val newOtherToday: Int = 0,
        /** Keyset cursor = (importance tier, feed_items.id) of the last new item served, per pool. */
        val mcqCursorImp: Int? = null,
        val mcqCursorId: Long? = null,
        val otherCursorImp: Int? = null,
        val otherCursorId: Long? = null,
        /** Per-feed within-tier shuffle seed (0 = unset; the VM assigns one before first use). */
        val shuffleSeed: Int = 0,
        val pendingBlobQuizzes: List<Pending> = emptyList(),
        val globalIndex: Int = 0,
    ) {
        data class Pending(val blobId: Long, val landAt: Int)
    }

    data class Config(
        /** Target share of NEW cards in the blend when both pools are live (rest are due reviews). */
        val newRatioPct: Int = 30,
        /** Max overdue-backlog items surfaced per app-open (the anti-avalanche cap). */
        val dueBurst: Int = 60,
        /** Non-MCQ new daily budget = ceil(mcqBudget × this). Non-MCQ types are NOT deadline-bound. */
        val varietyRatio: Double = 0.4,
        /** Never emit more than this many of one type consecutively. */
        val maxSameTypeRun: Int = 2,
        /** Roughly every Nth card, prefer a new reel for pacing (if one is available + in budget). */
        val reelEvery: Int = 12,
        /** Land a blob's quiz-back this many cards after it was read. */
        val blobQuizDelay: Int = 4,
        /** Default deadline horizon when the user hasn't set one yet. */
        val defaultDeadlineDays: Int = 56,
    )

    // ---- per-app-open state (resets when a new scheduler is built = a new session) -------------------

    /** Items already emitted this app-open, so windows don't repeat a card (due pool is re-queried). */
    private val emitted = HashSet<Long>()

    /** Overdue-backlog items spent this app-open, against [Config.dueBurst]. */
    private var dueBurstUsed = 0

    /** Deterministic blend accumulator — crosses 100 to release a NEW card at [Config.newRatioPct]. */
    private var newAcc = 0

    /** Sub-blend inside NEW picks: crosses 100 to release a non-MCQ (else an MCQ). */
    private var otherAcc = 0

    /**
     * Schedule a blob's quiz-back to land ~[Config.blobQuizDelay] cards from now (§8 A3 rule 3). Called
     * when the user marks a passage read; the delayed re-surface is this engine's job (A2 deferred it).
     */
    fun scheduleBlobQuiz(blobId: Long) {
        val s = store.load()
        if (s.pendingBlobQuizzes.any { it.blobId == blobId }) return
        store.save(
            s.copy(pendingBlobQuizzes = s.pendingBlobQuizzes +
                FeedState.Pending(blobId, s.globalIndex + config.blobQuizDelay))
        )
    }

    /**
     * Compose the next [size] cards of the default feed.
     *
     * @param nowIso   ISO-8601 instant, for the due-pool boundary.
     * @param today    `yyyy-MM-dd`, to roll the daily drip counters over at midnight.
     * @param daysUntilDeadline  whole days from today to the (editable) MCQ finish line, ≥ 1. Past the
     *   deadline the caller passes 1, so the pace becomes "serve all remaining".
     */
    fun window(size: Int, nowIso: String, today: String, daysUntilDeadline: Int): List<Ref> {
        val w = Working(store.load(), today, nowIso, size, daysUntilDeadline)
        while (w.out.size < size) {
            if (!composeOne(w)) break   // pools exhausted — window is as full as it gets
        }
        store.save(w.snapshot())
        return w.out
    }

    /** One card. Returns false only when nothing at all can be emitted (feed fully drained). */
    private fun composeOne(w: Working): Boolean {
        // (0) A landed blob quiz-back is time-sensitive — place it first (variety permitting).
        emitLandedBlobQuiz(w)?.let { w.record(it); return true }

        val dueEligible = w.due.isNotEmpty() && dueBurstUsed < config.dueBurst
        val newEligible = w.mcqEligible() || w.otherEligible()

        // (1) Blend: release a NEW card when the accumulator crosses 100, else prefer a due review.
        newAcc += config.newRatioPct
        val wantNew = newAcc >= 100
        val ref = when {
            wantNew && newEligible -> { newAcc -= 100; pickNew(w) }
            dueEligible -> pickDue(w)
            newEligible -> { if (newAcc >= 100) newAcc -= 100; pickNew(w) }
            w.due.isNotEmpty() -> pickDue(w)             // over burst cap but nothing else → still review
            else -> pickFiller(w)
        } ?: fallback(w)                                  // chosen pool couldn't place → try everything

        if (ref == null) return false
        w.record(ref)
        return true
    }

    // ---- pickers ------------------------------------------------------------------------------------

    private fun pickDue(w: Working): Ref? =
        takeWithVariety(w.due, w::typeOk)?.also { dueBurstUsed++ }

    private fun pickFiller(w: Working): Ref? =
        pools.filler(w.nowIso, emitted, w.size).firstOrNull { w.typeOk(it.itemType) }

    /**
     * Choose a NEW card. MCQ is the deadline-critical backbone; a lighter sub-blend interleaves non-MCQ
     * types (with a reel sprinkled ~every [Config.reelEvery] cards for pacing). Cursors + daily counters
     * advance on the chosen type only.
     */
    private fun pickNew(w: Working): Ref? {
        val mcqOk = w.mcqEligible()
        val otherOk = w.otherEligible()
        if (!mcqOk && !otherOk) return null

        otherAcc += (config.varietyRatio * 100).toInt()
        val sprinkleReel = otherOk && w.globalIndex % config.reelEvery == 0
        val preferOther = otherOk && (sprinkleReel || otherAcc >= 100 || !mcqOk)

        if (preferOther) {
            val reel = if (sprinkleReel) takeReel(w.other, w::typeOk) else null
            val chosen = reel ?: takeWithVariety(w.other, w::typeOk)
            if (chosen != null) { w.onOtherChosen(chosen); return chosen }
            // no placeable non-MCQ → fall through to MCQ
        }
        if (mcqOk) {
            val chosen = takeWithVariety(w.mcq, w::typeOk)
            if (chosen != null) { w.onMcqChosen(chosen); return chosen }
        }
        // preferred MCQ but none placeable → try non-MCQ as a last new option
        if (otherOk) {
            val chosen = takeWithVariety(w.other, w::typeOk)
            if (chosen != null) { w.onOtherChosen(chosen); return chosen }
        }
        return null
    }

    private fun emitLandedBlobQuiz(w: Working): Ref? {
        val land = w.pending.firstOrNull { it.landAt <= w.globalIndex } ?: return null
        w.pending = w.pending.filterNot { it.blobId == land.blobId }
        val ref = pools.blobRef(land.blobId)
        return if (ref != null && ref.itemId !in emitted && w.typeOk(ref.itemType)) {
            ref.copy(source = Source.BLOB_QUIZ, openAtQuiz = true)
        } else {
            // couldn't place now (dedupe/variety) — nudge one card later, never lose it.
            w.pending = w.pending + FeedState.Pending(land.blobId, w.globalIndex + 1)
            null
        }
    }

    /**
     * Last resort when every picker was blocked (usually by variety at the pool heads). Relax the
     * **variety** constraint — but **never the budgets**: an over-daily-cap new item must not be forced
     * out, or the deadline pace is defeated (a hit cap should end today's feed, not dump the deck). Only
     * the anti-avalanche `dueBurst` is relaxed as the very last resort, since reviews should always be
     * available rather than leave the feed empty.
     */
    private fun fallback(w: Working): Ref? {
        if (dueBurstUsed < config.dueBurst) w.due.removeFirstOrNull()?.let { dueBurstUsed++; return it }
        if (w.mcqEligible()) w.mcq.removeFirstOrNull()?.let { w.onMcqChosen(it); return it }
        if (w.otherEligible()) w.other.removeFirstOrNull()?.let { w.onOtherChosen(it); return it }
        pools.filler(w.nowIso, emitted, w.size).firstOrNull()?.let { return it }
        w.due.removeFirstOrNull()?.let { dueBurstUsed++; return it }   // reviews beyond the burst cap
        return null                                                   // truly nothing in-budget → end
    }

    /** Pop the first ref in [pool] whose type passes [ok]; scan a few before giving up (keeps order). */
    private fun takeWithVariety(pool: ArrayDeque<Ref>, ok: (String) -> Boolean): Ref? {
        val scan = min(pool.size, 8)
        for (i in 0 until scan) if (ok(pool[i].itemType)) return pool.removeAt(i)
        return null
    }

    /** Pop the first reel in [pool] that passes variety — the "sprinkle reels" pacing rule. */
    private fun takeReel(pool: ArrayDeque<Ref>, ok: (String) -> Boolean): Ref? {
        val scan = min(pool.size, 16)
        for (i in 0 until scan) if (pool[i].itemType == "reel" && ok("reel")) return pool.removeAt(i)
        return null
    }

    /**
     * The mutable per-window working set. Bundles the pool queues, the ring buffer, daily counters and
     * cursors so [composeOne] and the pickers read/write one legible object instead of a dozen closed
     * vars. Snapshotted back to [FeedState] at the end of [window].
     */
    private inner class Working(
        base: FeedState, val today: String, val nowIso: String, val size: Int, daysUntilDeadline: Int,
    ) {
        private val mcqBudget = ceilDiv(pools.remainingNewMcqs(), daysUntilDeadline.coerceAtLeast(1))
        private val otherBudget = Math.ceil(mcqBudget * config.varietyRatio).toInt()

        val due = ArrayDeque(pools.due(nowIso, emitted, size * 3 + 16))
        val mcq = ArrayDeque(pools.newMcqs(base.mcqCursorImp, base.mcqCursorId, base.shuffleSeed, size * 3 + 16))
        val other = ArrayDeque(pools.newOthers(base.otherCursorImp, base.otherCursorId, base.shuffleSeed, size * 3 + 16))

        val out = ArrayList<Ref>(size)
        private val recent = ArrayDeque(base.recentTypes)
        // Roll daily counters over at date change.
        private var mcqToday = if (base.dripDate == today) base.newMcqToday else 0
        private var otherToday = if (base.dripDate == today) base.newOtherToday else 0
        var globalIndex = base.globalIndex; private set
        var pending = base.pendingBlobQuizzes
        private var mcqCursorImp = base.mcqCursorImp; private var mcqCursorId = base.mcqCursorId
        private var otherCursorImp = base.otherCursorImp; private var otherCursorId = base.otherCursorId
        private val shuffleSeed = base.shuffleSeed

        fun mcqEligible() = mcq.isNotEmpty() && mcqToday < mcqBudget
        fun otherEligible() = other.isNotEmpty() && otherToday < otherBudget

        fun typeOk(type: String): Boolean {
            val n = config.maxSameTypeRun
            if (recent.size < n) return true
            return !recent.toList().takeLast(n).all { it == type }
        }

        fun record(ref: Ref) {
            out += ref
            emitted += ref.itemId
            recent.addLast(ref.itemType)
            while (recent.size > 8) recent.removeFirst()
            globalIndex++
        }

        // Cursor keys on (importance tier, itemId) — the SAME columns the pool SQL orders/keysets by.
        fun onMcqChosen(r: Ref) { mcqToday++; mcqCursorImp = r.importance; mcqCursorId = r.itemId }
        fun onOtherChosen(r: Ref) {
            otherToday++; otherAcc -= 100; otherCursorImp = r.importance; otherCursorId = r.itemId
        }

        fun snapshot() = FeedState(
            recentTypes = recent.toList(), dripDate = today,
            newMcqToday = mcqToday, newOtherToday = otherToday,
            mcqCursorImp = mcqCursorImp, mcqCursorId = mcqCursorId,
            otherCursorImp = otherCursorImp, otherCursorId = otherCursorId,
            shuffleSeed = shuffleSeed,
            pendingBlobQuizzes = pending, globalIndex = globalIndex,
        )
    }

    private fun ceilDiv(a: Int, b: Int): Int = if (b <= 0) a else (a + b - 1) / b
}
