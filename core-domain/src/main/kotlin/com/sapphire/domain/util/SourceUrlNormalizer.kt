package com.sapphire.domain.util

import java.net.URI

/**
 * Normalizes a feed URL for equivalence comparisons: already-subscribed detection,
 * discovered-pool dedup, and catalog-vs-subscribed cross-checks.
 *
 * Rules:
 * - Blank -> "".
 * - Trim; prepend "https://" when no scheme is present.
 * - Lowercase the host (DNS is case-insensitive); preserve path/query case.
 * - DROP the scheme so http/https compare equal.
 * - Drop the port (default or explicit).
 * - Drop the fragment; keep the query (RSSHub routes use query params).
 * - Strip a trailing "/" unless the path is "/" (root).
 * - On parse failure, return the trimmed input unchanged.
 */
fun normalizeSourceUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""

    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val parsed = runCatching { URI(withScheme) }.getOrNull() ?: return trimmed

    val host = parsed.host?.lowercase() ?: return trimmed
    val path = parsed.path?.ifEmpty { "/" } ?: "/"
    val query = parsed.rawQuery?.let { "?$it" } ?: ""
    val normalizedPath = if (path.length > 1 && path.endsWith("/")) path.dropLast(1) else path

    return "$host$normalizedPath$query"
}
