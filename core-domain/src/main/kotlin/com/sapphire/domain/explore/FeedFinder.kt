package com.sapphire.domain.explore

/**
 * Topic-keyword feed discovery: returns subscribable feed URLs for a topic, with
 * curated titles/descriptions. Unlike [WebSearchClient] — a generic web search whose
 * callers shape the query ("$topic RSS feed" etc.) — implementations here receive the
 * raw topic and own their own query semantics.
 *
 * Hits are feed URLs, not pages; the harvester still verifies each one for liveness
 * and content. Failures are non-fatal: an empty list just contributes nothing to the
 * merged result.
 */
interface FeedFinder {
    suspend fun findFeeds(topic: String): List<WebSearchHit>
}
