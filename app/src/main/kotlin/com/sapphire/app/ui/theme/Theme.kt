package com.sapphire.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Sapphire theme — dark-first by design (PRD: research-terminal + editorial hybrid).
 * Light mode is intentionally not provided; the whole identity is built around the
 * charcoal canvas and the single sapphire accent.
 */
private val SapphireColorScheme = darkColorScheme(
    primary = SapphirePalette.Accent,
    onPrimary = Color.White,
    primaryContainer = SapphirePalette.AccentDeep,
    onPrimaryContainer = SapphirePalette.OnInk,
    secondary = SapphirePalette.AccentBright,
    onSecondary = Color.White,
    secondaryContainer = SapphirePalette.InkRaised,
    onSecondaryContainer = SapphirePalette.OnInk,
    tertiary = SapphirePalette.AccentBright,
    onTertiary = Color.White,
    background = SapphirePalette.Ink,
    onBackground = SapphirePalette.OnInk,
    surface = SapphirePalette.Ink,
    onSurface = SapphirePalette.OnInk,
    surfaceVariant = SapphirePalette.InkElevated,
    onSurfaceVariant = SapphirePalette.OnInkMuted,
    surfaceTint = SapphirePalette.Accent,
    inverseSurface = SapphirePalette.OnInk,
    inverseOnSurface = SapphirePalette.Ink,
    outline = SapphirePalette.InkStrokeStrong,
    outlineVariant = SapphirePalette.InkStroke,
    error = SapphirePalette.Danger,
    onError = Color.White,
    errorContainer = SapphirePalette.Danger,
    onErrorContainer = Color.White,
    scrim = Color(0xE60B0F14),
)

/** Local access to the raw palette for screens that need explicit token colors. */
val LocalSapphirePalette = staticCompositionLocalOf { SapphirePalette }

@Composable
fun SapphireTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = SapphirePalette.Ink.toArgb()
            window.navigationBarColor = SapphirePalette.Ink.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }
    CompositionLocalProvider(LocalSapphirePalette provides SapphirePalette) {
        MaterialTheme(
            colorScheme = SapphireColorScheme,
            typography = SapphireTypography,
            shapes = SapphireShapes,
            content = content,
        )
    }
}
