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
    fun `blank query returns Empty error without calling LLM`() = runTest {
        val useCase = SearchFeedsUseCase(RecordingLlm(null))
        val outcome = useCase.invoke("   ")
        assertTrue(outcome is LlmOutcome.Err)
        assertTrue((outcome as LlmOutcome.Err).error is LlmError.Empty)
    }

    @Test
    fun `URL query returns single result without calling LLM`() = runTest {
        val llm = RecordingLlm(null)
        val useCase = SearchFeedsUseCase(llm)

        val outcome = useCase.invoke("https://hnrss.org/frontpage")

        assertTrue(outcome is LlmOutcome.Ok)
        val results = (outcome as LlmOutcome.Ok).value
        assertEquals(1, results.size)
        assertEquals("https://hnrss.org/frontpage", results[0].url)
        assertEquals("hnrss.org/frontpage", results[0].title)
        assertEquals(0, llm.callCount)
    }

    @Test
    fun `URL query without scheme still detected as URL`() = runTest {
        val llm = RecordingLlm(null)
        val useCase = SearchFeedsUseCase(llm)

        val outcome = useCase.invoke("example.com/feed.xml")

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(0, llm.callCount)
    }

    @Test
    fun `topic query calls Tier-1 and returns mapped results`() = runTest {
        val response = FeedSearchResponse(
            results = listOf(
                FeedSearchResult(title = "AI Blog", url = "https://example.com/ai", kind = "rss"),
            ),
        )
        val llm = RecordingLlm(response)
        val useCase = SearchFeedsUseCase(llm)

        val outcome = useCase.invoke("artificial intelligence")

        assertTrue(outcome is LlmOutcome.Ok)
        val results = (outcome as LlmOutcome.Ok).value
        assertEquals(1, results.size)
        assertEquals("AI Blog", results[0].title)
        assertEquals(1, llm.callCount)
        assertEquals(LlmTier.TIER1_FAST, llm.lastTier)
    }

    @Test
    fun `LLM error propagates`() = runTest {
        val llm = RecordingLlm(error = LlmError.Timeout)
        val useCase = SearchFeedsUseCase(llm)

        val outcome = useCase.invoke("biohacking")

        assertTrue(outcome is LlmOutcome.Err)
        assertEquals(LlmError.Timeout, (outcome as LlmOutcome.Err).error)
    }

    @Test
    fun `empty results from LLM returns Empty error`() = runTest {
        val llm = RecordingLlm(FeedSearchResponse(results = emptyList()))
        val useCase = SearchFeedsUseCase(llm)

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

        override suspend fun <T> completeStructured(
            tier: LlmTier,
            systemPrompt: String,
            userPrompt: String,
            outputSerializer: KSerializer<T>,
        ): LlmOutcome<T> {
            callCount++
            lastTier = tier
            return when {
                error != null -> LlmOutcome.Err(error)
                else -> LlmOutcome.Ok(response as T)
            }
        }
    }
}
