package com.sapphire.data.browser

import com.sapphire.domain.browser.BrowserClient
import com.sapphire.domain.browser.BrowserConfig
import com.sapphire.domain.browser.LinkRef
import com.sapphire.domain.browser.RenderRequest
import com.sapphire.domain.browser.RenderResult
import com.sapphire.domain.browser.WaitUntil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C2 browser-service implementation of [BrowserClient]. HTTP POST to a user-configured base
 * URL (the RSSHub self-hosting pattern). The server (Playwright/Puppeteer, or a metered API
 * like Browserless) renders the page and returns `{title, text, links}`.
 *
 * Non-fatal (mirrors [com.sapphire.domain.explore.WebSearchClient]): any error or an
 * unconfigured base URL → [RenderResult.failed]/[RenderResult.notConfigured], so the agent
 * loop degrades gracefully rather than crashing.
 *
 * Contract with the render service:
 *   POST {baseUrl}render
 *   body: {"url": "...", "waitUntil": "networkidle", "extractLinks": true}
 *   200: {"title": "...", "text": "...", "links": [{"text": "...", "url": "..."}]}
 *   non-2xx / exception / parse error → RenderResult.failed
 */
@Singleton
class HttpBrowserClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val config: BrowserConfig,
) : BrowserClient {

    override suspend fun render(request: RenderRequest): RenderResult = withContext(Dispatchers.IO) {
        val baseUrl = config.baseUrl()
        if (baseUrl.isBlank()) return@withContext RenderResult.notConfigured()

        val body = json.encodeToString(
            RenderServiceRequest.serializer(),
            RenderServiceRequest(
                url = request.url,
                waitUntil = request.waitUntil.name.lowercase(),
                extractLinks = request.extractLinks,
            ),
        )

        val httpResponse = try {
            client.newCall(
                Request.Builder()
                    .url(baseUrl + RENDER_PATH)
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute()
        } catch (e: IOException) {
            return@withContext RenderResult.failed("network error: ${e.message ?: "unknown"}")
        }

        httpResponse.use { res ->
            if (!res.isSuccessful) {
                return@withContext RenderResult.failed("render service HTTP ${res.code}")
            }
            val raw = res.body?.string().orEmpty()
            val parsed = try {
                json.decodeFromString(RenderServiceResponse.serializer(), raw)
            } catch (e: Exception) {
                return@withContext RenderResult.failed("unparseable render response")
            }
            if (parsed.text.isNullOrBlank()) {
                return@withContext RenderResult.failed("render returned empty text")
            }
            RenderResult(
                ok = true,
                title = parsed.title,
                text = parsed.text,
                links = parsed.links?.map { LinkRef(it.text ?: "", it.url ?: "") } ?: emptyList(),
            )
        }
    }

    @Serializable
    private data class RenderServiceRequest(
        val url: String,
        val waitUntil: String,
        val extractLinks: Boolean,
    )

    @Serializable
    private data class RenderServiceResponse(
        val title: String? = null,
        val text: String? = null,
        val links: List<RenderServiceLink>? = null,
    )

    @Serializable
    private data class RenderServiceLink(
        val text: String? = null,
        val url: String? = null,
    )

    private companion object {
        const val RENDER_PATH = "render"
    }
}
