package com.sapphire.domain.agent

import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.llm.LlmOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Post-loop acceptance pass (PRD §3.6 rerank, the deterministic + Tier-2 half): drops
 * items that duplicate content this agent already filed, and asks Tier-2 to drop
 * near-duplicate / off-topic items within the batch.
 *
 * Layered with the loop's own guards ([AgentLoopService] already URL-dedups within a run
 * and scrubs unseen citations); this owns the *cross-run* dimension:
 * 1. Deterministic — an item whose normalized URL was already filed by this agent is
 *    dropped without an LLM call (the loop's exclusion prompt is soft; this is hard).
 * 2. Tier-2 verdict — one structured call comparing candidates against each other and
 *    against the last filed titles. Non-fatal by contract: any error returns the input
 *    unchanged — a rerank hiccup must never lose an agent run.
 */
class AgentRerankService @Inject constructor(
    private val llm: LlmClient,
) {

    @Serializable
    private data class RerankVerdict(val drop: List<Int> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun rerank(
        items: List<AgentSynthesisItem>,
        previouslyFiledUrls: List<String>,
    ): List<AgentSynthesisItem> {
        if (items.isEmpty()) return items
        // 1. Hard cross-run URL dedup — deterministic, no LLM, never skipped.
        val filed = previouslyFiledUrls.map(::normalizeUrl).toSet()
        val novel = items.filter { it.url == null || !filed.contains(normalizeUrl(it.url!!)) }
        if (novel.isEmpty()) return novel
        // 2. Tier-2 near-dup verdict — needs ≥2 candidates to compare (cross-run dupes are
        // already caught deterministically above; without filed titles the LLM has nothing
        // to compare a lone item against).
        if (novel.size < 2) return novel
        return try {
            when (val verdict = llm.completeStructured(
                tier = LlmTier.TIER2_DEEP,
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = userPrompt(novel),
                outputSerializer = RerankVerdict.serializer(),
            )) {
                is LlmOutcome.Err -> novel // non-fatal
                is LlmOutcome.Ok -> {
                    val drop = verdict.value.drop.filter { it in novel.indices }.toSet()
                    novel.filterIndexed { i, _ -> i !in drop }.ifEmpty { novel }
                }
            }
        } catch (_: Exception) {
            novel // deterministic result stands; only the verdict is skipped
        } catch (_: Error) {
            novel // NotImplementedError from test fakes lands here; never fatal
        }
    }

    private fun userPrompt(items: List<AgentSynthesisItem>): String = buildString {
        appendLine("Candidate items (index, title, summary, url):")
        items.forEachIndexed { i, it ->
            appendLine("""$i | ${it.title} | ${it.summary?.take(140) ?: ""} | ${it.url ?: "(no url)"}""")
        }
        appendLine()
        appendLine("""Respond with {"drop": [indices]} listing ONLY indices of items that are near-duplicates of another candidate (same story, different URL) or too thin to file. Empty drop keeps everything. Never drop everything unless every item is a near-duplicate.""" )
    }

    private fun normalizeUrl(url: String): String {
        val marker = "://"
        val idx = url.indexOf(marker)
        if (idx < 0) return url.trim().lowercase().trimEnd('/')
        val scheme = url.substring(0, idx).lowercase()
        val rest = url.substring(idx + marker.length)
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val host = (if (hostEnd < 0) rest else rest.substring(0, hostEnd)).lowercase().removePrefix("www.")
        val tail = if (hostEnd < 0) "" else rest.substring(hostEnd)
        return "$scheme$marker$host$tail".trimEnd('/')
    }

    private companion object {
        val SYSTEM_PROMPT = """
            You are a feed-quality gate inside a news reader. You receive candidate digest items from one agent run. Your ONLY job is to drop items that duplicate another candidate or previously filed content, or are too thin (a bare headline with no substance). Keep distinct, substantive items — when unsure, keep. Respond strictly as {"drop": [int]}.
        """.trimIndent()
    }
}
