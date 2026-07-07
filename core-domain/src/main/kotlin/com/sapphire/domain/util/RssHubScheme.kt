package com.sapphire.domain.util

/** Virtual scheme users type to subscribe to an RSSHub route without memorizing a host. */
const val RSSHUB_SCHEME = "rsshub://"

private const val RSSHUB_BASE = "https://russhub.umzzz.com/"

/**
 * Resolves a stored feed URL into the real https endpoint the fetcher should hit.
 * `rsshub://bbc/world` → `https://russhub.umzzz.com/bbc/world`; any other URL is returned
 * unchanged. Applied at the network boundary so the rest of the app stores and displays
 * the friendly `rsshub://` form verbatim — only the actual HTTP request sees the host.
 */
fun resolveFeedUrl(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    val scheme = url.substring(0, schemeEnd).lowercase()
    if (scheme != "rsshub") return url
    val route = url.substring(schemeEnd + 3).trimStart('/')
    if (route.isEmpty()) return url
    return RSSHUB_BASE + route
}
