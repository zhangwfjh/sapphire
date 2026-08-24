package com.sapphire.domain.explore

/**
 * One hit from a live-web search: enough page context for an LLM to extract subscribable
 * feeds. [content] is the retriever's extracted text/snippet for the page at [url].
 */
data class WebSearchHit(
    val title: String,
    val url: String,
    val content: String,
)

/**
 * Live-web retrieval used to ground Explore feed search in current pages rather than the
 * LLM's parametric memory (which goes stale and hallucinates feed URLs). Pure-domain
 * contract; the keyless scraper implementations live in core-data.
 *
 * Failures are non-fatal: an implementation returns an empty list on any error, so the
 * caller ([SearchFeedsUseCase]) degrades gracefully to a knowledge-only LLM call rather
 * than blocking discovery.
 */
interface WebSearchClient {
    suspend fun search(query: String): List<WebSearchHit>
}
