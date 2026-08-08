package com.sapphire.domain.reader

import com.sapphire.domain.llm.ClassificationResponse
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.llm.SummaryResponse
import com.sapphire.domain.llm.TranslateRegions
import com.sapphire.domain.llm.TranslateResponse
import com.sapphire.domain.model.FeedItem
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderOpsUseCase] — the lazy-compute + idempotent-cache contract.
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
    fun `summary streams bullets, writes cache, then replays from cache without streaming`() = runTest {
        val llm = StubLlm(summary = SummaryResponse(listOf("First bullet.", "Second bullet.", "Third bullet.")))
        val cache = MemCache()
        val useCase = useCase(llm, cache, StubItems())

        val frames = useCase.summarizeStreaming("item-1").toList()
        assertTrue("every frame is Ok", frames.all { it is LlmOutcome.Ok })
        val final = (frames.last() as LlmOutcome.Ok).value
        assertEquals(listOf("First bullet.", "Second bullet.", "Third bullet."), final.bullets)
        assertEquals("", final.partial)
        assertEquals(1, llm.summaryStreamCalls)

        // Re-call: cache hit emits a single complete frame and does not stream.
        val replayed = useCase.summarizeStreaming("item-1").toList()
        assertEquals(1, replayed.size)
        val replayedFinal = (replayed.single() as LlmOutcome.Ok).value
        assertEquals(listOf("First bullet.", "Second bullet.", "Third bullet."), replayedFinal.bullets)
        assertEquals(1, llm.summaryStreamCalls)
    }

    @Test
    fun `streamed bullets are stripped of list decoration before caching`() = runTest {
        val llm = StubLlm(summaryText = "1. One\n• Two\n- Three")
        val cache = MemCache()
        val useCase = useCase(llm, cache, StubItems())

        val final = (useCase.summarizeStreaming("item-1").toList().last() as LlmOutcome.Ok).value
        assertEquals(listOf("One", "Two", "Three"), final.bullets)
    }

    @Test
    fun `translate streams regions, writes cache, then replays from cache keyed per language`() = runTest {
        val llm = StubLlm(translateText = "t1|||s1|||b1|||a1")
        val cache = MemCache()
        val useCase = useCase(llm, cache, StubItems())
        val regions = TranslateRegions(title = listOf("T1"), summary = listOf("S1"), brief = listOf("B1"), article = listOf("A1"))

        val frames = useCase.translateStreaming("item-1", "zh", regions).toList()
        assertTrue("every frame is Ok", frames.all { it is LlmOutcome.Ok })
        val final = (frames.last() as LlmOutcome.Ok).value
        assertEquals("t1", final.title)
        assertEquals(listOf("s1"), final.summary)
        assertEquals(listOf("b1"), final.brief)
        assertEquals(listOf("a1"), final.article)
        assertEquals(1, llm.translateStreamCalls)

        // Re-call same language: cache hit emits a single complete frame without streaming.
        val replayed = useCase.translateStreaming("item-1", "zh", regions).toList()
        assertEquals(1, replayed.size)
        val replayedFinal = (replayed.single() as LlmOutcome.Ok).value
        assertEquals("t1", replayedFinal.title)
        assertEquals(listOf("b1"), replayedFinal.brief)
        assertEquals(1, llm.translateStreamCalls)

        // Different language → miss → streams again.
        useCase.translateStreaming("item-1", "ja", regions).toList()
        assertEquals(2, llm.translateStreamCalls)
    }

    @Test
    fun `streamed translate without a delimiter is a single in-progress region`() = runTest {
        val llm = StubLlm(translateText = "uno\ndos") // no delimiter yet → one segment mid-stream
        val cache = MemCache()
        val useCase = useCase(llm, cache, StubItems())
        val regions = TranslateRegions(brief = listOf("only"))

        val final = (useCase.translateStreaming("item-1", "es", regions).toList().last() as LlmOutcome.Ok).value
        assertEquals(listOf("uno\ndos"), final.brief)
    }

    @Test
    fun `translate without pipe delimiter recovers paragraph alignment from blank lines`() = runTest {
        // Models routinely ignore the obscure `|||` sentinel on longer articles and emit
        // blank-line-separated paragraphs instead. Without recovery the whole blob collapses
        // into the title slot ("all paragraphs just below the title").
        val llm = StubLlm(translateText = "t1\n\nb1\n\nb2\n\na1")
        val cache = MemCache()
        val useCase = useCase(llm, cache, StubItems())
        val regions = TranslateRegions(title = listOf("T1"), brief = listOf("B1", "B2"), article = listOf("A1"))

        val final = (useCase.translateStreaming("item-1", "zh", regions).toList().last() as LlmOutcome.Ok).value
        assertEquals("t1", final.title)
        assertEquals(listOf("b1", "b2"), final.brief)
        assertEquals(listOf("a1"), final.article)
    }

    @Test
    fun `translate without pipe or blank-line recovers from single newlines`() = runTest {
        val llm = StubLlm(translateText = "b1\nb2\nb3")
        val cache = MemCache()
        val useCase = useCase(llm, cache, StubItems())
        val regions = TranslateRegions(brief = listOf("B1", "B2", "B3"))

        val final = (useCase.translateStreaming("item-1", "zh", regions).toList().last() as LlmOutcome.Ok).value
        assertEquals(listOf("b1", "b2", "b3"), final.brief)
    }

    @Test
    fun `clean pipe-delimited stream is never re-split by newlines`() = runTest {
        // A `|||` stream whose individual translations contain newlines must not be over-split.
        val llm = StubLlm(translateText = "t1|||b1 line1\nb1 line2|||a1")
        val cache = MemCache()
        val useCase = useCase(llm, cache, StubItems())
        val regions = TranslateRegions(title = listOf("T1"), brief = listOf("B1"), article = listOf("A1"))

        val final = (useCase.translateStreaming("item-1", "zh", regions).toList().last() as LlmOutcome.Ok).value
        assertEquals("t1", final.title)
        assertEquals(listOf("b1 line1\nb1 line2"), final.brief)
        assertEquals(listOf("a1"), final.article)
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
        val summaryText: String? = null,
        val translate: TranslateResponse = TranslateResponse(),
        val translateText: String? = null,
        val error: LlmError? = null,
    ) : LlmClient {
        var classifyCalls = 0; private set
        var summaryStreamCalls = 0; private set
        var translateStreamCalls = 0; private set
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
                TranslateResponse.serializer() -> { translate }
                else -> error("unexpected serializer")
            }
            return LlmOutcome.Ok(raw as T)
        }

        /**
         * Simulates a streamed completion. Routes by system prompt: a translate stream
         * (prompt contains "translator") emits the configured translate text; otherwise the
         * summary text. Each emits a mid-point partial then the full text, like the real
         * provider's token deltas.
         */
        override fun streamText(
            tier: LlmTier,
            systemPrompt: String,
            userPrompt: String,
        ): kotlinx.coroutines.flow.Flow<LlmOutcome<String>> = flow {
            userPrompts.add(userPrompt)
            if (error != null) { emit(LlmOutcome.Err(error)); return@flow }
            val isTranslate = systemPrompt.contains("translator")
            if (isTranslate) translateStreamCalls++ else summaryStreamCalls++
            val full = if (isTranslate) {
                translateText ?: listOf(translate.title, *translate.summary.toTypedArray(), *translate.brief.toTypedArray(), *translate.article.toTypedArray())
                    .filter { it.isNotEmpty() }
                    .joinToString("\n${TranslateResponse.STREAM_DELIMITER}\n")
            } else {
                summaryText ?: summary.bullets.joinToString("\n")
            }
            if (full.isEmpty()) return@flow
            val mid = full.length / 2
            if (mid > 0) emit(LlmOutcome.Ok(full.substring(0, mid)))
            emit(LlmOutcome.Ok(full))
        }

        override suspend fun completeWithTools(
            tier: LlmTier,
            systemPrompt: String,
            conversation: List<com.sapphire.domain.llm.ToolMessage>,
            tools: List<com.sapphire.domain.llm.ToolDefinition>,
        ): LlmOutcome<com.sapphire.domain.llm.ToolTurn> =
            throw NotImplementedError("not used by reader ops")
    }
}
