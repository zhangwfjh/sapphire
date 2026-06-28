package com.sapphire.domain.reader

import com.sapphire.domain.llm.ClassificationResponse
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.llm.SummaryResponse
import com.sapphire.domain.llm.TranslateResponse
import com.sapphire.domain.llm.TranslatedParagraph
import com.sapphire.domain.model.FeedItem
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderOpsUseCase] — the lazy-compute + idempotent-cache contract (PRD §4.2, S03).
 *
 *
 * - Classification is cache-first: a hit skips the LLM entirely.
 * - Classification is persisted onto the item row; a second classify() reads the persisted
 *   value without an LLM call (re-open is free).
 * - Summary / translate are cache-first per (item, op, model).
 * - Translate is keyed per target language.
 * - LLM errors propagate as typed outcomes, never exceptions.
 * - Missing item → Empty error.
 */
class ReaderOpsUseCaseTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `classification cache hit skips LLM`() = runTest {
        val llm = StubLlm(classification = ClassificationResponse("Tech Blog", 0.9))
        val cache = MemCache()
        val items = StubItems()
        val useCase = useCase(llm, cache, items)

        val first = useCase.classify("item-1")
        assertEquals("Tech Blog", (first as LlmOutcome.Ok).value.classification)
        assertEquals(1, llm.classifyCalls)

        // Second call: the item now has a persisted classification → no LLM, no cache read.
        val second = useCase.classify("item-1")
        assertEquals("Tech Blog", (second as LlmOutcome.Ok).value.classification)
        assertEquals(1, llm.classifyCalls) // unchanged
    }

    @Test
    fun `classification with persisted value skips both LLM and cache`() = runTest {
        val llm = StubLlm(classification = ClassificationResponse("News Article", 0.8))
        val items = StubItems(item = feedItem(classification = "News Article"))
        val cache = MemCache()
        val useCase = useCase(llm, cache, items)

        val out = useCase.classify("item-1")
        assertEquals("News Article", (out as LlmOutcome.Ok).value.classification)
        assertEquals(0, llm.classifyCalls)
        assertEquals(0, cache.gets)
    }

    @Test
    fun `classification cache hit returns cached payload without LLM`() = runTest {
        val llm = StubLlm(classification = ClassificationResponse("Tech Blog", 0.9))
        val cached = json.encodeToString(ClassificationResponse.serializer(), ClassificationResponse("News Article", 0.5))
        val cacheKey = com.sapphire.domain.util.LlmCacheKey.compute("item-1", "classification", "m1")
        val cache = MemCache().with(cacheKey, cached)
        val items = StubItems()
        val useCase = useCase(llm, cache, items)

        val out = useCase.classify("item-1")
        // Cache key uses tier1ModelVersion "m1"; pre-seeded payload wins.
        assertEquals("News Article", (out as LlmOutcome.Ok).value.classification)
        assertEquals(0, llm.classifyCalls)
    }

    @Test
    fun `classification persists result onto item row`() = runTest {
        val llm = StubLlm(classification = ClassificationResponse("Tech Blog", 0.9))
        val items = StubItems()
        val useCase = useCase(llm, MemCache(), items)

        useCase.classify("item-1")
        assertEquals("Tech Blog", items.setClassification["item-1"])
    }

    @Test
    fun `blank classification from model falls back to OTHER`() = runTest {
        val llm = StubLlm(classification = ClassificationResponse("", 0.0))
        val items = StubItems()
        val useCase = useCase(llm, MemCache(), items)

        val out = useCase.classify("item-1")
        assertEquals(ClassificationLabels.OTHER, (out as LlmOutcome.Ok).value.classification)
        assertEquals(ClassificationLabels.OTHER, items.setClassification["item-1"])
    }

    @Test
    fun `classify with supplied paragraphs uses them instead of the item body`() = runTest {
        val llm = StubLlm(classification = ClassificationResponse("Tech Blog", 0.9))
        val items = StubItems()  // default item: bodyRaw = "<p>Body one</p><p>Body two</p>"
        val useCase = useCase(llm, MemCache(), items)

        useCase.classify("item-1", paragraphs = listOf("FIRST EXTRACTED PARA", "SECOND EXTRACTED PARA"))

        assertEquals(1, llm.classifyCalls)
        val captured = llm.userPrompts.single()
        assertTrue("supplied paragraphs should be used", captured.contains("FIRST EXTRACTED PARA") && captured.contains("SECOND EXTRACTED PARA"))
        assertTrue("paragraphs should be joined with blank-line separator", captured.contains("\n\n"))
        assertTrue("feed body should NOT be used", !captured.contains("Body one"))
    }

    @Test
    fun `classify with null paragraphs falls back to the item body`() = runTest {
        val llm = StubLlm(classification = ClassificationResponse("Tech Blog", 0.9))
        val items = StubItems()  // bodyRaw = "<p>Body one</p><p>Body two</p>"
        val useCase = useCase(llm, MemCache(), items)

        useCase.classify("item-1", paragraphs = null)

        assertEquals(1, llm.classifyCalls)
        val captured = llm.userPrompts.single()
        assertTrue("feed body should be used on null", captured.contains("Body one"))
    }

    @Test
    fun `summary cache hit skips LLM`() = runTest {
        val llm = StubLlm(summary = SummaryResponse(listOf("a", "b", "c")))
        val cache = MemCache()
        val useCase = useCase(llm, cache, StubItems())

        val first = useCase.summarize("item-1")
        assertEquals(listOf("a", "b", "c"), (first as LlmOutcome.Ok).value.bullets)
        val second = useCase.summarize("item-1")
        assertEquals(listOf("a", "b", "c"), (second as LlmOutcome.Ok).value.bullets)
        assertEquals(1, llm.summaryCalls)
    }

    @Test
    fun `translate cache hit skips LLM and is keyed per language`() = runTest {
        val llm = StubLlm(translate = TranslateResponse(listOf(TranslatedParagraph("Hi", "你好"))))
        val cache = MemCache()
        val useCase = useCase(llm, cache, StubItems())

        useCase.translate("item-1", "zh")
        useCase.translate("item-1", "zh") // cache hit
        assertEquals(1, llm.translateCalls)

        useCase.translate("item-1", "ja") // different language → miss
        assertEquals(2, llm.translateCalls)
    }

    @Test
    fun `LLM error propagates as typed outcome`() = runTest {
        val llm = StubLlm(error = LlmError.Timeout)
        val useCase = useCase(llm, MemCache(), StubItems())

        val out = useCase.classify("item-1")
        assertTrue(out is LlmOutcome.Err)
        assertEquals(LlmError.Timeout, (out as LlmOutcome.Err).error)
    }

    @Test
    fun `missing item returns Empty error`() = runTest {
        val useCase = useCase(StubLlm(), MemCache(), StubItems(item = null))
        val out = useCase.classify("nope")
        assertTrue(out is LlmOutcome.Err)
        assertTrue((out as LlmOutcome.Err).error is LlmError.Empty)
    }

    @Test
    fun `translate language name maps known locales`() {
        assertEquals("Chinese (Simplified)", ReaderOpsUseCase.translateLanguageName("zh"))
        assertEquals("Chinese (Simplified)", ReaderOpsUseCase.translateLanguageName("zh-CN"))
        assertEquals("Japanese", ReaderOpsUseCase.translateLanguageName("ja"))
        assertEquals("Korean", ReaderOpsUseCase.translateLanguageName("ko"))
    }

    @Test
    fun `translate unknown locale falls back to raw tag`() {
        assertEquals("xx", ReaderOpsUseCase.translateLanguageName("xx"))
    }

    // ---------- helpers ----------

    private fun useCase(llm: StubLlm, cache: ReaderOpCache, items: ReaderItemStore): ReaderOpsUseCase =
        ReaderOpsUseCase(llm, cache, items, json, tier1ModelVersion = "m1", tier2ModelVersion = "m2")

    private fun feedItem(classification: String? = null) = FeedItem(
        hashUuid = "item-1",
        sourceId = "src",
        categoryId = "cat",
        title = "Title",
        summary = "Summary text",
        bodyRaw = "<p>Body one</p><p>Body two</p>",
        authorHandle = null,
        publishedAt = null,
        fetchedAt = 0L,
        platformTag = null,
        mediaUrl = null,
        classification = classification,
    )

    private class StubItems(val item: FeedItem? = FeedItem(hashUuid = "item-1", sourceId = "src", categoryId = "cat", title = "Title", summary = "Summary text", bodyRaw = "<p>Body one</p><p>Body two</p>", authorHandle = null, publishedAt = null, fetchedAt = 0L, platformTag = null, mediaUrl = null, classification = null)) : ReaderItemStore {
        val setClassification = mutableMapOf<String, String>()
        override suspend fun item(itemId: String): FeedItem? = item
        override suspend fun setClassification(itemId: String, classification: String) {
            setClassification[itemId] = classification
        }
    }

    /** In-memory cache keyed by the LlmCacheKey input string (re-derived here for seeding). */
    private class MemCache : ReaderOpCache {
        private val map = mutableMapOf<String, String>()
        var gets = 0; private set
        fun with(key: String, payload: String): MemCache { map[key] = payload; return this }
        override suspend fun get(key: String): String? { gets++; return map[key] }
        override suspend fun put(itemId: String, key: String, op: String, payloadJson: String) {
            map[key] = payloadJson
        }
    }

    private class StubLlm(
        val classification: ClassificationResponse = ClassificationResponse("Other", 0.0),
        val summary: SummaryResponse = SummaryResponse(emptyList()),
        val translate: TranslateResponse = TranslateResponse(emptyList()),
        val error: LlmError? = null,
    ) : LlmClient {
        var classifyCalls = 0; private set
        var summaryCalls = 0; private set
        var translateCalls = 0; private set
        val userPrompts = mutableListOf<String>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> completeStructured(
            tier: LlmTier,
            systemPrompt: String,
            userPrompt: String,
            outputSerializer: kotlinx.serialization.KSerializer<T>,
        ): LlmOutcome<T> {
            userPrompts.add(userPrompt)
            if (error != null) return LlmOutcome.Err(error)
            val raw: Any = when (outputSerializer) {
                ClassificationResponse.serializer() -> { classifyCalls++; classification }
                SummaryResponse.serializer() -> { summaryCalls++; summary }
                TranslateResponse.serializer() -> { translateCalls++; translate }
                else -> error("unexpected serializer")
            }
            return LlmOutcome.Ok(raw as T)
        }
    }
}
