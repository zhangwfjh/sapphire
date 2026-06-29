package com.sapphire.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SapphireColorSchemeDark = darkColorScheme(
    primary = SapphirePaletteDark.Accent,
    onPrimary = Color.White,
    primaryContainer = SapphirePaletteDark.AccentDeep,
    onPrimaryContainer = SapphirePaletteDark.OnInk,
    secondary = SapphirePaletteDark.AccentBright,
    onSecondary = Color.White,
    secondaryContainer = SapphirePaletteDark.InkRaised,
    onSecondaryContainer = SapphirePaletteDark.OnInk,
    tertiary = SapphirePaletteDark.AccentBright,
    onTertiary = Color.White,
    background = SapphirePaletteDark.Ink,
    onBackground = SapphirePaletteDark.OnInk,
    surface = SapphirePaletteDark.Ink,
    onSurface = SapphirePaletteDark.OnInk,
    surfaceVariant = SapphirePaletteDark.InkElevated,
    onSurfaceVariant = SapphirePaletteDark.OnInkMuted,
    surfaceTint = SapphirePaletteDark.Accent,
    inverseSurface = SapphirePaletteDark.OnInk,
    inverseOnSurface = SapphirePaletteDark.Ink,
    outline = SapphirePaletteDark.InkStrokeStrong,
    outlineVariant = SapphirePaletteDark.InkStroke,
    error = SapphirePaletteDark.Danger,
    onError = Color.White,
    errorContainer = SapphirePaletteDark.Danger,
    onErrorContainer = Color.White,
    scrim = Color(0xE60B0F14),
)

private val SapphireColorSchemeLight = lightColorScheme(
    primary = SapphirePaletteLight.Accent,
    onPrimary = Color.White,
    primaryContainer = SapphirePaletteLight.AccentDeep,
    onPrimaryContainer = Color.White,
    secondary = SapphirePaletteLight.AccentBright,
    onSecondary = Color.White,
    secondaryContainer = SapphirePaletteLight.InkRaised,
    onSecondaryContainer = SapphirePaletteLight.OnInk,
    tertiary = SapphirePaletteLight.AccentBright,
    onTertiary = Color.White,
    background = SapphirePaletteLight.Ink,
    onBackground = SapphirePaletteLight.OnInk,
    surface = SapphirePaletteLight.Ink,
    onSurface = SapphirePaletteLight.OnInk,
    surfaceVariant = SapphirePaletteLight.InkElevated,
    onSurfaceVariant = SapphirePaletteLight.OnInkMuted,
    surfaceTint = SapphirePaletteLight.Accent,
    inverseSurface = SapphirePaletteLight.OnInk,
    inverseOnSurface = SapphirePaletteLight.Ink,
    outline = SapphirePaletteLight.InkStrokeStrong,
    outlineVariant = SapphirePaletteLight.InkStroke,
    error = SapphirePaletteLight.Danger,
    onError = Color.White,
    errorContainer = SapphirePaletteLight.Danger,
    onErrorContainer = Color.White,
    scrim = Color(0x33000000),
)

val LocalSapphirePalette = staticCompositionLocalOf { SapphirePaletteDark }

@Composable
fun SapphireTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) SapphirePaletteDark else SapphirePaletteLight
    val colorScheme = if (darkTheme) SapphireColorSchemeDark else SapphireColorSchemeLight
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = palette.Ink.toArgb()
            window.navigationBarColor = palette.Ink.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalSapphirePalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SapphireTypography,
            shapes = SapphireShapes,
            content = content,
        )
    }
}
