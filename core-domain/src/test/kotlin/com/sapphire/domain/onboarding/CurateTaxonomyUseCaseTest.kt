package com.sapphire.domain.onboarding

import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.TaxonomyFeed
import com.sapphire.domain.llm.TaxonomyL1
import com.sapphire.domain.llm.TaxonomyL2
import com.sapphire.domain.llm.TaxonomyResponse
import com.sapphire.domain.review.ReviewBuilder
import com.sapphire.domain.util.IdGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurateTaxonomyUseCaseTest {

    @Test
    fun `empty phrase returns Empty error`() = runTest {
        val useCase = useCase(StubLlm(TaxonomyResponse(categories = emptyList())))
        val outcome = useCase.invoke("   ")
        assertEquals(
            LlmError.Empty("Type a topic to begin."),
            (outcome as LlmOutcome.Err).error,
        )
    }

    @Test
    fun `LLM returns no categories returns Empty error`() = runTest {
        val useCase = useCase(StubLlm(TaxonomyResponse(categories = emptyList())))
        val outcome = useCase.invoke("Artificial Intelligence")
        assertTrue(outcome is LlmOutcome.Err)
        assertEquals(
            "No categories found. Try a broader topic.",
            (outcome as LlmOutcome.Err).error.userMessage(),
        )
    }

    @Test
    fun `non-empty response builds review model with ids and trimmed names`() = runTest {
        val useCase = useCase(StubLlm(sampleResponse()))
        val outcome = useCase.invoke("  Biohacking  ")
        assertTrue(outcome is LlmOutcome.Ok)
        val model = (outcome as LlmOutcome.Ok).value

        assertEquals("Biohacking", model.topicPhrase)
        assertEquals(1, model.folders.size)
        assertEquals("Health & Performance", model.folders.first().name)

        val folder = model.folders.first()
        // Both L2s' keywords merge into the folder.
        assertEquals(listOf("sleep", "circadian", "racetams"), folder.keywords.map { it.text })
        assertEquals(listOf(false, false, false), folder.keywords.map { it.userAdded })

        val feed = folder.feeds.single()
        assertEquals("Sleep Sci Weekly", feed.title)
        assertEquals("https://example.com/sleep.xml", feed.url)
        assertTrue(!feed.userAdded)
    }

    @Test
    fun `unknown feed kind falls back to RSS`() = runTest {
        val resp = TaxonomyResponse(
            categories = listOf(
                TaxonomyL1(
                    name = "Tech",
                    level2 = listOf(
                        TaxonomyL2(
                            name = "AI",
                            keywords = listOf("llm"),
                            feeds = listOf(
                                TaxonomyFeed(title = "A", url = "https://a.feed", kind = "weird-kind"),
                                TaxonomyFeed(title = "B", url = "https://b.feed", kind = "atom"),
                                TaxonomyFeed(title = "C", url = "https://c.feed", kind = "json"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val useCase = useCase(StubLlm(resp))
        val outcome = useCase.invoke("Tech") as LlmOutcome.Ok
        val kinds = outcome.value.folders.first().feeds.map { it.kind }
            assertEquals(
                listOf(
                    com.sapphire.domain.model.SourceKind.RSS,
                    com.sapphire.domain.model.SourceKind.ATOM,
                    com.sapphire.domain.model.SourceKind.JSON,
                ),
                kinds,
            )
    }

    @Test
    fun `LLM timeout propagates as Timeout error`() = runTest {
        val useCase = useCase(StubLlm(error = LlmError.Timeout))
        val outcome = useCase.invoke("AI")
        assertTrue(outcome is LlmOutcome.Err)
        assertEquals(LlmError.Timeout, (outcome as LlmOutcome.Err).error)
    }

    @Test
    fun `LLM invalid response propagates as InvalidResponse`() = runTest {
        val useCase = useCase(StubLlm(error = LlmError.InvalidResponse))
        val outcome = useCase.invoke("AI")
        assertTrue(outcome is LlmOutcome.Err)
        assertEquals(LlmError.InvalidResponse, (outcome as LlmOutcome.Err).error)
    }

    @Test
    fun `duplicate feed urls within L2 are deduplicated`() = runTest {
        val resp = TaxonomyResponse(
            categories = listOf(
                TaxonomyL1(
                    name = "X",
                    level2 = listOf(
                        TaxonomyL2(
                            name = "Y",
                            keywords = emptyList(),
                            feeds = listOf(
                                TaxonomyFeed(title = "First", url = "https://dup.feed", kind = "rss"),
                                TaxonomyFeed(title = "Second", url = "https://dup.feed", kind = "rss"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val useCase = useCase(StubLlm(resp))
        val outcome = useCase.invoke("X") as LlmOutcome.Ok
        val feeds = outcome.value.folders.first().feeds
        assertEquals(1, feeds.size)
        assertEquals("First", feeds.first().title)
    }

    @Test
    fun `blank feed title or url is filtered out`() = runTest {
        val resp = TaxonomyResponse(
            categories = listOf(
                TaxonomyL1(
                    name = "X",
                    level2 = listOf(
                        TaxonomyL2(
                            name = "Y",
                            keywords = emptyList(),
                            feeds = listOf(
                                TaxonomyFeed(title = "Good", url = "https://good.feed", kind = "rss"),
                                TaxonomyFeed(title = "", url = "https://blank-title.feed", kind = "rss"),
                                TaxonomyFeed(title = "NoUrl", url = "", kind = "rss"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val useCase = useCase(StubLlm(resp))
        val outcome = useCase.invoke("X") as LlmOutcome.Ok
        val feeds = outcome.value.folders.first().feeds
        assertEquals(1, feeds.size)
        assertEquals("https://good.feed", feeds.first().url)
    }

    // ---------- helpers ----------

    private fun useCase(llm: LlmClient): CurateTaxonomyUseCase =
        CurateTaxonomyUseCase(
            llm = llm,
            reviewBuilder = ReviewBuilder(SequentialIdGenerator),
        )

    private fun sampleResponse() = TaxonomyResponse(
        categories = listOf(
            TaxonomyL1(
                name = "Health & Performance",
                level2 = listOf(
                    TaxonomyL2(
                        name = "Sleep Optimization",
                        keywords = listOf("sleep", "circadian"),
                        feeds = listOf(
                            TaxonomyFeed(title = "Sleep Sci Weekly", url = "https://example.com/sleep.xml", kind = "rss"),
                        ),
                    ),
                    TaxonomyL2(name = "Nootropics", keywords = listOf("racetams"), feeds = emptyList()),
                ),
            ),
        ),
    )

    /** LLM stub with fixed output or fixed error. Serializer is accepted but ignored. */
    private class StubLlm(
        private val response: TaxonomyResponse? = null,
        private val error: LlmError? = null,
    ) : LlmClient {
        override suspend fun <T> completeStructured(
            tier: com.sapphire.domain.llm.LlmTier,
            systemPrompt: String,
            userPrompt: String,
            outputSerializer: kotlinx.serialization.KSerializer<T>,
        ): LlmOutcome<T> = when {
            error != null -> LlmOutcome.Err(error)
            else -> LlmOutcome.Ok(response as T)
        }
    }

    /** Deterministic ids for stable assertions in tests. */
    private object SequentialIdGenerator : IdGenerator {
        private var counter = 0
        override fun uuid(): String = "id-${counter++}"
    }
}
