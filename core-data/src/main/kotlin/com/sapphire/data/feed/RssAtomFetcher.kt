package com.sapphire.data.feed

import com.sapphire.domain.feed.FetchResult
import com.sapphire.domain.feed.Fetcher
import com.sapphire.domain.feed.FeedItemCandidate
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import javax.inject.Inject

/**
 * RSS 2.0 + Atom 1.0 fetcher (architecture §6 / §11). Uses Android's [XmlPullParser]
 * (kxml2, bundled) rather than Rome — Rome drags JDK XML APIs that AGP/Android struggle
 * with and adds ~400 KB for one feed parser.
 *
 * Extracts the PRD §3.2 card fields: title, summary, canonical link, author, publishedAt
 * epoch, media thumbnail. `<content:encoded>` (RSS) / `<content>` (Atom) feeds `bodyRaw`
 * for S03's reader body parse; S02 only needs the card fields.
 */
class RssAtomFetcher @Inject constructor(
    private val client: OkHttpClient,
) : Fetcher {

    override suspend fun fetch(url: String, configJson: String?): FetchResult = try {
        val body = fetchText(url)
        parse(body)
    } catch (e: IOException) {
        FetchResult.TransientError(e.message ?: "network error")
    } catch (e: XmlPullParserException) {
        // Malformed XML is persistent — the feed itself is broken, not a network blip.
        FetchResult.PersistentFailure("malformed feed: ${e.message}")
    } catch (e: Exception) {
        // Any other throw from the parser loops (RuntimeException from nextText() on
        // unexpected structure, NumberFormatException, etc.) is treated as persistent.
        FetchResult.PersistentFailure("parse error: ${e.message}")
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Sapphire/0.1 (local feed reader)")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, */*")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw IOException("http ${res.code}")
            }
            return res.body?.string() ?: throw IOException("empty body")
        }
    }

    internal fun parse(xml: String): FetchResult {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xml))
        val items = ArrayList<FeedItemCandidate>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name.relaxed()
                val ns = parser.namespace?.relaxedNs()
                when {
                    name == "item" -> items += readRssItem(parser)
                    name == "entry" && (ns == null || ns.contains("atom")) -> items += readAtomEntry(parser)
                }
            }
            event = parser.next()
        }
        return FetchResult.Success(items)
    }

    /** Reads one RSS `<item>` block until its matching `</item>`. */
    private fun readRssItem(parser: XmlPullParser): FeedItemCandidate {
        var title: String? = null
        var link: String? = null
        var description: String? = null
        var content: String? = null
        var author: String? = null
        var pubDate: String? = null
        var mediaUrl: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name.relaxed() == "item")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.relaxed()) {
                    "title" -> title = parser.nextText().trim().nullIfBlank()
                    "link" -> link = parser.nextText().trim().nullIfBlank()
                    "description" -> description = parser.nextText().trim().nullIfBlank()
                    "content" -> content = parser.nextText().trim().nullIfBlank()
                    "encoded" -> content = parser.nextText().trim().nullIfBlank() // content:encoded
                    "creator" -> author = parser.nextText().trim().nullIfBlank() // dc:creator
                    "author" -> author = parseRssAuthor(parser.nextText().trim())
                    "pubdate", "date" -> pubDate = parser.nextText().trim().nullIfBlank()
                    "thumbnail" -> mediaUrl = parser.getAttributeValue(null, "url")
                        ?: mediaUrl
                }
            }
            parser.next()
        }
        return FeedItemCandidate(
            title = title?.stripHtml() ?: "(untitled)",
            summary = description?.stripHtml(),
            canonicalUrl = link,
            authorHandle = author,
            publishedAt = pubDate?.let(::parseRfc822),
            platformTag = null,
            mediaUrl = mediaUrl,
            bodyRaw = content,
        )
    }

    /** Reads one Atom `<entry>` block. */
    private fun readAtomEntry(parser: XmlPullParser): FeedItemCandidate {
        var title: String? = null
        var link: String? = null
        var summary: String? = null
        var content: String? = null
        var author: String? = null
        var updated: String? = null
        var published: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name.relaxed() == "entry")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.relaxed()) {
                    "title" -> title = parser.nextText().trim().nullIfBlank()
                    "summary" -> summary = parser.nextText().trim().nullIfBlank()
                    "content" -> content = parser.nextText().trim().nullIfBlank()
                    "name" -> author = parser.nextText().trim().nullIfBlank()
                    "published" -> published = parser.nextText().trim().nullIfBlank()
                    "updated" -> updated = parser.nextText().trim().nullIfBlank()
                    "link" -> {
                        // Prefer rel="alternate" (the permalink); fall back to first link.
                        val rel = parser.getAttributeValue(null, "rel")
                        val href = parser.getAttributeValue(null, "href")
                        if (href != null && (rel == null || rel == "alternate")) {
                            if (link == null) link = href
                        }
                    }
                }
            }
            parser.next()
        }
        return FeedItemCandidate(
            title = title?.stripHtml() ?: "(untitled)",
            summary = summary?.stripHtml() ?: content?.stripHtml(),
            canonicalUrl = link,
            authorHandle = author,
            publishedAt = (published ?: updated)?.let(::parseIso8601),
            platformTag = null,
            mediaUrl = null,
            bodyRaw = content,
        )
    }
}

/** RSS author can be `name (email)` or a raw email; strip the email part. */
private fun parseRssAuthor(raw: String): String? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    val paren = s.substringBefore('(').trim()
    return if (paren.isNotEmpty() && !paren.contains('@')) paren else s
}
