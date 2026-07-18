package dev.kayomarz.studyfeed.progress

/**
 * The keys of the `settings` table rows the app reads/writes (A7). One place so the writer (the Settings
 * screen) and the readers (the feed engine, the dashboard) can never drift on a spelling.
 *
 * Values are plain strings in `settings.value`; each reader parses + range-clamps its own. These rows ride
 * along in the §11e progress export/import, so a restored user keeps their tuned deadline/intensity.
 */
object SettingsKeys {
    /** The editable MCQ finish line, ISO date (`feed.deadline`). Drives the daily new-MCQ drip pace. */
    const val DEADLINE = "feed.deadline"

    /** Re-drill: correct this many consecutive times to resolve a mistake (`redrill.n`, default 3). */
    const val REDRILL_N = "redrill.n"

    /** Feed intensity — target share of NEW cards in the blend, percent (`feed.newRatioPct`). */
    const val NEW_RATIO_PCT = "feed.newRatioPct"

    /** Feed intensity — max overdue-backlog items surfaced per app-open (`feed.dueBurst`). */
    const val DUE_BURST = "feed.dueBurst"

    /** After rating a card, advance to the next one (`true`) or just highlight the rating and stay. */
    const val ADVANCE_AFTER_RATING = "feed.advanceAfterRating"
}
