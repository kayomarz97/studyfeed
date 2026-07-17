package dev.kayomarz.studyfeed.feed

/**
 * A fully-loaded feed card — one item plus everything a composable needs to render it (BUILD_PROMPT
 * §8 A2). Assembled by [FeedRepository] from the pack (content) joined to progress (FSRS state).
 *
 * **Five types, not six (PATCH 04):** `mcq`, `flashcard`, `reel`, `blob`, `interlink`. There is no
 * standalone `image` card — figures are [figure] attachments on the card that owns them.
 *
 * Every card carries the shared "chrome" §8 A2 requires on all of them: the 1–5 [importance] indicator,
 * the PYQ badge ([pyqYears]), and the FSRS [due]/[stability] pulled across the ATTACH.
 */
sealed interface FeedCard {
    val itemType: String
    val itemId: Long
    val importance: Int
    val importanceScore: Double
    val importanceSignals: ImportanceSignals?
    val subjectName: String?
    /** Non-empty only for a PYQ MCQ; drives the "Asked 2019 · 2023" badge. */
    val pyqYears: List<Int>
    /** FSRS due date (ISO-8601) if this item has been seen; null = never studied. */
    val due: String?
    val stability: Double?

    /** A figure attached to this card (via the item's atom, or the MCQ directly). Null on most cards. */
    data class Figure(val path: String, val captionMd: String?)

    data class Mcq(
        override val itemType: String = "mcq",
        override val itemId: Long,
        override val importance: Int,
        override val importanceScore: Double,
        override val importanceSignals: ImportanceSignals?,
        override val subjectName: String?,
        override val pyqYears: List<Int>,
        override val due: String?,
        override val stability: Double?,
        val stemMd: String,
        val options: List<String>,      // 2–5 options; EMQ-style keys already quarantined at pack time
        val correctIdx: Int,
        val explanationMd: String,
        val perOptionWhyWrong: List<String?>, // parallel to options; null where the pack has none
        val keyConflict: Boolean,
        val keyConflictNote: String?,
        val figure: Figure?,
    ) : FeedCard

    data class Flashcard(
        override val itemType: String = "flashcard",
        override val itemId: Long,
        override val importance: Int,
        override val importanceScore: Double,
        override val importanceSignals: ImportanceSignals?,
        override val subjectName: String?,
        override val pyqYears: List<Int> = emptyList(),
        override val due: String?,
        override val stability: Double?,
        val frontMd: String,
        val backMd: String,
        val isCloze: Boolean,
        val figure: Figure?,
    ) : FeedCard

    data class Reel(
        override val itemType: String = "reel",
        override val itemId: Long,
        override val importance: Int,
        override val importanceScore: Double,
        override val importanceSignals: ImportanceSignals?,
        override val subjectName: String?,
        override val pyqYears: List<Int> = emptyList(),
        override val due: String?,
        override val stability: Double?,
        val hookMd: String,
        val detailMd: String,
        val figure: Figure?,
    ) : FeedCard

    data class Blob(
        override val itemType: String = "blob",
        override val itemId: Long,
        override val importance: Int,
        override val importanceScore: Double,
        override val importanceSignals: ImportanceSignals?,
        override val subjectName: String?,
        override val pyqYears: List<Int> = emptyList(),
        override val due: String?,
        override val stability: Double?,
        val title: String?,
        val bodyMd: String,
        val estReadSeconds: Int?,
        /** The quiz-back for this passage (§8 A2). Null if the pack shipped none for this blob. */
        val quiz: BlobQuiz?,
        /**
         * True when the A3 engine re-surfaced this passage as its **delayed quiz-back** (a few cards
         * after it was first read, §8 A3 rule 3) — the card can open straight at its quiz. False for a
         * first read.
         */
        val openAtQuiz: Boolean = false,
    ) : FeedCard

    /** A passage's check-your-understanding question (`blob_quizzes`). */
    data class BlobQuiz(
        val questionMd: String,
        val options: List<String>,
        val correctIdx: Int,
        val answerMd: String,
    )

    data class Interlink(
        override val itemType: String = "interlink",
        override val itemId: Long,
        override val importance: Int,
        override val importanceScore: Double,
        override val importanceSignals: ImportanceSignals?,
        override val subjectName: String?,
        override val pyqYears: List<Int> = emptyList(),
        override val due: String?,
        override val stability: Double?,
        val questionMd: String,
        val answerMd: String,
    ) : FeedCard
}

/**
 * The "why is this important" breakdown (§8 A2: tap the indicator → a sheet from
 * `importance_signals_json`). Eight weighted signals per SCORING.md; each is a normalised contribution.
 *
 * Rendered as human phrases by the UI ("2 past papers · 4 distinct sources · hub concept"), never as
 * raw numbers — but the numbers are what the pack ships, so they are what we carry.
 */
data class ImportanceSignals(
    val pyq: Double,
    val corrob: Double,
    val freq: Double,
    val emphasis: Double,
    val blueprint: Double,
    val centrality: Double,
    val tier: Double,
    val trend: Double,
) {
    /** Signals in descending contribution, labelled — the order the "why" sheet should present them. */
    fun ranked(): List<Pair<String, Double>> = listOf(
        "Past-paper history" to pyq,
        "Corroborating sources" to corrob,
        "Frequency" to freq,
        "Book emphasis" to emphasis,
        "Blueprint weight" to blueprint,
        "Concept centrality" to centrality,
        "Source tier" to tier,
        "Trend" to trend,
    ).filter { it.second > 0.0 }.sortedByDescending { it.second }
}
