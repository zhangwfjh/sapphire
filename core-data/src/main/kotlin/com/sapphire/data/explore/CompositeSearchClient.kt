package com.sapphire.data.explore

import com.sapphire.domain.explore.SearchConfig
import com.sapphire.domain.explore.SearchRegion
import com.sapphire.domain.explore.SearchRegionResolver
import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Routes a search to the right backend(s):
 *
 * 1. If a Tavily key is set (runtime override or BuildConfig), try [tavily] first. On
 *    non-empty result, return. Tavily's contract is non-fatal (failures → empty), so a
 *    failed/empty Tavily call transparently falls through.
 * 2. Else (or after Tavily returned empty) run the no-key failover chain:
 *      - Effective region: [SearchConfig.region], unless [SearchRegion.AUTO], in which
 *        case [regionResolver] decides from `Locale`.
 *      - WEST   / AUTO-non-zh: ddg → baidu
 *      - CHINA  / AUTO-zh    : baidu → ddg
 * 3. First non-empty result wins. All-empty → empty list.
 *
 * Calls are strictly sequential (no parallel dispatch) to avoid tripping rate limits on
 * the fallback that wouldn't have been queried anyway.
 */
class CompositeSearchClient @Inject constructor(
    private val tavily: WebSearchClient,
    private val ddg: WebSearchClient,
    private val baidu: WebSearchClient,
    private val config: SearchConfig,
    private val regionResolver: SearchRegionResolver,
) : WebSearchClient {

    override suspend fun search(query: String): List<WebSearchHit> {
        // 1. Tavily path (only if key set).
        val tavilyKey = config.observeTavilyKey().first()
        if (tavilyKey.isNotBlank()) {
            val tavilyHits = runCatching { tavily.search(query) }.getOrDefault(emptyList())
            if (tavilyHits.isNotEmpty()) return tavilyHits
        }

        // 2. No-key failover chain.
        val effectiveRegion = when (config.region()) {
            SearchRegion.AUTO -> regionResolver.resolve()
            SearchRegion.WEST -> SearchRegion.WEST
            SearchRegion.CHINA -> SearchRegion.CHINA
        }
        val chain: List<WebSearchClient> = when (effectiveRegion) {
            SearchRegion.WEST -> listOf(ddg, baidu)
            SearchRegion.CHINA -> listOf(baidu, ddg)
            SearchRegion.AUTO -> listOf(ddg, baidu) // defensive; regionResolver above resolves AUTO
        }
        for (client in chain) {
            val hits = runCatching { client.search(query) }.getOrDefault(emptyList())
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }
}
