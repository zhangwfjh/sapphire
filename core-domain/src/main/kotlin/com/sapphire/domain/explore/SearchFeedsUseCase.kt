package com.sapphire.domain.explore

import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome

/**
 * Explore search — turn a topic or URL into a list of verified feed matches.
 * **No LLM**: the topic path is fully deterministic, so it is keyless (no API key
 * required), spends no tokens, and every returned URL has been fetched and sniffed.
 *
 * URL shortcut: if the query looks like a URL (has a scheme + host, or a bare host
 * with a path), skip retrieval and return it as a single result.
 *
 * Topic path: the [FeedFinder] topic engine (RSS Finder) returns subscribable feed
 * URLs for the raw keyword, then [FeedLinkHarvester] verifies each candidate parses as
 * a live feed. The generic web-search engine chain is deliberately NOT involved — it
 * belongs to the agent loop.
 */
class SearchFeedsUseCase(
    private val feedFinder: FeedFinder,
    private val harvester: FeedLinkHarvester,
) {

    suspend operator fun invoke(query: String): LlmOutcome<List<FeedSearchResult>> {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return LlmOutcome.Err(LlmError.Empty("Type a topic or paste a feed URL."))

        val asUrl = parseUrlFeed(cleaned)
        if (asUrl != null) {
            return LlmOutcome.Ok(
                listOf(
                    FeedSearchResult(
                        title = asUrl.title,
                        url = asUrl.url,
                        kind = "rss",
                        description = null,
                    ),
                ),
            )
        }

        // Feed search rides on RSS Finder alone — the web-search engine chain
        // (exa → bing → ddg → baidu) is the agent loop's retrieval, not ours.
        // Empty hits (failure/unreachable) surface as Empty — no LLM fallback.
        val hits = runCatching { feedFinder.findFeeds(cleaned) }.getOrDefault(emptyList())
        if (hits.isEmpty()) return ERR_NO_FEEDS

        val verified = runCatching { harvester.harvest(hits) }.getOrDefault(emptyList())
        return if (verified.isEmpty()) ERR_NO_FEEDS else LlmOutcome.Ok(verified)
    }

    private companion object {
        private val ERR_NO_FEEDS =
            LlmOutcome.Err(LlmError.Empty("No feeds found — try a broader term or paste a URL."))
    }
}
