package com.sapphire.data.feed

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Shared feed-parsing helpers — pure (no Android deps) so they're testable on the JVM. */

internal fun String.nullIfBlank(): String? = if (isBlank()) null else this

/**
 * Strips the tags from an RSS/Atom HTML fragment for the card title/summary snippet, then
 * decodes HTML entities in a single left-to-right pass so the result is plain text for
 * Compose. Handles the common named entities plus ALL numeric ones (`&#8217;`, `&#x2019;`,
 * …) — feeds routinely emit numeric entities for typographic quotes/dashes that the old
 * hard-coded set missed (e.g. `Anthropic&#8217;s`). Single-pass: a literal `&amp;#39;` in
 * source decodes to `&#39;` text, not `'`, because decoded output is never re-scanned.
 */
internal fun String.stripHtml(): String {
    val out = StringBuilder(length)
    var inTag = false
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c == '<' -> inTag = true
            c == '>' -> inTag = false
            !inTag -> out.append(c)
        }
        i++
    }
    return out.toString().decodeHtmlEntities().trim()
}

/** HTML named entities real feeds actually emit. */
private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
    "trade" to "™", "copy" to "©", "reg" to "®", "deg" to "°", "middot" to "·",
    "laquo" to "«", "raquo" to "»", "bull" to "•", "prime" to "′", "Prime" to "″",
)

/**
 * Decodes HTML entities in one left-to-right pass: named (from [NAMED_ENTITIES]), decimal
 * (`&#8217;`), and hex (`&#x2019;`). Unknown/malformed references are left intact. Output is
 * never re-scanned, so `&amp;#39;` → `&#39;` (literal), not `'`.
 */
internal fun String.decodeHtmlEntities(): String {
    val amp = indexOf('&')
    if (amp == -1) return this
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        if (this[i] != '&') { out.append(this[i]); i++; continue }
        val semi = indexOf(';', startIndex = i + 1)
        if (semi == -1 || semi - i > 12) { out.append('&'); i++; continue } // too long to be an entity
        val body = substring(i + 1, semi)
        val resolved: String? = if (body.startsWith("#")) {
            val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull(10)
            }
            code?.let { if (it in 1..0x10FFFF) String(Character.toChars(it)) else null }
        } else {
            NAMED_ENTITIES[body]
        }
        if (resolved != null) { out.append(resolved); i = semi + 1 }
        else { out.append('&'); i++ } // unknown entity name — leave the '&' verbatim
    }
    return out.toString()
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
