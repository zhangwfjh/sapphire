package com.sapphire.domain.agent

import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRecency
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.model.AgentStyle
import com.sapphire.domain.model.OutputLanguage
import com.sapphire.domain.model.SearchTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentSynthesisService (design: Slice B). Exercises the search→synth pipeline with
 * hand-rolled fakes (house pattern — no mocking frameworks). Verifies: hits are passed
 * to the LLM prompt, the result type decodes, empty-search degrades gracefully, and LLM
 * errors propagate as LlmOutcome.Err.
 */
class AgentSynthesisServiceTest {

    @Test
    fun `successful synth returns the items from the LLM`() = runTest {
        val llm = StubLlm(
            AgentSynthesisResult(
                items = listOf(
                    AgentSynthesisItem("vLLM 0.7", summary = "prefill split", body = "details"),
                    AgentSynthesisItem("SGLang", summary = "radix cache"),
                ),
            ),
        )
        val service = AgentSynthesisService(llm, StubSearch(listOf(hit("vLLM", "https://vllm.ai", "fast"))))
        val outcome = service.run(job("Scanner", "LLM infra"))
        assertTrue(outcome is LlmOutcome.Ok)
        val result = (outcome as LlmOutcome.Ok).value
        assertEquals(2, result.items.size)
        assertEquals("vLLM 0.7", result.items[0].title)
    }

    @Test
    fun `search failure degrades to empty hits and synth still runs`() = runTest {
        val llm = StubLlm(AgentSynthesisResult(emptyList()))
        val service = AgentSynthesisService(llm, StubSearch(throws = true))
        val outcome = service.run(job("Scanner", "anything"))
        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(0, (outcome as LlmOutcome.Ok).value.items.size)
    }

    @Test
    fun `LLM error propagates as Err`() = runTest {
        val llm = StubLlm<AgentSynthesisResult>(result = AgentSynthesisResult(emptyList()), error = LlmError.NotConfigured)
        val service = AgentSynthesisService(llm, StubSearch(emptyList()))
        val outcome = service.run(job("Scanner", "anything"))
        assertTrue(outcome is LlmOutcome.Err)
        assertEquals(LlmError.NotConfigured, (outcome as LlmOutcome.Err).error)
    }

    @Test
    fun `synth uses TIER1_FAST`() = runTest {
        val llm = StubLlm(AgentSynthesisResult(emptyList()))
        val service = AgentSynthesisService(llm, StubSearch(emptyList()))
        service.run(job("Scanner", "anything"))
        assertEquals(LlmTier.TIER1_FAST, llm.lastTier)
    }

    @Test
    fun `synth prompt includes the directive and search hits`() = runTest {
        val llm = StubLlm(AgentSynthesisResult(emptyList()))
        val service = AgentSynthesisService(llm, StubSearch(listOf(hit("Found Page", "https://x.com", "the content"))))
        service.run(job("Scanner", "find widgets"))
        assertTrue("user prompt must contain directive", llm.lastUserPrompt!!.contains("find widgets"))
        assertTrue("user prompt must contain hit title", llm.lastUserPrompt!!.contains("Found Page"))
        assertTrue("user prompt must contain hit url", llm.lastUserPrompt!!.contains("https://x.com"))
    }

    @Test
    fun `system prompt encodes style and language`() = runTest {
        val llm = StubLlm(AgentSynthesisResult(emptyList()))
        val service = AgentSynthesisService(llm, StubSearch(emptyList()))
        service.run(job("Scanner", "anything", style = AgentStyle.ACADEMIC, lang = OutputLanguage.ZH))
        assertTrue(llm.lastSystemPrompt!!.contains("academic rigor"))
        assertTrue(llm.lastSystemPrompt!!.contains("中文"))
    }

    // ---- fakes (house pattern) ----

    private class StubSearch(private val hits: List<WebSearchHit> = emptyList(), private val throws: Boolean = false) : WebSearchClient {
        override suspend fun search(query: String): List<WebSearchHit> {
            if (throws) error("search down")
            return hits
        }
    }

    private class StubLlm<T>(private val result: T, private val error: LlmError? = null) : LlmClient {
        var lastTier: LlmTier? = null
        var lastSystemPrompt: String? = null
        var lastUserPrompt: String? = null

        @Suppress("UNCHECKED_CAST")
        override suspend fun <U> completeStructured(
            tier: LlmTier,
            systemPrompt: String,
            userPrompt: String,
            outputSerializer: KSerializer<U>,
        ): LlmOutcome<U> {
            lastTier = tier
            lastSystemPrompt = systemPrompt
            lastUserPrompt = userPrompt
            return if (error != null) LlmOutcome.Err(error) else LlmOutcome.Ok(result as U)
        }

        override fun streamText(tier: LlmTier, systemPrompt: String, userPrompt: String) =
            throw NotImplementedError("not used by agent synth")
    }

    private fun hit(title: String, url: String, content: String) = WebSearchHit(title, url, content)

    private fun job(
        name: String,
        directive: String,
        style: AgentStyle = AgentStyle.BRIEF,
        lang: OutputLanguage = OutputLanguage.EN,
    ) = AgentJob(
        id = "j1", name = name, directive = directive,
        searchTool = SearchTool.TAVILY, frequency = AgentFrequency.DAILY, triggerTime = "07:00",
        recency = AgentRecency.WEEK, outputLanguage = lang, style = style,
        enabled = true, nextRunIntentEpochMs = null, createdAt = 0L,
    )
}
