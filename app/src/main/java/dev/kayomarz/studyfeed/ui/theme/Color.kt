package dev.kayomarz.studyfeed.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The "clinical night" palette (BUILD_PROMPT §0 `theme_default: amoled_dark`, §8 A7; **A7 cycle-2
 * redesign 2026-07-18, user-approved**).
 *
 * True black is not a style choice: on an AMOLED panel a #000000 pixel is powered off, so a black
 * background is a battery decision as much as a visual one. Colour is added **on top** of that black —
 * one confident iris accent used sparingly, coral for the loud PYQ badge (kept distinct from the amber
 * importance ramp), and muted per-content-type hues. Neutrals are cool-biased charcoals (chosen, not a
 * default mid-grey). No gradient-slop; boldness spent in one place.
 */
internal val AmoledBlack = Color(0xFF000000)

/** Raised surfaces: cool near-black charcoals, enough to separate a card from the void without glowing. */
internal val AmoledSurface = Color(0xFF0E1015)
internal val AmoledSurfaceVariant = Color(0xFF151820)
internal val AmoledSurfaceHigh = Color(0xFF1D212B)

/** Text/icons. Pure white on pure black is harsh at night; this is a notch down, cool-biased. */
internal val AmoledOnSurface = Color(0xFFE9EDF3)
internal val AmoledOnSurfaceMuted = Color(0xFF949CAB)

/** Borders / hairlines. */
internal val AmoledOutline = Color(0xFF2F3644)
internal val AmoledOutlineVariant = Color(0xFF242A36)

/** The one confident accent — iris. Used flat and sparingly, never as a big gradient fill. */
internal val Iris = Color(0xFF6E72E8)
internal val IrisContainer = Color(0xFF23264A)
internal val IrisOnContainer = Color(0xFFAAB0FF)
internal val ErrorRed = Color(0xFFE5675F)

/**
 * App accent colours referenced across screens (public within the module) — the loud PYQ coral and the
 * four muted content-type hues used by the Home tiles and card eyebrows. Distinct from the importance
 * ramp in [dev.kayomarz.studyfeed.ui.feed.Importance], which is intentionally unchanged.
 */
object AppColor {
    val PyqCoral = Color(0xFFF2705B)   // loud PYQ — warm, distinct from importance amber
    val Mcq = Color(0xFF6E72E8)        // iris
    val Deck = Color(0xFFB07CD6)       // plum — flashcards & reels
    val Passage = Color(0xFF3FA9A0)    // teal
    val Review = Color(0xFFE0A54A)     // amber — due/mistakes/starred
    val CorrectGreen = Color(0xFF5FBE7E)
    val MapTeal = Color(0xFF3FA9A0)
}
