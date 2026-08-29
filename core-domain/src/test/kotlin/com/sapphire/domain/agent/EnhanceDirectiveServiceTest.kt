package com.sapphire.domain.agent

import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.llm.ToolDefinition
import com.sapphire.domain.llm.ToolMessage
import com.sapphire.domain.llm.ToolTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhanceDirectiveServiceTest {

    @Test
    fun `enhance returns the generated blueprint`() = runTest {
        val generated = AgentBlueprint(
            taskVariants = listOf("Sweep", "Deep dive", "Changes only"),
            formatVariants = listOf("Brief", "Editorial", "Deck"),
            ruleVariants = listOf("R1", "R2", "R3"),
            extraTags = listOf("English only", "primary sources only"),
        )
        val llm = StubLlm(generated)
        val service = EnhanceDirectiveService(llm)

        val outcome = service.enhance("summarize TED talks", 1)

        assertTrue(outcome is LlmOutcome.Ok)
        val blueprint = (outcome as LlmOutcome.Ok).value
        assertEquals(3, blueprint.taskVariants.size)
        assertEquals("Sweep", blueprint.taskVariants.first())
        assertEquals(listOf("English only", "primary sources only"), blueprint.extraTags)
        assertTrue(blueprint.isUsable)
    }

    @Test
    fun `enhance uses TIER1_FAST`() = runTest {
        val llm = StubLlm(AgentBlueprint.EMPTY)
        val service = EnhanceDirectiveService(llm)

        service.enhance("any goal", 1)

        assertEquals(LlmTier.TIER1_FAST, llm.lastTier)
    }

    @Test
    fun `enhance LLM error propagates as Err`() = runTest {
        val llm = StubLlm<AgentBlueprint>(error = LlmError.Timeout)
        val service = EnhanceDirectiveService(llm)

        val outcome = service.enhance("any goal", 1)

        assertTrue(outcome is LlmOutcome.Err)
        assertEquals(LlmError.Timeout, (outcome as LlmOutcome.Err).error)
    }

    @Test
    fun `enhance prompt includes goal maxItems and extra notes`() = runTest {
        val llm = StubLlm(AgentBlueprint.EMPTY)
        val service = EnhanceDirectiveService(llm)

        service.enhance("track AI papers", 3, extraNotes = "Focus on: pricing")

        assertTrue(llm.lastUserPrompt!!.contains("track AI papers"))
        assertTrue(llm.lastUserPrompt!!.contains("3"))
        assertTrue(llm.lastUserPrompt!!.contains("Focus on: pricing"))
    }

    @Test
    fun `empty blueprint is not usable`() {
        assertFalse(AgentBlueprint.EMPTY.isUsable)
    }

    private fun assertFalse(v: Boolean) = org.junit.Assert.assertFalse(v)

    @Test
    fun `heuristics blueprint is usable and matches goal`() {
        val bp = AgentBlueprintHeuristics.blueprint("Monitor security advisories and breaches", 2)

        assertTrue(bp.isUsable)
        assertEquals(3, bp.taskVariants.size)
        assertEquals(3, bp.formatVariants.size)
        assertEquals(3, bp.ruleVariants.size)
        // Security archetype tags surface.
        assertTrue(bp.extraTags.any { it.equals("include CVE IDs", ignoreCase = true) })
        // Generic tail always present.
        assertTrue(bp.extraTags.contains("English only"))
        // Task variants reference the goal's primary keyword.
        assertTrue(bp.taskVariants.first().contains("security", ignoreCase = true))
    }

    @Test
    fun `heuristics blueprint caps tags at 8`() {
        val bp = AgentBlueprintHeuristics.blueprint("Watch pricing plans and subscription costs", 1)

        assertTrue(bp.extraTags.size <= 8)
    }

    @Test
    fun `sample synthesizer follows format choice`() {
        val prose = AgentSampleSynthesizer.synthesize(
            goal = "Track LLM inference engines",
            task = "sweep", format = "editorial paragraph", rules = "cite 2 sources",
        )
        val bullets = AgentSampleSynthesizer.synthesize(
            goal = "Track LLM inference engines",
            task = "sweep", format = "bullet deck <ul>", rules = "cite 2 sources",
        )

        assertTrue(prose.bodyHtml.contains("<p>"))
        assertTrue(bullets.bodyHtml.contains("<ul>"))
        assertTrue(prose.title.isNotBlank())
        assertTrue(prose.sourceDomains.isNotEmpty())
    }

    private class StubLlm<T>(
        private val result: T? = null,
        private val error: LlmError? = null,
    ) : LlmClient {
        var lastTier: LlmTier? = null
        var lastUserPrompt: String? = null
        var lastSystemPrompt: String? = null

        @Suppress("UNCHECKED_CAST")
        override suspend fun <U> completeStructured(
            tier: LlmTier, systemPrompt: String, userPrompt: String, outputSerializer: KSerializer<U>,
        ): LlmOutcome<U> {
            lastTier = tier; lastSystemPrompt = systemPrompt; lastUserPrompt = userPrompt
            return if (error != null) LlmOutcome.Err(error) else LlmOutcome.Ok(result as U)
        }

        override fun streamText(tier: LlmTier, systemPrompt: String, userPrompt: String): Flow<LlmOutcome<String>> =
            flow { throw NotImplementedError("not used") }

        override suspend fun completeWithTools(
            tier: LlmTier, systemPrompt: String, conversation: List<ToolMessage>, tools: List<ToolDefinition>,
        ): LlmOutcome<ToolTurn> = throw NotImplementedError("not used")
    }
}
