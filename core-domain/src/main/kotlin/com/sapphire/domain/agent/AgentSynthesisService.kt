package com.sapphire.domain.agent

import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRecency
import com.sapphire.domain.model.AgentStyle
import com.sapphire.domain.model.OutputLanguage

/**
 * The retrieve-then-synthesize pipeline for prompt agents (PRD §3.7). Pure domain —
 * takes injectable [WebSearchClient] + [LlmClient], returns a typed outcome. The
 * worker (core-data) calls this then inserts [FeedItemEntity] rows with agentTag set.
 *
 * Pipeline: search (non-fatal, empty-list fallback) → LLM structured synthesis
 * (TIER1_FAST, grounded by the search hits, shaped by the agent's style/language).
 * Always uses TIER1_FAST — the builder no longer exposes model tier.
 *
 * Mirrors the [com.sapphire.domain.explore.SearchFeedsUseCase] retrieve→generate shape.
 */
class AgentSynthesisService(
    private val llm: LlmClient,
    private val webSearch: WebSearchClient,
) {

    suspend fun run(job: AgentJob): LlmOutcome<AgentSynthesisResult> {
        val query = buildSearchQuery(job)
        val hits = runCatching { webSearch.search(query) }.getOrDefault(emptyList())

        val systemPrompt = buildSystemPrompt(job.style, job.outputLanguage, job.maxItems)
        val userPrompt = buildUserPrompt(job.directive, hits, job.recency, job.outputLanguage)

        val outcome = llm.completeStructured(
            tier = LlmTier.TIER1_FAST,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            outputSerializer = AgentSynthesisResult.serializer(),
        )

        // Post-process: guarantee every item has verifiable source links. If the LLM
        // didn't provide sources, attach the search hits as fallback references so the
        // user can always follow and verify. This is best-effort — we don't know which
        // specific hit grounded which item, so we attach all hits as general references.
        return when (outcome) {
            is LlmOutcome.Ok -> {
                val fallbackSources = hits.take(5).map { AgentSourceRef(it.title, it.url) }
                val fixedItems = outcome.value.items.map { item ->
                    if (item.sources.isEmpty() && fallbackSources.isNotEmpty()) {
                        item.copy(sources = fallbackSources)
                    } else item
                }
                LlmOutcome.Ok(AgentSynthesisResult(fixedItems))
            }
            is LlmOutcome.Err -> outcome
        }
    }

    private fun buildSearchQuery(job: AgentJob): String {
        // For hourly agents, narrow the search to recent; for weekly, broaden.
        val recencyHint = when (job.recency) {
            AgentRecency.H24 -> "today"
            AgentRecency.WEEK -> "this week"
            AgentRecency.MONTH -> "this month"
            AgentRecency.YEAR -> "this year"
            AgentRecency.ALL -> ""
        }
        return if (recencyHint.isBlank()) job.directive else "$recencyHint: ${job.directive}"
    }

    private fun buildSystemPrompt(style: AgentStyle, lang: OutputLanguage, maxItems: Int): String {
        val styleGuide = when (style) {
            AgentStyle.BRIEF -> "Write terse, high-signal analyst briefs. One paragraph per item, no filler."
            AgentStyle.BULLETED -> "Write scannable bullet-point summaries. Lead with the key fact, then supporting bullets."
            AgentStyle.CONVERSATIONAL -> "Write in a warm, conversational tone — as if explaining to a curious friend over coffee."
            AgentStyle.ACADEMIC -> "Write with academic rigor: cite sources, note methodology, qualify claims with confidence levels."
            AgentStyle.HOTTAKE -> "Write sharp, opinionated takes. Take a clear position, argue it forcefully, name what others get wrong."
            AgentStyle.EXPLAINER -> "Write as a teacher: define terms, explain why it matters, give one concrete example per item."
        }
        val langGuide = when (lang) {
            OutputLanguage.EN -> "Write in English."
            OutputLanguage.ZH -> "用中文撰写。"
            OutputLanguage.MATCH_SOURCE -> "Write in the same language as each source."
        }
        return """You are an AI research agent that synthesizes web search results into feed items.
Each item must have a clear title, a one-line summary, and a body with substance.

CITATIONS: Whenever possible, include a "sources" array with real "title" and "url" from the provided search results. Never fabricate URLs. If you include sources, cite the actual search result URLs. Do not omit items just because you forgot to add sources — sources are best-effort.

$styleGuide
$langGuide

Return JSON: {"items":[{"title":"...","summary":"...","body":"...","url":"optional source link if known"}]}
Only include items worth reading — quality over quantity. Return at most ${maxItems.coerceAtLeast(1)} item(s) per run.
If nothing notable was found, return an empty items list."""
    }

    private fun buildUserPrompt(
        directive: String,
        hits: List<WebSearchHit>,
        recency: AgentRecency,
        lang: OutputLanguage,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Directive: $directive")
        sb.appendLine()
        if (hits.isEmpty()) {
            sb.appendLine("Web search results (cite these URLs in each item's \"sources\" — never fabricate URLs):")
            hits.take(10).forEachIndexed { i, hit ->
                sb.appendLine("${i + 1}. ${hit.title}")
                sb.appendLine("   URL: ${hit.url}")
                hit.content.take(500).let { c -> if (c.isNotBlank()) sb.appendLine("   $c") }
            }
        }
        return sb.toString()
    }
}
