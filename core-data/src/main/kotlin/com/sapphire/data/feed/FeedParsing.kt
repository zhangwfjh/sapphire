package com.sapphire.data.feed

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Shared feed-parsing helpers — pure (no Android deps) so they're testable on the JVM. */

internal fun String.nullIfBlank(): String? = if (isBlank()) null else this

/**
 * Strips the tags from an RSS/Atom HTML fragment for the card summary snippet. Does NOT
 * decode entities beyond the common `&amp;`/`&lt;`/`&gt;`/`&quot;`/`&#39;` set — feeds
 * rarely use exotic entities and the summary is re-rendered by Compose, not a browser.
 */
internal fun String.stripHtml(): String {
    val out = StringBuilder(length)
    var i = 0
    var inTag = false
    while (i < length) {
        val c = this[i]
        when {
            c == '<' -> inTag = true
            c == '>' -> inTag = false
            !inTag -> out.append(c)
        }
        i++
    }
    return out.toString()
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .trim()
}

/** RFC-822 / RFC-1123 — RSS 2.0 pubDate. Feeds emit a mix of named zones ("GMT") and
 * numeric offsets ("+0000", "+00:00"), so try both zzz and Z/XXX rather than one pattern. */
internal fun parseRfc822(raw: String): Long? {
    val patterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz", // named zone: GMT/UTC/PST
        "EEE, dd MMM yyyy HH:mm:ss Z",   // numeric offset: +0000
        "EEE, dd MMM yyyy HH:mm:ss XXX", // numeric offset: +00:00
    )
    for (p in patterns) {
        val parsed = runCatching {
            SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(raw)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return null
}

/** ISO-8601 — Atom. e.g. "2024-10-02T13:37:00Z" or "2024-10-02T13:37:00.000+08:00". */
internal fun parseIso8601(raw: String): Long? {
    val cleaned = raw.trim().replace(" ", "T")
    // Try a handful of common precision/zone variants rather than one fragile pattern.
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
    )
    for (p in patterns) {
        val parsed = runCatching {
            SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(cleaned)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return null
}

/** Relax tag/namespace names: lowercase, drop XML-ns prefix so `dc:creator`→`creator`. */
internal fun String.relaxed(): String =
    substringAfterLast(':').lowercase(Locale.US)

internal fun String?.relaxedNs(): String? = this?.lowercase(Locale.US)

/** Heuristic: does this URL look like a raster image we can show as a preview?
 * True for `.jpg/.jpeg/.png/.webp/.gif/.avif` (case-insensitive, query stripped).
 * Used to disambiguate untyped enclosures (image vs. podcast audio). */
internal fun looksLikeImageUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
    return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
        path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".avif")
}

/** Extracts the first `src="..."` from an `<img>` tag in an HTML fragment (feed body).
 * This is the canonical preview-image source for Reddit and image-light blogs that embed
 * imagery only inside `content:encoded` / `description` HTML. Returns null if none. */
internal fun firstImgSrc(html: String): String? {
    val lower = html.lowercase(Locale.US)
    var i = lower.indexOf("<img")
    while (i >= 0) {
        val tagEnd = html.indexOf('>', i)
        if (tagEnd < 0) return null
        val tag = html.substring(i, tagEnd + 1)
        val src = extractAttr(tag, "src") ?: extractAttr(tag, "data-src")
        if (src != null) {
            // Skip junk placeholders ("#", blank, data: URIs) and keep scanning.
            val cleaned = src.trim()
            if (cleaned.isNotEmpty() && cleaned != "#" && !cleaned.startsWith("data:")) return cleaned
        }
        i = lower.indexOf("<img", tagEnd)
    }
    return null
}

/**
 * Pulls a `name="value"` or `name='value'` attribute value from a single tag string.
 * HTML attribute names are case-insensitive, so the lookup lowercases the tag while
 * preserving the original-case value (URLs may carry case-sensitive query params). */
private fun extractAttr(tag: String, name: String): String? {
    val lower = tag.lowercase(Locale.US)
    val key = "$name="
    val idx = lower.indexOf(key)
    if (idx < 0) return null
    val quote = tag[idx + key.length]
    if (quote != '"' && quote != '\'') return null
    val start = idx + key.length + 1
    val end = tag.indexOf(quote, start)
    return if (end < 0) null else tag.substring(start, end)
}
