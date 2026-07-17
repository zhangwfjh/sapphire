package com.sapphire.data.explore

import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tavily-backed [WebSearchClient]. Tavily is a search API tuned for LLM consumption: it
 * returns clean page content (title/url/extracted text) rather than raw HTML, which the
 * feed-search LLM then reads to identify real, currently-available RSS/Atom/JSON feeds.
 *
 * Auth is the `api_key` field in the request body. A blank key short-circuits to an empty
 * result — graceful degradation: [SearchFeedsUseCase] then falls back to a knowledge-only
 * LLM call. Any network/HTTP/parse failure likewise returns an empty list; nothing throws
 * across the boundary (search grounding is best-effort, never a hard dependency).
 *
 * `search_depth = "advanced"` trades one extra credit per query for richer per-page content,
 * which materially helps the LLM spot feed links; `max_results = 10` gives it a broad pool.
 */
class TavilySearchClient(
    private val apiKey: String,
    private val json: Json,
    client: OkHttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : WebSearchClient {

    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun search(query: String): List<WebSearchHit> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()

        val body = json.encodeToString(
            TavilyRequest.serializer(),
            TavilyRequest(query = query, apiKey = apiKey),
        )
        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            return@withContext emptyList()
        }

        response.use { res ->
            if (!res.isSuccessful) return@use emptyList<WebSearchHit>()
            val raw = res.body?.string().orEmpty()
            val parsed = try {
                json.decodeFromString(TavilyResponse.serializer(), raw)
            } catch (e: Exception) {
                return@use emptyList()
            }
            parsed.results.map { hit ->
                WebSearchHit(title = hit.title, url = hit.url, content = hit.content)
            }
        }
    }

    @Serializable
    private data class TavilyRequest(
        val query: String,
        @SerialName("api_key") val apiKey: String,
        @SerialName("search_depth") val searchDepth: String = "basic",
        @SerialName("max_results") val maxResults: Int = 10,
        @SerialName("include_answer") val includeAnswer: Boolean = false,
    )

    @Serializable
    private data class TavilyResponse(
        val results: List<TavilyHit> = emptyList(),
    )

    @Serializable
    private data class TavilyHit(
        val title: String = "",
        val url: String = "",
        val content: String = "",
    )

    private companion object {
        const val DEFAULT_ENDPOINT = "https://api.tavily.com/search"
        val MEDIA_TYPE = "application/json".toMediaType()
    }
}
