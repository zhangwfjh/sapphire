package com.sapphire.domain.explore

/**
 * Deterministic feed discovery: given live-web search hits for a topic, fetch the hit
 * pages, extract candidate feed URLs (`<link rel="alternate">`, feed-shaped paths,
 * well-known feed paths), verify each candidate by fetching and sniffing it, and return
 * verified results ranked by search position.
 *
 * This replaces the former Tier-1 LLM extraction step: every returned URL has been
 * fetched and content-sniffed, so results are verified subscribable feeds rather than
 * model-recalled URLs, and the whole topic search runs keyless with zero token spend.
 *
 * Failures are non-fatal per candidate: any fetch/parse error drops that candidate; an
 * empty return means nothing verifiable was found in the hits.
 */
interface FeedLinkHarvester {
    suspend fun harvest(hits: List<WebSearchHit>): List<FeedSearchResult>
}
