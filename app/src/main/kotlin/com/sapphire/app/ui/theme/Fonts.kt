package com.sapphire.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.sapphire.app.R

/**
 * Sapphire type system — editorial display serif paired with research-grade sans/mono.
 *
 * - **Newsreader** (serif): high-contrast literary display face, used for titles and
 *   headlines; carries the "editorial" half of the tone. Bundled as a variable font;
 *   Compose synthesizes intermediate weights from the variable axes.
 * - **IBM Plex Sans**: the research/data body face. Carries the "research-terminal" half.
 * - **IBM Plex Mono**: micro-metadata, labels, timestamps, hashes, section eyebrows.
 *
 * All bundled locally (OFL) — no network font loading on Android.
 */
private val Newsreader = FontFamily(
    Font(R.font.newsreader_variable, FontWeight.Normal),
    Font(R.font.newsreader_italic_variable, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.newsreader_variable, FontWeight.Medium),
    Font(R.font.newsreader_variable, FontWeight.SemiBold),
    Font(R.font.newsreader_variable, FontWeight.Bold),
)

private val PlexSans = FontFamily(
    Font(R.font.plex_sans_regular, FontWeight.Normal),
    Font(R.font.plex_sans_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.plex_sans_medium, FontWeight.Medium),
    Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plex_sans_bold, FontWeight.Bold),
)

private val PlexMono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
    Font(R.font.plex_mono_bold, FontWeight.Bold),
)

/** Typeface bundle exposed for screens that want an explicit family reference. */
val SapphireFonts = SapphireTypeFaces(
    display = Newsreader,
    sans = PlexSans,
    mono = PlexMono,
)

data class SapphireTypeFaces(
    val display: FontFamily,
    val sans: FontFamily,
    val mono: FontFamily,
)
