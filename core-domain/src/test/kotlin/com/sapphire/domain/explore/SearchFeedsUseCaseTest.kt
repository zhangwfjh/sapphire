package com.sapphire.domain.explore

import com.sapphire.domain.llm.FeedSearchResponse
import com.sapphire.domain.llm.FeedSearchResult
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFeedsUseCaseTest {

    @Test
    fun `blank query returns Empty error without calling LLM or web search`() = runTest {
        val llm = RecordingLlm(null)
        val web = RecordingWebSearch()
        val useCase = SearchFeedsUseCase(llm, web)

        val outcome = useCase.invoke("   ")

        assertTrue(outcome is LlmOutcome.Err)
        assertTrue((outcome as LlmOutcome.Err).error is LlmError.Empty)
        assertEquals(0, llm.callCount)
        assertEquals(0, web.calls)
    }

    @Test
    fun `URL query returns single result without calling LLM or web search`() = runTest {
        val llm = RecordingLlm(null)
        val web = RecordingWebSearch()
        val useCase = SearchFeedsUseCase(llm, web)

        val outcome = useCase.invoke("https://hnrss.org/frontpage")

        assertTrue(outcome is LlmOutcome.Ok)
        val results = (outcome as LlmOutcome.Ok).value
        assertEquals(1, results.size)
        assertEquals("https://hnrss.org/frontpage", results[0].url)
        assertEquals("hnrss.org/frontpage", results[0].title)
        assertEquals(0, llm.callCount)
        assertEquals(0, web.calls)
    }

    @Test
    fun `URL query without scheme still detected as URL`() = runTest {
        val llm = RecordingLlm(null)
        val web = RecordingWebSearch()
        val useCase = SearchFeedsUseCase(llm, web)

        val outcome = useCase.invoke("example.com/feed.xml")

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(0, llm.callCount)
        assertEquals(0, web.calls)
    }

    @Test
    fun `topic query retrieves web hits then calls Tier-1 and returns mapped results`() = runTest {
        val response = FeedSearchResponse(
            results = listOf(
                FeedSearchResult(title = "AI Blog", url = "https://example.com/ai", kind = "rss"),
            ),
        )
        val llm = RecordingLlm(response)
        val web = RecordingWebSearch(
            listOf(WebSearchHit(title = "AI Blog", url = "https://example.com", content = "posts about AI")),
        )
        val useCase = SearchFeedsUseCase(llm, web)

        val outcome = useCase.invoke("artificial intelligence")

        assertTrue(outcome is LlmOutcome.Ok)
        val results = (outcome as LlmOutcome.Ok).value
        assertEquals(1, results.size)
        assertEquals("AI Blog", results[0].title)
        assertEquals(1, web.calls)
        assertEquals("artificial intelligence RSS feed", web.lastQuery)
        assertEquals(1, llm.callCount)
        assertEquals(LlmTier.TIER1_FAST, llm.lastTier)
        // Retrieval query is enriched, but the topic label the LLM sees stays original.
        assertTrue(llm.lastUserPrompt.orEmpty().startsWith("Topic: artificial intelligence\n"))
    }

    @Test
    fun `web hits are injected into the LLM prompt as grounding context`() = runTest {
        val llm = RecordingLlm(FeedSearchResponse(results = emptyList()))
        val web = RecordingWebSearch(
            listOf(WebSearchHit(title = "AI Blog", url = "https://example.com", content = "subscribe via RSS")),
        )
        val useCase = SearchFeedsUseCase(llm, web)

        useCase.invoke("artificial intelligence")

        val prompt = llm.lastUserPrompt.orEmpty()
        assertTrue("prompt must carry the hit URL", prompt.contains("https://example.com"))
        assertTrue("prompt must carry the hit content", prompt.contains("subscribe via RSS"))
    }

    @Test
    fun `topic query still calls LLM when web search returns nothing`() = runTest {
        val llm = RecordingLlm(FeedSearchResponse(results = emptyList()))
        val web = RecordingWebSearch(emptyList())
        val useCase = SearchFeedsUseCase(llm, web)

        useCase.invoke("biohacking")

        assertEquals(1, web.calls)
        assertEquals(1, llm.callCount)
        assertTrue(llm.lastUserPrompt.orEmpty().contains("No live web results"))
    }

    @Test
    fun `LLM error propagates`() = runTest {
        val llm = RecordingLlm(error = LlmError.Timeout)
        val web = RecordingWebSearch()
        val useCase = SearchFeedsUseCase(llm, web)

        val outcome = useCase.invoke("biohacking")

        assertTrue(outcome is LlmOutcome.Err)
        assertEquals(LlmError.Timeout, (outcome as LlmOutcome.Err).error)
    }

    @Test
    fun `empty results from LLM returns Empty error`() = runTest {
        val llm = RecordingLlm(FeedSearchResponse(results = emptyList()))
        val web = RecordingWebSearch()
        val useCase = SearchFeedsUseCase(llm, web)

        val outcome = useCase.invoke("obscure topic")

        assertTrue(outcome is LlmOutcome.Err)
        assertTrue((outcome as LlmOutcome.Err).error is LlmError.Empty)
    }

    // ---------- helpers ----------

    private class RecordingLlm(
        private val response: FeedSearchResponse? = null,
        private val error: LlmError? = null,
    ) : LlmClient {
        var callCount = 0
            private set
        var lastTier: LlmTier? = null
            private set
        var lastUserPrompt: String? = null
            private set

        override suspend fun <T> completeStructured(
            tier: LlmTier,
            systemPrompt: String,
            userPrompt: String,
            outputSerializer: KSerializer<T>,
        ): LlmOutcome<T> {
            callCount++
            lastTier = tier
            lastUserPrompt = userPrompt
            return when {
                error != null -> LlmOutcome.Err(error)
                else -> LlmOutcome.Ok(response as T)
            }
        }

        override fun streamText(
            tier: LlmTier,
            systemPrompt: String,
            userPrompt: String,
        ): kotlinx.coroutines.flow.Flow<LlmOutcome<String>> =
            kotlinx.coroutines.flow.flowOf(LlmOutcome.Err(LlmError.InvalidResponse))
    }

    private class RecordingWebSearch(private val hits: List<WebSearchHit> = emptyList()) : WebSearchClient {
        var calls = 0
            private set
        var lastQuery: String? = null
            private set

        override suspend fun search(query: String): List<WebSearchHit> {
            calls++
            lastQuery = query
            return hits
        }
    }
}
