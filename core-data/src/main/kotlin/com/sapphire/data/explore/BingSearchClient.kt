package com.sapphire.data.explore

import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * Bing HTML endpoint scraper. No API key, no signup. Works from China (redirects to
 * cn.bing.com). Region-robust parser: splits on `b_algo` blocks (doesn't rely on `<h2>`
 * which intl Bing uses but CN Bing omits), extracts href + `b_lineclamp2` snippet.
 *
 * Best-effort fallback after Exa MCP — Bing may localize results to zh-CN.
 */
class BingSearchClient @Inject constructor(
    client: OkHttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : WebSearchClient {

    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(true)
        .build()

    override suspend fun search(query: String): List<WebSearchHit> {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("count", "10")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SearchHttp.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        val html = SearchHttp.bodyOrNull(client, request) ?: return emptyList()
        return runCatching { parse(html) }.getOrDefault(emptyList())
    }

    /** Splits on `b_algo` blocks — works for both intl and CN Bing. */
    private fun parse(html: String): List<WebSearchHit> =
        html.split("class=\"b_algo\"").drop(1).mapNotNull { block ->
            val url = Regex("href=\"(https?:[^\"]+)\"").find(block)?.groupValues?.getOrNull(1)
            val snippet = Regex("class=\"b_lineclamp2\"[^>]*>([\\s\\S]*?)</p>").find(block)?.groupValues?.getOrNull(1)
            // Skip Bing-internal / Microsoft / resource URLs.
            if (url == null || url.contains("bing.com") || url.contains("microsoft") ||
                url.contains("aka.ms") || url.contains("go.microsoft") || url.contains("/rs/")) return@mapNotNull null
            val cleanSnippet = snippet?.replace(Regex("<[^>]+>|&\\w+;"), " ")?.trim()?.take(200) ?: ""
            // Extract a title from the first link text near the URL.
            val title = block.substringBefore("</h2>").substringAfter(">").trim().replace(Regex("<[^>]+>"), "").ifBlank { url }
            WebSearchHit(title = title, url = url, content = cleanSnippet)
        }.distinctBy { it.url }.take(10)

    private companion object {
        const val DEFAULT_ENDPOINT = "https://cn.bing.com/search"
    }
}
