package com.sapphire.domain.explore

import com.sapphire.domain.llm.FeedSearchResponse
import com.sapphire.domain.llm.FeedSearchResult
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier

/**
 * Explore search — turn a topic or URL into a list of feed matches.
 *
 * URL shortcut: if the query looks like a URL (has a scheme + host, or a bare host
 * with a path), skip the LLM and return it as a single result.
 *
 * Topic path uses **retrieve-then-generate**: [WebSearchClient] first pulls live
 * web pages for the topic, then those real results are injected into the Tier-1 prompt as
 * grounding context. The model extracts subscribable feed URLs from current pages instead
 * of answering from parametric memory (which goes stale and hallucinates URLs). If web
 * retrieval is unavailable (no key / network failure / empty), the call degrades gracefully
 * to a knowledge-only LLM completion rather than blocking discovery.
 */
class SearchFeedsUseCase(
    private val llm: LlmClient,
    private val webSearch: WebSearchClient,
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

        // Enrich only the retrieval query — biasing the search engines toward feed endpoints and
        // directories (Feedspot, /feed, .rss) so the LLM has real subscribable URLs to
        // extract. The topic label passed to the LLM (and shown to the user) stays the
        // original wording. Empirically this turns generic-noun queries (e.g. "china")
        // from 0 feed-ish results into feed-directory pages.
        // Best-effort: an empty list (failure/unconfigured) still yields a valid prompt —
        // the model then answers from its own knowledge with lowered confidence.
        val hits = runCatching { webSearch.search("$cleaned RSS feed") }.getOrDefault(emptyList())

        return when (val outcome = llm.completeStructured(
            tier = LlmTier.TIER1_FAST,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = userPrompt(cleaned, hits),
            outputSerializer = FeedSearchResponse.serializer(),
        )) {
            is LlmOutcome.Err -> outcome
            is LlmOutcome.Ok -> {
                if (outcome.value.results.isEmpty()) {
                    LlmOutcome.Err(LlmError.Empty("No feeds found — try a broader term or paste a URL."))
                } else {
                    LlmOutcome.Ok(outcome.value.results)
                }
            }
        }
    }

    private fun userPrompt(topic: String, hits: List<WebSearchHit>): String {
        val context = if (hits.isEmpty()) {
            "No live web results were available; fall back to your own knowledge of the topic."
        } else {
            buildString {
                appendLine("Live web search results — ground your answer in ONLY these:")
                hits.forEachIndexed { i, hit ->
                    appendLine()
                    appendLine("[${i + 1}] ${hit.title}")
                    appendLine("URL: ${hit.url}")
                    // Cap each page's content so the prompt stays bounded across many hits.
                    appendLine(hit.content.take(MAX_CONTENT_CHARS))
                }
            }
        }
        return "Topic: $topic\n\n$context"
    }

    companion object {
        private const val MAX_CONTENT_CHARS = 2000

        internal val SYSTEM_PROMPT = """
You are Sapphire's feed search. Using the live web results provided, identify REAL,
currently-available RSS/Atom/JSON feeds a reader could subscribe to for the topic.

Grounding rules (most important):
- Base every result on the provided web content. Prefer actual feed endpoints you can see:
  /feed, /rss, /atom.xml, /feeds/posts/default, a JSON Feed, an aggregator entry, or a
  <link rel="alternate" type="application/rss+xml"> found in the page content.
- For a site in the results that is clearly an active blog/news source but whose feed URL
  is not shown, you may return its standard feed path — but only if that site appeared in
  the results. Never fabricate a URL for a site that did not.
- Return 3-8 results. Favor active feeds with recent posts.
- Skip paywalled, login-gated, or dead sources.
- feed.kind is one of: rss, atom, json.
- If no live results were provided and you must use your own knowledge, return only feeds
  you are highly confident still exist.
- Output STRICT JSON matching this shape:
  {"results":[{"title":string,"url":string,"kind":string,"description":string}]}
- No prose outside the JSON object.
        """.trimIndent()
    }
}
