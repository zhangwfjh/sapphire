package com.sapphire.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Sapphire dark-first palette.
 *
 * Concept: deep ink with a single disciplined sapphire-blue accent. The accent is the
 * ONLY saturated color in the system — it marks unread state, AI provenance, primary
 * CTAs, and active toggles. Everything else is a ramp of cool charcoal. Read items fade
 * into the ink; unread items glow against it.
 *
 * Hex values cross-referenced with res/values/colors.xml so the XML bootstrap window
 * matches this scheme before Compose paints.
 */
object SapphirePalette {
    // Base ink ramp — background, elevated surfaces, cards.
    val Ink = Color(0xFF0B0F14)          // app background; deepest.
    val InkElevated = Color(0xFF11161D)  // cards, bottom sheet.
    val InkRaised = Color(0xFF161D26)    // hover/active card, inputs.
    val InkStroke = Color(0xFF222B36)    // hairline borders.
    val InkStrokeStrong = Color(0xFF2F3A47)

    // Text — cool off-whites so nothing reads pure #FFF (too harsh on charcoal).
    val OnInk = Color(0xFFE6EAF0)        // primary text.
    val OnInkMuted = Color(0xFFAEB8C4)   // secondary text.
    val OnInkFaint = Color(0xFF6B7785)   // tertiary / placeholder.

    // The single accent. Deepened from #3B82F6 toward a more saturated jewel tone.
    val Accent = Color(0xFF3B82F6)       // primary accent.
    val AccentBright = Color(0xFF60A5FA) // unread glow / hover.
    val AccentDeep = Color(0xFF1D4ED8)   // pressed / gradient base.

    // Semantic — kept restrained, only where needed.
    val Danger = Color(0xFFF87171)

    // Reader body — warmer paper-on-charcoal for long-form.
    val ReaderPaper = Color(0xFF141A22)
    val ReaderInk = Color(0xFFD8DEE6)
}
