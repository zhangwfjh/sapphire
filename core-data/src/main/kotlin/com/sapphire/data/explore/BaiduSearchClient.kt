package com.sapphire.data.explore

import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import javax.inject.Inject

/**
 * Baidu HTML scraper (`www.baidu.com/s?wd=…`). No API key, excellent for technical and
 * Chinese-language queries; the dominant engine on mainland-China networks where DDG is
 * blocked and Bing returns unreliable localized results.
 *
 * Baidu wraps every result URL through `http://www.baidu.com/link?url=…` redirectors.
 * For each result we issue one follow-up GET to resolve the real article URL. OkHttp's
 * built-in redirect handling follows the 302 with a 1-hop cap (the redirector target
 * itself rarely chains further). The 20 follow-ups per search are parallelized inside a
 * bounded `coroutineScope`.
 *
 * Failures (network, parse, redirector loop/error) collapse to: hit URL left as the
 * wrapped form; per-hit failure does not abort the whole result list.
 */
class BaiduSearchClient @Inject constructor(
    client: OkHttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : WebSearchClient {

    // One-hop resolver for baidu.com/link?url=... targets: do NOT auto-follow — read the
    // 302 Location directly so resolution is a single hop (no fetch of the article body,
    // no dependency on the article host being reachable).
    private val resolverClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .build()
    // Search call does not need redirects — the SERP itself is a 200.
    private val serpClient: OkHttpClient = client

    override suspend fun search(query: String): List<WebSearchHit> = withContext(Dispatchers.IO) {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("wd", query)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SearchHttp.USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()

        val rawHits: List<RawHit> = try {
            serpClient.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@use emptyList()
                val bodyBytes = res.body?.bytes() ?: return@use emptyList()
                val charset = runCatching {
                    res.header("Content-Type")
                        ?.substringAfter("charset=", "")
                        ?.trim()
                        ?.lowercase()
                        ?.ifBlank { null }
                }.getOrNull()
                val html = decodeBody(bodyBytes, charset)
                runCatching { parseSerp(Jsoup.parse(html)) }.getOrDefault(emptyList())
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: Throwable) {
            emptyList()
        }

        if (rawHits.isEmpty()) return@withContext emptyList()

        // Resolve wrapped URLs in parallel (bounded by the natural result count, ~10–20).
        coroutineScope {
            rawHits.map { hit ->
                async {
                    val resolved = resolveUrl(hit.url)
                    WebSearchHit(title = hit.title, url = resolved, content = hit.snippet)
                }
            }.awaitAll()
        }
    }

    private data class RawHit(val title: String, val url: String, val snippet: String)

    private fun parseSerp(doc: Document): List<RawHit> =
        doc.select("div.result, div.c-container").mapNotNull { block ->
            val a = block.selectFirst("h3 a") ?: return@mapNotNull null
            val title = a.text().trim()
            val url = a.absUrl("href").ifBlank { a.attr("href") }.trim()
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            val snippet = block.selectFirst("div.c-abstract")?.text()?.trim().orEmpty()
            RawHit(title = title, url = url, snippet = snippet)
        }

    /**
     * If [rawUrl] is a baidu link redirector (`…/link?url=…`), issue one HEAD to read the
     * 302 `Location` and return the real article URL. Identified by a `/link` path so the
     * check holds for both `www.baidu.com/link?url=…` (prod) and any test mock; direct
     * article URLs are returned untouched. Any failure leaves the wrapped URL in place.
     */
    private fun resolveUrl(rawUrl: String): String {
        if (!isLinkRedirector(rawUrl)) return rawUrl
        return try {
            val req = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", SearchHttp.USER_AGENT)
                .head() // we only need the redirect target, no body
                .build()
            resolverClient.newCall(req).execute().use { res ->
                if (res.isRedirect) res.header("Location")?.trim()?.takeUnless { it.isBlank() } ?: rawUrl
                else rawUrl
            }
        } catch (_: Throwable) {
            rawUrl // leave wrapped; LLM still has a working redirect URL
        }
    }

    private fun isLinkRedirector(rawUrl: String): Boolean =
        runCatching { rawUrl.toHttpUrl().encodedPath == "/link" }.getOrDefault(false)

    private fun decodeBody(bytes: ByteArray, declaredCharset: String?): String = when (declaredCharset) {
        "gbk", "gb2312", "gb18030" -> String(bytes, charset("GB18030"))
        else -> String(bytes, Charsets.UTF_8) // Baidu default as of 2026
    }

    private companion object {
        const val DEFAULT_ENDPOINT = "https://www.baidu.com/s"
    }
}
