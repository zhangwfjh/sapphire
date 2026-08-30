package com.sapphire.data.explore

import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject

/**
 * Exa public MCP search (`https://mcp.exa.ai/mcp`). **No API key, no signup.**
 *
 * Protocol: JSON-RPC `tools/call` with `web_search_exa` tool. Response is SSE —
 * the result line starts with `data:` and contains a JSON payload with structured
 * text blocks (`Title: …`, `URL: …`, `Highlights: …`).
 *
 * This is the preferred keyless engine: clean structured results, stable JSON contract,
 * works from China. May be rate-limited (throttles result count on heavy use).
 */
class ExaMcpSearchClient @Inject constructor(
    private val client: OkHttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : WebSearchClient {

    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun search(query: String): List<WebSearchHit> {
        val rpcBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "tools/call")
            .put("params", JSONObject()
                .put("name", "web_search_exa")
                .put("arguments", JSONObject()
                    .put("query", query)
                    .put("numResults", 5)
                )
            )
            .toString()

        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .post(rpcBody.toRequestBody(jsonMediaType))
            .build()

        val raw = SearchHttp.bodyOrNull(client, request) ?: return emptyList()
        return runCatching { parse(raw) }.getOrDefault(emptyList())
    }

    /**
     * SSE payload: the `data:` line carries JSON-RPC result content — text blocks with
     * `Title:` / `URL:` / `Highlights:` sections. Results may arrive one text block
     * each OR concatenated into a single block, so each block is split on line-start
     * `Title:` boundaries before extraction. JSONObject handles embedded newlines.
     */
    private fun parse(raw: String): List<WebSearchHit> {
        val dataLine = raw.lineSequence().find { it.startsWith("data:") } ?: return emptyList()
        val payload = try { JSONObject(dataLine.removePrefix("data:").trim()) } catch (_: Exception) { return emptyList() }
        val content = payload.optJSONObject("result")?.optJSONArray("content") ?: return emptyList()
        val hits = mutableListOf<WebSearchHit>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") != "text") continue
            hits += parseBlock(block.optString("text"))
        }
        return hits
    }

    private fun parseBlock(text: String): List<WebSearchHit> =
        // Split before each line-start "Title:" so N concatenated results become N chunks.
        text.split(Regex("\\n(?=Title:)")).mapNotNull { chunk ->
            val title = Regex("(?m)^Title:\\s*(.+)$").find(chunk)?.groupValues?.get(1)?.trim()
            val url = Regex("(?m)^URL:\\s*(\\S+)$").find(chunk)?.groupValues?.get(1)?.trim()
            if (url.isNullOrBlank()) return@mapNotNull null
            val snippet = chunk.substringAfter("Highlights:", "").trim().take(300)
            WebSearchHit(
                title = title?.ifBlank { url } ?: url,
                url = url,
                content = snippet,
            )
        }

    private companion object {
        const val DEFAULT_ENDPOINT = "https://mcp.exa.ai/mcp"
    }
}
