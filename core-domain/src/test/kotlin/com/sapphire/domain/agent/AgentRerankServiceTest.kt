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

/**
 * AgentRerankService — the post-loop acceptance pass. Defends:
 * - Hard cross-run URL dedup happens without any LLM call.
 * - The Tier-2 verdict drops only listed, in-range indices.
 * - Any LLM failure is non-fatal (items returned unchanged).
 * - An all-drop verdict never empties a non-empty batch.
 */
class AgentRerankServiceTest {

    private fun item(title: String, url: String?) =
        AgentSynthesisItem(title = title, summary = "s", body = "body of $title", url = url)

    @Test
    fun `already-filed url is dropped without an LLM call`() = runTest {
        val llm = StubLlm("""{"drop":[99]}""", shouldThrow = IllegalStateException("must not be called"))
        val svc = AgentRerankService(llm)

        val kept = svc.rerank(
            listOf(item("A", "https://same.example/story"), item("B", "https://new.example/x")),
            previouslyFiledUrls = listOf("https://same.example/story"),
        )

        assertEquals(listOf("B"), kept.map { it.title })
        assertEquals(0, llm.calls)
    }

    @Test
    fun `tier2 verdict drops listed indices`() = runTest {
        val llm = StubLlm("""{"drop":[1]}""")
        val svc = AgentRerankService(llm)

        val kept = svc.rerank(listOf(item("A", "https://a"), item("B", "https://b")), emptyList())

        assertEquals(listOf("A"), kept.map { it.title })
        assertEquals(1, llm.calls)
        assertEquals(LlmTier.TIER2_DEEP, llm.lastTier)
    }

    @Test
    fun `out-of-range drop indices are ignored`() = runTest {
        val llm = StubLlm("""{"drop":[5,-1]}""")
        val svc = AgentRerankService(llm)

        val kept = svc.rerank(listOf(item("A", "https://a"), item("B", "https://b")), emptyList())

        assertEquals(2, kept.size)
    }

    @Test
    fun `llm error is non-fatal`() = runTest {
        val llm = StubLlm("{}", shouldThrow = null, error = LlmError.Timeout)
        val svc = AgentRerankService(llm)

        val kept = svc.rerank(listOf(item("A", "https://a"), item("B", "https://b")), emptyList())

        assertEquals(2, kept.size)
    }

    @Test
    fun `all-drop verdict keeps the batch`() = runTest {
        val llm = StubLlm("""{"drop":[0,1]}""")
        val svc = AgentRerankService(llm)

        val kept = svc.rerank(listOf(item("A", "https://a"), item("B", "https://b")), emptyList())

        assertEquals(2, kept.size)
    }

    @Test
    fun `single novel item skips the tier2 check`() = runTest {
        val llm = StubLlm("""{"drop":[0]}""")
        val svc = AgentRerankService(llm)

        val kept = svc.rerank(listOf(item("A", "https://a")), listOf("https://old"))

        // A lone candidate has nothing to compare against — deterministic pass only.
        assertEquals(listOf("A"), kept.map { it.title })
        assertEquals(0, llm.calls)
    }

    @Test
    fun `www and trailing slash normalize to the same url`() = runTest {
        val llm = StubLlm("{}", shouldThrow = IllegalStateException("must not be called"))
        val svc = AgentRerankService(llm)

        val kept = svc.rerank(
            listOf(item("A", "https://www.example.com/story/")),
            previouslyFiledUrls = listOf("https://example.com/story"),
        )

        assertTrue(kept.isEmpty())
    }

    /** Structured-output stub: returns a canned JSON verdict; optional failure modes. */
    private class StubLlm(
        private val verdictJson: String,
        private val shouldThrow: Exception? = null,
        private val error: LlmError? = null,
    ) : LlmClient {
        var calls = 0
        var lastTier: LlmTier? = null

        override suspend fun <T> completeStructured(
            tier: LlmTier, systemPrompt: String, userPrompt: String, outputSerializer: KSerializer<T>,
        ): LlmOutcome<T> {
            lastTier = tier
            calls++
            shouldThrow?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return when (error) {
                null -> LlmOutcome.Ok(json.decodeFromJsonElement(outputSerializer, kotlinx.serialization.json.Json.parseToJsonElement(verdictJson))) as LlmOutcome<T>
                else -> LlmOutcome.Err(error)
            }
        }

        override fun streamText(tier: LlmTier, systemPrompt: String, userPrompt: String): Flow<LlmOutcome<String>> =
            flow { throw NotImplementedError("not used") }

        override suspend fun completeWithTools(
            tier: LlmTier, systemPrompt: String, conversation: List<ToolMessage>, tools: List<ToolDefinition>,
        ): LlmOutcome<ToolTurn> = throw NotImplementedError("not used")

        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
