package com.sapphire.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Sapphire palette — dark-first identity with a light variant. The sapphire-blue accent is
 * the ONLY saturated color; it is preserved across both themes. Everything else is a cool
 * ramp (charcoal for dark; cool paper for light).
 *
 * Two instances: [SapphirePaletteDark] (the original) and [SapphirePaletteLight].
 */
data class SapphirePalette(
    val Ink: Color,
    val InkElevated: Color,
    val InkRaised: Color,
    val InkStroke: Color,
    val InkStrokeStrong: Color,
    val OnInk: Color,
    val OnInkMuted: Color,
    val OnInkFaint: Color,
    val Accent: Color,
    val AccentBright: Color,
    val AccentDeep: Color,
    val Danger: Color,
    val ReaderPaper: Color,
    val ReaderInk: Color,
)

val SapphirePaletteDark = SapphirePalette(
    Ink = Color(0xFF0B0F14),
    InkElevated = Color(0xFF11161D),
    InkRaised = Color(0xFF161D26),
    InkStroke = Color(0xFF222B36),
    InkStrokeStrong = Color(0xFF2F3A47),
    OnInk = Color(0xFFE6EAF0),
    OnInkMuted = Color(0xFFAEB8C4),
    OnInkFaint = Color(0xFF6B7785),
    Accent = Color(0xFF3B82F6),
    AccentBright = Color(0xFF60A5FA),
    AccentDeep = Color(0xFF1D4ED8),
    Danger = Color(0xFFF87171),
    ReaderPaper = Color(0xFF141A22),
    ReaderInk = Color(0xFFD8DEE6),
)

/**
 * Light variant — cool paper canvas, same sapphire accent. Text inverts to near-black;
 * surfaces become cool off-whites. Accent deepened from #3B82F6 to #2563EB for WCAG-AA
 * contrast on paper.
 */
val SapphirePaletteLight = SapphirePalette(
    Ink = Color(0xFFF4F6F8),
    InkElevated = Color(0xFFFFFFFF),
    InkRaised = Color(0xFFE9EEF3),
    InkStroke = Color(0xFFD7DEE6),
    InkStrokeStrong = Color(0xFFB8C2CE),
    OnInk = Color(0xFF1A1F26),
    OnInkMuted = Color(0xFF5C6873),
    OnInkFaint = Color(0xFF8A95A1),
    Accent = Color(0xFF2563EB),
    AccentBright = Color(0xFF3B82F6),
    AccentDeep = Color(0xFF1D4ED8),
    Danger = Color(0xFFDC2626),
    ReaderPaper = Color(0xFFFFFFFF),
    ReaderInk = Color(0xFF2A3340),
)
