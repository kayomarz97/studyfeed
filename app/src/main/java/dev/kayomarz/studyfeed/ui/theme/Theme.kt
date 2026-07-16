package dev.kayomarz.studyfeed.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The app's theme.
 *
 * Dynamic colour (Material You) is deliberately NOT used: it would let the user's wallpaper
 * repaint the feed, which breaks the AMOLED true-black contract (§0) and makes the importance
 * indicators' colour coding (A2/A5) unpredictable. Colour carries meaning in this app, so it
 * cannot be sampled from a wallpaper.
 *
 * A0 ships dark only. The light/dim toggle is A7 — hence the [darkTheme] parameter exists but is
 * hardcoded; wire it to DataStore there, don't sneak it in earlier.
 */
@Composable
fun StudyFeedTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AmoledDarkColorScheme,
        content = content,
    )
}

private val AmoledDarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = AmoledBlack,
    primaryContainer = AccentBlueDark,
    onPrimaryContainer = AccentBlue,
    background = AmoledBlack,
    onBackground = AmoledOnSurface,
    surface = AmoledBlack,
    onSurface = AmoledOnSurface,
    surfaceVariant = AmoledSurfaceVariant,
    onSurfaceVariant = AmoledOnSurfaceMuted,
    surfaceContainer = AmoledSurface,
    surfaceContainerHigh = AmoledSurfaceVariant,
    error = ErrorRed,
    onError = AmoledBlack,
)
