package dev.kayomarz.studyfeed.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * AMOLED palette floor (BUILD_PROMPT §0 `theme_default: amoled_dark`, §8 A7).
 *
 * True black is not a style choice here: on an AMOLED panel a #000000 pixel is powered off, so a
 * black background is a battery decision as much as a visual one. "Very dark grey" (#121212, the
 * Material default) lights every pixel and looks grey next to real black.
 *
 * A0 defines only what the skeleton needs. The full scheme — restrained bright fills, the
 * high-contrast text ramp, the light/dim alternative — is A7.
 */
internal val AmoledBlack = Color(0xFF000000)

/** Raised surfaces: barely-there grey, enough to separate a card from the void without glowing. */
internal val AmoledSurface = Color(0xFF0A0A0A)
internal val AmoledSurfaceVariant = Color(0xFF161616)

/** Text/icons. Pure white on pure black is harsh at night; this is a notch down. */
internal val AmoledOnSurface = Color(0xFFE6E6E6)
internal val AmoledOnSurfaceMuted = Color(0xFF9A9A9A)

/** Restrained accent — used sparingly, never as a large fill. */
internal val AccentBlue = Color(0xFF8AB4F8)
internal val AccentBlueDark = Color(0xFF0B1B33)
internal val ErrorRed = Color(0xFFF2B8B5)
