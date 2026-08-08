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
    fun `enhance returns the generated fields`() = runTest {
        val generated = GeneratedDirective(task = "Step 1", format = "Bullets", rules = "Max 100w")
        val llm = StubLlm(generated)
        val service = EnhanceDirectiveService(llm)

        val outcome = service.enhance("summarize TED talks", 1)

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals("Step 1", (outcome as LlmOutcome.Ok).value.task)
        assertEquals("Bullets", outcome.value.format)
        assertEquals("Max 100w", outcome.value.rules)
    }

    @Test
    fun `enhance uses TIER1_FAST`() = runTest {
        val llm = StubLlm(GeneratedDirective())
        val service = EnhanceDirectiveService(llm)

        service.enhance("any goal", 1)

        assertEquals(LlmTier.TIER1_FAST, llm.lastTier)
    }

    @Test
    fun `enhance LLM error propagates as Err`() = runTest {
        val llm = StubLlm<GeneratedDirective>(error = LlmError.Timeout)
        val service = EnhanceDirectiveService(llm)

        val outcome = service.enhance("any goal", 1)

        assertTrue(outcome is LlmOutcome.Err)
        assertEquals(LlmError.Timeout, (outcome as LlmOutcome.Err).error)
    }

    @Test
    fun `enhance prompt includes the goal`() = runTest {
        val llm = StubLlm(GeneratedDirective())
        val service = EnhanceDirectiveService(llm)

        service.enhance("track AI papers", 3)

        assertTrue(llm.lastUserPrompt!!.contains("track AI papers"))
        assertTrue(llm.lastUserPrompt!!.contains("3"))
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
