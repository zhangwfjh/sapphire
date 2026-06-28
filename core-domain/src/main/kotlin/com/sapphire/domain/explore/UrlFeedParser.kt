package com.sapphire.domain.explore

import java.net.URI

/** Result of recognizing a pasted feed URL: enough to drive a direct subscribe. */
data class ParsedUrlFeed(
    val title: String,
    val url: String,
)

/**
 * Subscribe-by-URL shortcut: if [raw] looks like a feed URL, recognize it and return a
 * [ParsedUrlFeed] instantly — no LLM. Returning null means "not a URL"; the caller should
 * treat the input as a topic query (handled by [SearchFeedsUseCase]'s Tier-1 path).
 *
 * A value qualifies as a URL when it has a host containing a dot AND either an explicit
 * scheme or a path. This rejects bare topics ("biohacking") while accepting
 * "example.com/feed" and "https://hnrss.org/frontpage".
 *
 * The title is derived from the lowercased host plus any non-root path; [url] preserves
 * the trimmed input as-is (scheme kept) so it round-trips through `FeedSearchResult.url`.
 * Equivalence checks against already-subscribed sources use [com.sapphire.domain.util.normalizeSourceUrl]
 * at the call site, not here.
 */
fun parseUrlFeed(raw: String): ParsedUrlFeed? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val parsed = runCatching { URI(withScheme) }.getOrNull() ?: return null

    val host = parsed.host ?: return null
    if (!host.contains('.')) return null
    if (!trimmed.contains("://") && !trimmed.contains("/")) return null

    val pathSuffix = parsed.path?.takeIf { it.isNotEmpty() && it != "/" } ?: ""
    val title = host.lowercase() + pathSuffix

    return ParsedUrlFeed(title = title, url = trimmed)
}
