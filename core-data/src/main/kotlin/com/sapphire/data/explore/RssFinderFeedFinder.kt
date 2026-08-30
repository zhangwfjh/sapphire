package com.sapphire.data.explore

import com.sapphire.domain.explore.FeedFinder
import com.sapphire.domain.explore.WebSearchHit
import javax.inject.Inject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * RSS Finder (`https://rssfinder.app`) [FeedFinder] — a purpose-built feed search
 * engine. **No API key, no signup**; one GraphQL POST returns subscribable feed URLs
 * with curated titles/descriptions, which generic web engines rarely surface for
 * topic queries.
 *
 * The topic arrives RAW (no "RSS feed" enrichment) — the index is keyword-shaped, so
 * enriched queries match junk. Its hits ARE feed URLs (not pages to harvest), so the
 * [FeedLinkHarvester] treats them as direct candidates and only re-fetches each one
 * for liveness/content verification.
 *
 * Failures are non-fatal: any HTTP/parse error collapses to an empty list and the
 * use case falls through to the generic engine chain.
 */
class RssFinderFeedFinder @Inject constructor(
    private val client: OkHttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : FeedFinder {

    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun findFeeds(topic: String): List<WebSearchHit> {
        val body = JSONObject()
            .put("operationName", "searchInFinder")
            .put("variables", JSONObject().put("text", topic))
            .put("query", GRAPHQL_QUERY)
            .toString()

        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .header("Origin", "https://rssfinder.app")
            .header("User-Agent", SearchHttp.USER_AGENT)
            .post(body.toRequestBody(jsonMediaType))
            .build()

        val raw = SearchHttp.bodyOrNull(client, request) ?: return emptyList()
        return runCatching { parse(raw) }.getOrDefault(emptyList())
    }

    private fun parse(raw: String): List<WebSearchHit> {
        val feeds = JSONObject(raw)
            .optJSONObject("data")?.optJSONObject("searchInFinder")?.optJSONObject("textResult")
            ?.optJSONArray("feeds") ?: return emptyList()
        val hits = mutableListOf<WebSearchHit>()
        for (i in 0 until feeds.length()) {
            val feed = feeds.optJSONObject(i) ?: continue
            val url = feed.optString("url").trim()
            if (!url.startsWith("http")) continue
            hits += WebSearchHit(
                title = textOrNull(feed, "title").orEmpty().ifBlank { url },
                url = url,
                content = textOrNull(feed, "description").orEmpty(),
            )
        }
        return hits
    }

    /** optString renders JSON null as the literal "null" — isNull-guard the field first. */
    private fun textOrNull(feed: JSONObject, key: String): String? =
        if (feed.isNull(key)) null else feed.optString(key)

    private companion object {
        const val DEFAULT_ENDPOINT = "https://rssfinder.app/gql"

        /** Minimal selection of the site's own searchInFinder query — title/description/url only. */
        const val GRAPHQL_QUERY =
            "query searchInFinder(\$text: String!) { searchInFinder(text: \$text) { textResult { feeds { title description url } } } }"
    }
}
