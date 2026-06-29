package com.sapphire.domain.util

import com.sapphire.domain.model.SourceKind

/**
 * Normalizes a raw kind string from the model's taxonomy response into a [SourceKind].
 * Unknown / blank values default to RSS — the safest universal assumption for a feed URL.
 */
fun parseSourceKind(raw: String?): SourceKind = when (raw?.trim()?.lowercase()) {
    "atom" -> SourceKind.ATOM
    "json", "jsonfeed" -> SourceKind.JSON
    else -> SourceKind.RSS
}
