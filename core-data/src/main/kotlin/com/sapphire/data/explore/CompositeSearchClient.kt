package com.sapphire.data.explore

import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import javax.inject.Inject

/**
 * Free-first search routing. All engines are keyless/anonymous — no API keys required.
 *
 * Priority chain (first non-empty result wins):
 * 1. **Exa MCP** — free public endpoint (`mcp.exa.ai/mcp`), JSON-RPC, structured
 *    results. Works from China. May throttle on heavy use.
 * 2. **Bing** — free HTML scraper, works in China, region-localized results.
 * 3. **DuckDuckGo** — free HTML scraper, blocked in China but good from WEST networks.
 * 4. **Baidu** — free HTML scraper, captcha-prone, good for zh-CN queries.
 * 5. **Tavily** — optional paid fallback (needs key). Last resort.
 *
 * All calls are non-fatal (failures → empty list → next engine tries).
 */
class CompositeSearchClient @Inject constructor(
    private val tavily: WebSearchClient,
    private val exa: WebSearchClient,
    private val bing: WebSearchClient,
    private val ddg: WebSearchClient,
    private val baidu: WebSearchClient,
) : WebSearchClient {

    override suspend fun search(query: String): List<WebSearchHit> {
        // Free/anonymous chain first.
        for (client in listOf<WebSearchClient>(exa, bing, ddg, baidu)) {
            val hits = runCatching { client.search(query) }.getOrDefault(emptyList())
            if (hits.isNotEmpty()) return hits
        }

        // Tavily — optional paid fallback (emptyList on no key).
        val tavilyHits = runCatching { tavily.search(query) }.getOrDefault(emptyList())
        if (tavilyHits.isNotEmpty()) return tavilyHits

        return emptyList()
    }
}
