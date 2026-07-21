package dev.kayomarz.studyfeed.ui.feed

/**
 * Which honest empty-state message a section should show when it has nothing to render.
 *
 * Users load their OWN packs, and a lean pack may not contain a concept map, topic pages, passages,
 * and so on (§0 `multi_pack`; [dev.kayomarz.studyfeed.pack.PackCapabilities]). A section that a pack
 * cannot fill must SAY so — not render a blank or broken screen. There are two very different reasons
 * a section is empty, and they read differently to the user:
 *
 *  - **[ABSENT]** — the loaded pack never had this content. Factual: *"This pack doesn't include X."*
 *  - **[NOTHING_MATCHES]** — the pack has it, but the active filter/search excludes all of it. *"Clear a filter."*
 *  - **[NOTHING_DUE]** — the pack has it, nothing is filtered, you're simply caught up. Encouraging.
 */
enum class EmptyCase { ABSENT, NOTHING_MATCHES, NOTHING_DUE }

object EmptyStates {
    /**
     * Decide which empty message a section shows.
     *
     * @param capabilityPresent does the loaded pack contain this kind of content at all? Resolved from
     *   [dev.kayomarz.studyfeed.pack.PackCapabilities], which is **rows-authoritative** per pack.
     * @param filterActive is a filter or search currently narrowing the section?
     *
     * Pure on purpose: the "which message" decision is the one piece worth unit-testing without a
     * device, so a lean pack can never regress into a blank screen unnoticed.
     */
    fun decide(capabilityPresent: Boolean, filterActive: Boolean): EmptyCase = when {
        !capabilityPresent -> EmptyCase.ABSENT
        filterActive -> EmptyCase.NOTHING_MATCHES
        else -> EmptyCase.NOTHING_DUE
    }
}
