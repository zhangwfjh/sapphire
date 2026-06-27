package com.sapphire.domain.util

import java.security.MessageDigest

/**
 * PRD §3.2 / architecture §3 global feed-item identity.
 *
 * `hashUuid = SHA-256( normalized(sourceId, canonicalUrl | title+publishedAt) )`, hex-encoded.
 * Falls back to `(title + publishedAt)` only when a canonical URL is absent (agent-synthesized
 * items, social posts without a permalink) so every item still gets a stable global key.
 *
 * Normalization is deliberately aggressive: scheme/host lowercased, default ports stripped,
 * common tracking params removed, fragments dropped, trailing slashes collapsed. This makes
 * `https://example.com/post?a=1&b=2&utm_source=x` and `HTTP://Example.com:443/post?b=2&a=1`
 * hash to the same id — the "same story from many feeds" case PRD §3.2 calls out.
 *
 * Pure (no Android deps) so it's exhaustively unit-testable. Uses SHA-256, not MD5 —
 * collisions here silently drop stories.
 */
object FeedItemId {

    private val TRACKING_PREFIXES = setOf("utm_", "ref_", "refq_", "gclid", "fbclid", "mc_", "_hs")

    /**
     * Identity for a web item with a URL.
     */
    fun fromUrl(sourceId: String, url: String): String =
        hash(normalizeSourceId(sourceId) + "\u0001" + canonicalizeUrl(url))

    /**
     * Identity fallback when no canonical URL exists (agent synth / social without permalink).
     */
    fun fromTitleAndPublishedAt(sourceId: String, title: String, publishedAt: Long?): String =
        hash(normalizeSourceId(sourceId) + "\u0002" + title.trim().lowercase() + "\u0003" + (publishedAt ?: 0L))

    private fun normalizeSourceId(sourceId: String): String = sourceId.trim()

    /** Visible for testing. */
    internal fun canonicalizeUrl(raw: String): String {
        if (raw.isBlank()) return ""
        val s = raw.trim()
        // Split scheme / authority / path / query / fragment with a tiny hand parser to avoid
        // pulling java.net.URI (whose behavior differs from android.net.Uri and is locale-sensitive).
        val schemeEnd = s.indexOf("://").takeIf { it > 0 } ?: return s.lowercase()
        val scheme = s.substring(0, schemeEnd).lowercase()
        val afterScheme = s.substring(schemeEnd + 3)
        val pathStart = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val (authority, rest) = if (pathStart < 0) {
            afterScheme to ""
        } else {
            afterScheme.substring(0, pathStart) to afterScheme.substring(pathStart)
        }
        val canonicalAuthority = canonicalizeAuthority(authority)
        // rest may begin with '/', '?', or '#'
        val fragmentIdx = rest.indexOf('#')
        val noFragment = if (fragmentIdx >= 0) rest.substring(0, fragmentIdx) else rest
        val queryIdx = noFragment.indexOf('?')
        val path = if (queryIdx < 0) noFragment else noFragment.substring(0, queryIdx)
        val query = if (queryIdx < 0) "" else noFragment.substring(queryIdx + 1)
        val canonicalPath = collapseTrailingSlash(path)
        val canonicalQuery = canonicalizeQuery(query)
        val sb = StringBuilder(scheme.length + canonicalAuthority.length + canonicalPath.length + canonicalQuery.length + 4)
        sb.append(scheme).append("://").append(canonicalAuthority).append(canonicalPath)
        if (canonicalQuery.isNotEmpty()) sb.append('?').append(canonicalQuery)
        return sb.toString()
    }

    private fun canonicalizeAuthority(authority: String): String {
        // userinfo@host:port -> host (lowercased), strip default ports, drop userinfo.
        val at = authority.lastIndexOf('@')
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val colon = hostPort.lastIndexOf(':')
        return if (colon > 0) {
            val host = hostPort.substring(0, colon).lowercase()
            val port = hostPort.substring(colon + 1).toIntOrNull()
            if (port == 443 || port == 80) host else "$host:$port"
        } else {
            hostPort.lowercase()
        }
    }

    private fun canonicalizeQuery(query: String): String {
        if (query.isEmpty()) return ""
        val pairs = query.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            if (isTrackingParam(key)) return@mapNotNull null
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            key.lowercase() to value
        }.sortedBy { it.first }
        return pairs.joinToString(separator = "&") { (k, v) -> if (v.isEmpty()) k else "$k=$v" }
    }

    private fun isTrackingParam(key: String): Boolean {
        val k = key.lowercase()
        return TRACKING_PREFIXES.any { pref ->
            if (pref.endsWith('_')) k.startsWith(pref) else k == pref
        }
    }

    private fun collapseTrailingSlash(path: String): String =
        if (path.length > 1 && path.endsWith('/')) path.dropLast(1) else path

    private fun hash(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
