package dev.kayomarz.studyfeed.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The reader text-size multiplier (user setting, 2026-07-19). Provided once at the app root from
 * `reader.textScale`; consumed only by the **reader** surfaces — topic pages ([dossier][dev.kayomarz.
 * studyfeed.ui.dossier.DossierScreen]) and passage (blob) bodies — which scale `LocalDensity.fontScale`
 * by this factor. `1f` = default. MCQ/flashcard layouts deliberately ignore it (scaling them would break
 * option/rating layout), so this is scoped, not global.
 */
val LocalReaderScale = staticCompositionLocalOf { 1f }
