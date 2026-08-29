package com.sapphire.domain.agent

import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import kotlinx.serialization.Serializable

/**
 * The questionnaire blueprint: three candidate answers per question plus goal-derived
 * requirement tags for the "Anything else?" slot. Returned by [EnhanceDirectiveService];
 * [AgentBlueprintHeuristics] is the offline fallback so the Tune step is never empty
 * (no LLM configured, generation failed, or user skipped generation).
 */
@Serializable
data class AgentBlueprint(
    val taskVariants: List<String> = emptyList(),
    val formatVariants: List<String> = emptyList(),
    val ruleVariants: List<String> = emptyList(),
    val extraTags: List<String> = emptyList(),
) {
    val isUsable: Boolean get() = taskVariants.isNotEmpty() && formatVariants.isNotEmpty() && ruleVariants.isNotEmpty()

    companion object {
        val EMPTY = AgentBlueprint()
    }
}

/**
 * Expands a short user goal into the questionnaire blueprint via one Tier-1 LLM call:
 * three candidate task/format/rule answers plus ~8 requirement tags derived from the
 * goal. Pure domain, injectable [LlmClient], typed [LlmOutcome]. Never throws.
 *
 * Callers fall back to [AgentBlueprintHeuristics.blueprint] when the outcome is
 * [LlmOutcome.Err] or the payload is not usable — the questionnaire always renders.
 */
class EnhanceDirectiveService(
    private val llm: LlmClient,
) {
    suspend fun enhance(goal: String, maxItems: Int, extraNotes: String = ""): LlmOutcome<AgentBlueprint> {
        val userPrompt = buildString {
            appendLine("Goal: $goal")
            appendLine("Max items per run: $maxItems")
            if (extraNotes.isNotBlank()) appendLine("User's extra requirements (honor these): $extraNotes")
            appendLine()
            appendLine("Generate the blueprint.")
        }
        return llm.completeStructured(
            tier = LlmTier.TIER1_FAST,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = userPrompt,
            outputSerializer = AgentBlueprint.serializer(),
        )
    }

    private companion object {
        val SYSTEM_PROMPT = """
            You expand a short goal into a questionnaire for building a feed agent. Return JSON with four arrays.

            "taskVariants": exactly 3 candidate answers to "what should the agent do each run?" — three genuinely different editorial strategies for the same goal (e.g. broad sweep vs deep dive vs changes-only). Describe the STRATEGY only: what to look for, how to prioritize, what angle to take. Do NOT describe mechanics — web search, fetching pages, and reading full text are built in and always happen, so never write phrases like "search the web" or "fetch pages". Each variant is 1-2 sentences.

            "formatVariants": exactly 3 candidate answers to "what should each filed item look like?" — e.g. brief summary cards, one flowing editorial paragraph, a bullet deck with sources. Describe shape, length, and tone in plain language for the user. NEVER mention HTML tags or markup (no <h2>, <blockquote>, etc.) — rendering requirements are injected separately by the system. First is the default.

            "ruleVariants": exactly 3 candidate answers to "hard constraints?" — each a set of 2-4 one-line rules phrased as "Should ..." or "Should not ...", covering sourcing, quality gates, and scope (freshness, language). First is the default.

            "extraTags": 6-8 short requirement tags (2-5 words each) a user might tap on for THIS goal — a mix of goal-specific (e.g. "include CVE IDs" for security, "link changelogs" for releases) and generally useful ("primary sources only", "English only"). No duplicates, no trailing punctuation.

            Everything must be directly grounded in the goal's topic. No placeholders like [TOPIC], no first-person references ("my", "I") — the system knows nothing about the user.
        """.trimIndent()
    }
}
