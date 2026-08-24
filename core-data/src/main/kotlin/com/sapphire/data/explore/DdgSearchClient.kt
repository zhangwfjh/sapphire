package com.sapphire.data.explore

import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import javax.inject.Inject

/**
 * DuckDuckGo HTML endpoint scraper (`html.duckduckgo.com/html/?q=…`). No API key, no
 * quota, blocked in mainland China — [CompositeSearchClient] failovers to Baidu when
 * this returns empty.
 *
 * DDG wraps external result URLs as `//duckduckgo.com/l/?uddg=<encoded>`; we unwrap the
 * `uddg` query param to recover the real article URL.
 *
 * Failures (network, 4xx/5xx, anti-bot interstitial, parse drift) all collapse to an
 * empty list per the [WebSearchClient] non-fatal contract.
 */
class DdgSearchClient @Inject constructor(
    client: OkHttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : WebSearchClient {

    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(true)
        .build()

    override suspend fun search(query: String): List<WebSearchHit> {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SearchHttp.USER_AGENT)
            .get()
            .build()

        val body = SearchHttp.bodyOrNull(client, request) ?: return emptyList()
        return runCatching { parse(Jsoup.parse(body)) }.getOrDefault(emptyList())
    }

    private fun parse(doc: Document): List<WebSearchHit> =
        doc.select("div.result").mapNotNull { block ->
            val a = block.selectFirst("a.result__a") ?: return@mapNotNull null
            val title = a.text().trim()
            val rawUrl = a.absUrl("href").ifBlank { a.attr("href") }
            val url = unwrapUddg(rawUrl) ?: rawUrl
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            val snippet = block.selectFirst("a.result__snippet")?.text()?.trim().orEmpty()
            WebSearchHit(title = title, url = url, content = snippet)
        }

    /** DDG wraps links as `//duckduckgo.com/l/?uddg=<encoded>`; unwrap when present. */
    private fun unwrapUddg(rawUrl: String): String? {
        return runCatching {
            val http = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
            val parsed = http.toHttpUrl()
            parsed.queryParameter("uddg")?.let { URLDecoder.decode(it, "UTF-8") }
        }.getOrNull()
    }

    private companion object {
        const val DEFAULT_ENDPOINT = "https://html.duckduckgo.com/html/"
    }
}
