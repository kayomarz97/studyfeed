package dev.kayomarz.studyfeed.feed

/**
 * A filter + sort over the feed (BUILD_PROMPT §8 A2). Turned into SQL by [FeedRepository].
 *
 * **The feed's default order is Smart, but the ordering ENGINE is A3** (standing order #2). A2 provides
 * the sort *options* the spec lists and a straightforward implementation of each; A3 replaces
 * [Sort.SMART]'s logic with its due-pool/variety engine. Everything else here is stable.
 */
data class FeedQuery(
    val filter: FeedFilter = FeedFilter(),
    val sort: Sort = Sort.SMART,
) {
    enum class Sort { SMART, IMPORTANCE_DESC, IMPORTANCE_ASC, PRIORITY, RANDOM, SUBJECT }
}

/**
 * Which items the feed shows. All fields combine with AND (an unset field is "no constraint").
 *
 * The chips in §8 A2 map here: "5★ only" → [importanceExact]=5; "4★+" → [importanceMin]=4; "PYQ only" →
 * [pyqOnly]; "MCQs only"/"Flashcards only" → [itemType]; "Starred" → [starredOnly]; "My mistakes" →
 * [mistakesOnly]; "Read only" → [itemType]="blob" (the passages you read); Subject ▾ → [subjectId];
 * Tag ▾ → [tagKey].
 */
data class FeedFilter(
    val itemType: String? = null,
    /**
     * Restrict to this SET of item types (the A7 study modes: "Recall only" → [mcq,flashcard], "Skim" →
     * [reel,blob]). Combines with AND alongside [itemType]; an empty/null set is "no constraint".
     */
    val itemTypesIn: List<String>? = null,
    /** Exactly this importance (the "5★ only" chip). */
    val importanceExact: Int? = null,
    /** At least this importance (the "4★+" chip). */
    val importanceMin: Int? = null,
    val pyqOnly: Boolean = false,
    val starredOnly: Boolean = false,
    /** Unresolved items in the mistakes ledger. */
    val mistakesOnly: Boolean = false,
    val subjectId: Long? = null,
    val tagKey: String? = null,
) {
    val isAll: Boolean
        get() = itemType == null && itemTypesIn.isNullOrEmpty() && importanceExact == null &&
            importanceMin == null && !pyqOnly && !starredOnly && !mistakesOnly && subjectId == null &&
            tagKey == null
}
