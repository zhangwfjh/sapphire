package com.sapphire.domain.explore

import com.sapphire.domain.llm.FeedSearchResponse
import com.sapphire.domain.llm.FeedSearchResult
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import java.net.URI

/**
 * Explore search — turn a topic or URL into a list of feed matches.
 *
 * URL shortcut: if the query looks like a URL (has a scheme + host, or a bare host
 * with a path), skip the LLM and return it as a single result. Otherwise call Tier-1.
 */
class SearchFeedsUseCase(
    private val llm: LlmClient,
) {

    suspend operator fun invoke(query: String): LlmOutcome<List<FeedSearchResult>> {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return LlmOutcome.Err(LlmError.Empty("Type a topic or paste a feed URL."))

        val asUrl = parseAsUrl(cleaned)
        if (asUrl != null) {
            val title = asUrl.host.lowercase() + (asUrl.path?.takeIf { it.isNotEmpty() && it != "/" } ?: "")
            return LlmOutcome.Ok(
                listOf(
                    FeedSearchResult(
                        title = title,
                        url = cleaned,
                        kind = "rss",
                        description = null,
                    ),
                ),
            )
        }

        return when (val outcome = llm.completeStructured(
            tier = LlmTier.TIER1_FAST,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = userPrompt(cleaned),
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

    private fun parseAsUrl(value: String): URI? {
        val withScheme = if (value.contains("://")) value else "https://$value"
        val parsed = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = parsed.host ?: return null
        if (!host.contains('.')) return null
        if (!value.contains("://") && !value.contains("/")) return null
        return parsed
    }

    private fun userPrompt(topic: String) = "Topic: $topic"

    companion object {
        internal val SYSTEM_PROMPT = """
You are Sapphire's feed search. For the user's topic, return high-signal RSS/Atom/JSON
feeds a reader could subscribe to.

Rules:
- Return 3-8 results. Prefer official site feeds and well-known aggregators.
- Only return feeds the user can actually subscribe to. Skip paywalled/login-gated sources.
- feed.kind is one of: rss, atom, json, rsshub.
- Output STRICT JSON matching this shape:
  {"results":[{"title":string,"url":string,"kind":string,"description":string}]}
- No prose outside the JSON object.
        """.trimIndent()
    }
}
