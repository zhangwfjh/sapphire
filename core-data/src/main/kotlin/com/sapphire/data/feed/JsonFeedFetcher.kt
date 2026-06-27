package com.sapphire.data.feed

import com.sapphire.domain.feed.FetchResult
import com.sapphire.domain.feed.Fetcher
import com.sapphire.domain.feed.FeedItemCandidate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * JSON Feed (jsonfeed.org, v1 & v1.1) fetcher. The third S02 ingestion kind; social feeds
 * routed through RSSHub (S06) also arrive as JSON Feed.
 *
 * Parses only the card-relevant fields. `content_html` → `bodyRaw` for S03; the summary
 * uses `summary` if present, else a stripped snippet of `content_text`.
 */
class JsonFeedFetcher @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) : Fetcher {

    override suspend fun fetch(url: String, configJson: String?): FetchResult = try {
        val body = fetchText(url)
        val feed = json.decodeFromString(JsonFeedRoot.serializer(), body)
        FetchResult.Success(feed.items.orEmpty().map { it.toCandidate() })
    } catch (e: IOException) {
        FetchResult.TransientError(e.message ?: "network error")
    } catch (e: Exception) {
        // Serialization / malformed JSON is persistent.
        FetchResult.PersistentFailure("invalid json feed: ${e.message}")
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Sapphire/0.1 (local feed reader)")
            .header("Accept", "application/feed+json, application/json, */*")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("http ${res.code}")
            return res.body?.string() ?: throw IOException("empty body")
        }
    }

    @Serializable
    private data class JsonFeedRoot(
        val items: List<JsonFeedItem>? = null,
    )

    @Serializable
    private data class JsonFeedItem(
        val id: String? = null,
        val url: String? = null,
        val external_url: String? = null,
        val title: String? = null,
        val summary: String? = null,
        val content_html: String? = null,
        val content_text: String? = null,
        val image: String? = null,
        val date_published: String? = null,
        val date_modified: String? = null,
        @kotlinx.serialization.SerialName("author")
        private val authorObj: JsonFeedAuthor? = null,
    ) {
        fun toCandidate(): FeedItemCandidate {
            val permalink = url ?: external_url ?: id
            val summaryText = summary ?: content_text?.take(280)
            return FeedItemCandidate(
                title = title?.stripHtml()?.nullIfBlank() ?: "(untitled)",
                summary = summaryText?.stripHtml(),
                canonicalUrl = permalink,
                authorHandle = authorObj?.name,
                publishedAt = (date_published ?: date_modified)?.let(::parseIso8601),
                platformTag = null,
                mediaUrl = image,
                bodyRaw = content_html ?: content_text,
            )
        }
    }

    @Serializable
    private data class JsonFeedAuthor(val name: String? = null, val url: String? = null)
}
