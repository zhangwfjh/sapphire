package com.sapphire.domain.reader

import com.sapphire.domain.llm.ClassificationResponse
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.llm.SummaryResponse
import com.sapphire.domain.llm.TranslateResponse
import com.sapphire.domain.llm.TranslateRegions
import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.settings.TranslateViewMode
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderSession] — the open ladder and auto-op gating policy, as named contracts.
 * House fakes: StubLlm / StubItems / MemCache mirror ReaderOpsUseCaseTest; extractor /
 * body-store / parser fakes cover the session's own ports.
 *
 * Contracts:
 * - Ladder: cached extraction → agent item → extract → URL-less feed-only; extraction
 *   failure degrades to feed blocks and still classifies.
 * - Auto-summarize fires only above 300 words; auto-translate only after the scripted
 *   summary settled (its bullets in the translate regions) and never in ORIGIN mode.
 * - zh-source articles skip translate (originals surfaced).
 * - Feed images missing from the extracted article are merged in front.
 * - Late extraction result does not double-emit EXTRACTING content.
 */
class ReaderSessionTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- ladder ----

    @Test
    fun `cached extraction is reused without extractor`() = runTest {
        val extractor = RecordingExtractor(ExtractionOutcome.Ok(title = "T", html = "<p>fresh</p>", byline = null))
        val session = session(
            items = StubItems(item = feedItem(url = "https://a")),
            bodyStore = MemBodyStore(cached = "<p>cached</p>"),
            extractor = extractor,
        )

        val events = session.open("item-1", "zh", TranslateViewMode.ORIGIN).toList()

        assertEquals(0, extractor.calls)
        val opened = events.filterIsInstance<ReaderSession.ReaderEvent.Opened>()
        assertEquals(1, opened.size)
        assertEquals(ReaderSession.ExtractionState.DONE, opened[0].extraction)
    }

    @Test
    fun `agent item uses feed blocks as the article without extracting`() = runTest {
        val extractor = RecordingExtractor(ExtractionOutcome.Ok(title = "T", html = "<p>x</p>", byline = null))
        val session = session(
            items = StubItems(item = feedItem(agentTag = "Bot", url = "https://src")),
            bodyStore = MemBodyStore(),
            extractor = extractor,
        )

        val events = session.open("item-1", "zh", TranslateViewMode.ORIGIN).toList()

        assertEquals(0, extractor.calls)
        val opened = events.filterIsInstance<ReaderSession.ReaderEvent.Opened>().single()
        assertEquals(opened.blocks, opened.articleBlocks)
        assertEquals(ReaderSession.ExtractionState.DONE, opened.extraction)
    }

    @Test
    fun `url-less item opens feed-only with no auto-summary`() = runTest {
        val llm = StubLlm()
        val session = session(items = StubItems(item = feedItem(url = null)), llm = llm)

        val events = session.open("item-1", "zh", TranslateViewMode.BILINGUAL).toList()

        val opened = events.filterIsInstance<ReaderSession.ReaderEvent.Opened>().single()
        assertEquals(ReaderSession.ExtractionState.IDLE, opened.extraction)
        assertEquals(null, opened.articleBlocks)
        assertTrue(events.any { it is ReaderSession.ReaderEvent.ClassificationDone })
        assertTrue(events.any { it is ReaderSession.ReaderEvent.TranslateLoading })
        assertEquals(0, llm.summaryStreamCalls) // nothing summarized
        // ^ the single stream call is translate, not summary
    }

    @Test
    fun `extraction succeeds emits EXTRACTING then DONE and caches body`() = runTest {
        val store = MemBodyStore()
        val session = session(
            items = StubItems(item = feedItem(url = "https://a")),
            bodyStore = store,
            extractor = RecordingExtractor(ExtractionOutcome.Ok(title = "T", html = "<p>full article</p>", byline = null)),
        )

        val events = session.open("item-1", "zh", TranslateViewMode.ORIGIN).toList()

        val states = events.filterIsInstance<ReaderSession.ReaderEvent.Opened>().map { it.extraction }
        assertEquals(listOf(ReaderSession.ExtractionState.EXTRACTING, ReaderSession.ExtractionState.DONE), states)
        assertEquals("<p>full article</p>", store.stored["item-1"])
    }

    @Test
    fun `extraction failure degrades to feed blocks and still classifies`() = runTest {
        val session = session(
            items = StubItems(item = feedItem(url = "https://a")),
            extractor = RecordingExtractor(ExtractionOutcome.Err.Blocked),
        )

        val events = session.open("item-1", "zh", TranslateViewMode.ORIGIN).toList()

        val opened = events.filterIsInstance<ReaderSession.ReaderEvent.Opened>().map { it.extraction }
        assertEquals(
            listOf(ReaderSession.ExtractionState.EXTRACTING, ReaderSession.ExtractionState.FAILED),
            opened,
        )
        assertTrue(events.any { it is ReaderSession.ReaderEvent.ClassificationDone })
        // articleBlocks is null → no auto-summarize even though classify ran.
        assertTrue(events.none { it is ReaderSession.ReaderEvent.SummaryLoading })
    }

    @Test
    fun `missing item emits NotFound`() = runTest {
        val session = session(items = StubItems(item = null))

        val events = session.open("nope", "zh", TranslateViewMode.ORIGIN).toList()

        assertTrue(events.single() is ReaderSession.ReaderEvent.NotFound)
    }

    // ---- auto-summarize threshold ----

    @Test
    fun `long article auto-summarizes then auto-translates with settled bullets`() = runTest {
        val longBody = "<p>${(1..400).joinToString(" ") { "word$it" }}</p>"
        val llm = StubLlm(
            classification = ClassificationResponse("Tech", 0.9),
            summaryText = "One.\nTwo.",
            translateText = "t|||One.\nTwo.|||body",
        )
        val session = session(
            items = StubItems(item = feedItem(url = null, bodyRaw = longBody, agentTag = "Bot")),
            llm = llm,
        )

        val events = session.open("item-1", "zh", TranslateViewMode.BILINGUAL).toList()

        // Settle contract: summary fully done BEFORE translate starts.
        val summaryDoneIdx = events.indexOfFirst { it is ReaderSession.ReaderEvent.SummaryDone }
        val translateLoadingIdx = events.indexOfFirst { it is ReaderSession.ReaderEvent.TranslateLoading }
        assertTrue("summary must settle before translate", summaryDoneIdx in 0 until translateLoadingIdx)
        // Translate regions include the settled bullets.
        val captured = llm.translatePrompts.single()
        assertTrue("translate prompt should include summary bullets", captured.contains("One.") && captured.contains("Two."))
    }

    @Test
    fun `short article skips auto-summarize but still translates`() = runTest {
        val session = session(items = StubItems(item = feedItem(agentTag = "Bot", url = null)))

        val events = session.open("item-1", "zh", TranslateViewMode.BILINGUAL).toList()

        assertTrue(events.none { it is ReaderSession.ReaderEvent.SummaryLoading })
        assertTrue(events.any { it is ReaderSession.ReaderEvent.TranslateLoading })
    }

    // ---- translate gating ----

    @Test
    fun `ORIGIN mode skips auto-translate`() = runTest {
        val session = session(items = StubItems(item = feedItem(agentTag = "Bot", url = null)))

        val events = session.open("item-1", "zh", TranslateViewMode.ORIGIN).toList()

        assertTrue(events.none { it is ReaderSession.ReaderEvent.TranslateLoading })
    }

    @Test
    fun `zh source article skips translate entirely`() = runTest {
        // SimplifiedChineseDetector.shouldSkipTranslate fires on Chinese text with zh target.
        val zhBody = "<p>这是一篇简体中文文章,内容足够长以供检测。</p>"
        val session = session(items = StubItems(item = feedItem(agentTag = "Bot", url = null, bodyRaw = zhBody)))

        val events = session.open("item-1", "zh", TranslateViewMode.BILINGUAL).toList()

        assertTrue(events.any { it is ReaderSession.ReaderEvent.TranslateSkipped })
        assertTrue(events.none { it is ReaderSession.ReaderEvent.TranslateLoading })
    }

    // ---- image merge ----

    @Test
    fun `feed image missing from extraction is merged in front`() = runTest {
        val feedBody = "<p>text</p><img src=\"https://img/1\" alt=\"pic\">"
        val extractedHtml = "<p>text only</p>" // no image
        val session = session(
            items = StubItems(item = feedItem(url = "https://a", bodyRaw = feedBody)),
            bodyStore = MemBodyStore(),
            extractor = RecordingExtractor(ExtractionOutcome.Ok(title = null, html = extractedHtml, byline = null)),
        )

        val events = session.open("item-1", "zh", TranslateViewMode.ORIGIN).toList()

        val done = events.filterIsInstance<ReaderSession.ReaderEvent.Opened>()
            .last { it.extraction == ReaderSession.ExtractionState.DONE }
        val first = done.articleBlocks!!.first()
        assertTrue("feed image should be merged first, got $first", first is RichBlock.Image)
        assertEquals("https://img/1", (first as RichBlock.Image).url)
    }

    // ---- fixtures ----

    private fun session(
        items: ReaderItemStore = StubItems(),
        llm: StubLlm = StubLlm(),
        bodyStore: ArticleBodyStore = MemBodyStore(),
        extractor: ArticleExtractor = RecordingExtractor(ExtractionOutcome.Err.Unreachable),
    ): ReaderSession = ReaderSession(
        items = items,
        ops = ReaderOpsUseCase(llm, MemCache(), items, json, tier1ModelVersion = "m1", tier2ModelVersion = "m2"),
        richContentParser = PlainTextParser,
        articleExtractor = extractor,
        articleBodyStore = bodyStore,
    )

    /** Parses `<p>…</p>` and `<img src=…>` minimally — enough for block-level assertions. */
    private object PlainTextParser : RichContentParser {
        override fun parse(bodyRaw: String?): List<RichBlock> {
            if (bodyRaw.isNullOrBlank()) return emptyList()
            return Regex("<p>(.*?)</p>|<img src=\"([^\"]+)\"[^>]*>")
                .findAll(bodyRaw)
                .map { m ->
                    if (m.groupValues[1].isNotEmpty()) {
                        RichBlock.Paragraph(listOf(RichSpan.Text(m.groupValues[1]))) as RichBlock
                    } else {
                        RichBlock.Image(url = m.groupValues[2], alt = null, caption = null)
                    }
                }
                .toList()
        }
    }

    private fun feedItem(
        url: String? = "https://a",
        bodyRaw: String? = "<p>Body one</p><p>Body two</p>",
        agentTag: String? = null,
    ) = FeedItem(
        hashUuid = "item-1",
        sourceId = "src",
        categoryId = "cat",
        title = "Title",
        summary = "Summary text",
        bodyRaw = bodyRaw,
        authorHandle = null,
        publishedAt = null,
        fetchedAt = 0L,
        platformTag = null,
        mediaUrl = null,
        agentTag = agentTag,
        url = url,
    )

    private class RecordingExtractor(private val outcome: ExtractionOutcome) : ArticleExtractor {
        var calls = 0
        override suspend fun extract(url: String): ExtractionOutcome {
            calls++
            return outcome
        }
    }

    private class MemBodyStore(private val cached: String? = null) : ArticleBodyStore {
        val stored = mutableMapOf<String, String>()
        override suspend fun get(itemId: String): String? = cached
        override suspend fun put(itemId: String, html: String) { stored[itemId] = html }
    }

    private class StubItems(val item: FeedItem? = null) : ReaderItemStore {
        override suspend fun item(itemId: String): FeedItem? = item
        override suspend fun setClassification(itemId: String, classification: String) {}
    }

    private class MemCache : ReaderOpCache {
        private val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override suspend fun put(itemId: String, key: String, op: String, payloadJson: String) {
            map[key] = payloadJson
        }
    }

    /** Same shape as ReaderOpsUseCaseTest's StubLlm, plus translate prompt capture. */
    private class StubLlm(
        val classification: ClassificationResponse = ClassificationResponse("Other", 0.0),
        val summaryText: String? = null,
        val translateText: String? = null,
        val error: LlmError? = null,
    ) : LlmClient {
        var classifyCalls = 0; private set
        var summaryStreamCalls = 0; private set
        var translateStreamCalls = 0; private set
        val translatePrompts = mutableListOf<String>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> completeStructured(
            tier: LlmTier,
            systemPrompt: String,
            userPrompt: String,
            outputSerializer: KSerializer<T>,
        ): LlmOutcome<T> {
            if (error != null) return LlmOutcome.Err(error)
            val raw: Any = when (outputSerializer) {
                ClassificationResponse.serializer() -> { classifyCalls++; classification }
                else -> error("unexpected serializer")
            }
            return LlmOutcome.Ok(raw as T)
        }

        override fun streamText(
            tier: LlmTier,
            systemPrompt: String,
            userPrompt: String,
        ): kotlinx.coroutines.flow.Flow<LlmOutcome<String>> = flow {
            if (error != null) { emit(LlmOutcome.Err(error)); return@flow }
            val isTranslate = systemPrompt.contains("translator")
            if (isTranslate) translateStreamCalls++ else summaryStreamCalls++
            if (isTranslate) translatePrompts.add(userPrompt)
            val full = if (isTranslate) {
                translateText ?: ""
            } else {
                summaryText ?: ""
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
