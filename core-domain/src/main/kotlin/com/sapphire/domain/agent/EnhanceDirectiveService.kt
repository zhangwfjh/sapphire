package com.sapphire.domain.agent

import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import kotlinx.serialization.Serializable

/**
 * The three generated fields (goal is user-provided). Returned by [EnhanceDirectiveService].
 */
@Serializable
data class GeneratedDirective(
    val task: String = "",
    val format: String = "",
    val rules: String = "",
)

/**
 * Expands a short user goal into a structured prompt (task, format, rules) via one Tier-1
 * LLM call. Pure domain, injectable [LlmClient], typed [LlmOutcome]. Never throws.
 *
 * RSS-style output guidance is baked into the format generation so every enhanced agent
 * produces feed-readable items.
 */
class EnhanceDirectiveService(
    private val llm: LlmClient,
) {
    suspend fun enhance(goal: String, maxItems: Int): LlmOutcome<GeneratedDirective> {
        val systemPrompt = buildSystemPrompt()
        val userPrompt = "Goal: " + goal + "\nMax items per run: " + maxItems + "\n\nGenerate the task, format, and rules fields."
        return llm.completeStructured(
            tier = LlmTier.TIER1_FAST,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            outputSerializer = GeneratedDirective.serializer(),
        )
    }

    private fun buildSystemPrompt(): String =
        "You expand a short goal into a structured agent prompt. Given the user's goal, produce three fields:\n" +
        "\n" +
        "1. \"task\": A step-by-step procedure the agent follows each run. Use concrete actions (search, fetch, read, summarize). Be specific about what tools to use and in what order. Encourage the agent to read full content (transcripts, articles) before synthesizing.\n" +
        "\n" +
        "2. \"format\": How each item should look in the feed. The body is rendered as HTML: use <h2> for section headings, <p> for paragraphs, <ul><li> or <ol><li> for lists, <blockquote> for quotes. Specify title style (under 80 chars, plain text), body structure, length guidance (e.g. 300-500 words for single-item digests, 150-250 for multi-item), and tone. Default to full prose paragraphs — rich and substantive. Only suggest bullets if the goal clearly calls for a list format.\n" +
        "\n" +
        "3. \"rules\": Constraints, restrictions, and quality guards. Include: what to skip or avoid, minimum content depth, anti-fabrication rules, and edge-case handling.\n" +
        "\n" +
        "Return JSON: {\"task\": \"...\", \"format\": \"...\", \"rules\": \"...\"}"
}
