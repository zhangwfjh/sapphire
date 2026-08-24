package com.sapphire.domain.reader

import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.SummaryStreamFrame
import com.sapphire.domain.llm.TranslateRegions
import com.sapphire.domain.llm.TranslateStreamFrame
import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.settings.TranslateViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * One **reader session** (see CONTEXT.md): the lifetime of a single article open — body
 * resolution (cached → agent → extract → feed-only), then classification, then any
 * auto-fired ops, then tap-driven summary/translate. This module owns the sequencing and
 * gating policy; the ViewModel reduces [ReaderEvent]s to UI state.
 *
 * Stateless: every method is a cold flow; callers supply [Content] and the current
 * [translate] summary bullets. The reducer re-fires translate when a late-settling
 * summary adds bullets (it knows whether translate is visible).
 *
 * Policy contracts (each is a named test in ReaderSessionTest):
 * - Cached extraction is reused; agent items use feed blocks as the article; URL-less
 *   items open feed-only (no auto-summarize, but classify + maybe translate); extraction
 *   failure degrades to the feed body and still classifies/summarizes on it.
 * - Auto-summarize fires only above [SUMMARY_MIN_WORDS] words of resolved article blocks.
 * - Auto-translate fires unless the view mode is ORIGIN, and only after the scripted
 *   summary (if any) has fully settled — its bullets are in the translate regions.
 * - Translate is skipped (originals surfaced, no translate block) when the article already
 *   reads in the target language — Simplified-Chinese detection today.
 * - Feed images missing from the extracted article are merged in front of it.
 *
 * All outcomes are typed; no exceptions cross this interface. Cancellation (closing the
 * reader mid-extraction) simply ends the flow — nothing is published after.
 */
class ReaderSession @Inject constructor(
    private val items: ReaderItemStore,
    private val ops: ReaderOpsUseCase,
    private val richContentParser: RichContentParser,
    private val articleExtractor: ArticleExtractor,
    private val articleBodyStore: ArticleBodyStore,
) {

    /** What happened in the session; the ViewModel maps these to UI states 1:1. */
    sealed interface ReaderEvent {
        /** The item opened with resolved blocks. Emitted per ladder rung (e.g. EXTRACTING → DONE). */
        data class Opened(
            val item: FeedItem,
            val blocks: List<RichBlock>,
            val articleBlocks: List<RichBlock>?,
            val extraction: ExtractionState,
        ) : ReaderEvent

        data class ClassificationError(val message: String) : ReaderEvent
        data class ClassificationDone(val label: String) : ReaderEvent

        data object SummaryLoading : ReaderEvent
        data class SummaryStreaming(val frame: SummaryStreamFrame) : ReaderEvent
        data class SummaryDone(val frame: SummaryStreamFrame) : ReaderEvent
        data class SummaryError(val message: String) : ReaderEvent

        data object TranslateLoading : ReaderEvent
        data class TranslateStreaming(val frame: TranslateStreamFrame) : ReaderEvent
        data class TranslateDone(val frame: TranslateStreamFrame) : ReaderEvent
        data class TranslateError(val message: String) : ReaderEvent

        /** Target-language article: originals are surfaced, no translate block. */
        data object TranslateSkipped : ReaderEvent

        data class NotFound(val message: String) : ReaderEvent
    }

    enum class ExtractionState { IDLE, EXTRACTING, DONE, FAILED }

    /** Resolved session content — what a reducer assembles from [ReaderEvent.Opened]. */
    data class Content(
        val item: FeedItem,
        val blocks: List<RichBlock>,
        val articleBlocks: List<RichBlock>?,
    ) {
        fun articleParagraphs(): List<String> = (articleBlocks ?: blocks).toPlainParagraphs()
    }

    /**
     * Opens an item: resolves the body per the cache → agent → url → feed-only ladder,
     * classifies, and auto-fires summary/translate per policy. Cold flow; cancelling it
     * (reader closed) abandons the session without side effects beyond the body cache.
     */
    fun open(
        itemId: String,
        targetLanguage: String,
        translateViewMode: TranslateViewMode,
    ): Flow<ReaderEvent> = flow {
        val item = items.item(itemId)
            ?: run {
                emit(ReaderEvent.NotFound("Item not found."))
                return@flow
            }

        val feedBlocks = richContentParser.parse(item.bodyRaw ?: item.summary ?: item.title)

        val cachedHtml = articleBodyStore.get(itemId)
        val content: Content
        if (cachedHtml != null) {
            content = Content(item, feedBlocks, mergeFeedImages(richContentParser.parse(cachedHtml), feedBlocks))
            emitOpened(content, ExtractionState.DONE)
        } else if (item.agentTag != null) {
            // Agent items: the synthesized body IS the full article. Skip extraction (the
            // item URL is often agent://... or a source link, not the article itself).
            content = Content(item, feedBlocks, feedBlocks)
            emitOpened(content, ExtractionState.DONE)
        } else {
            val url = item.url
            if (url.isNullOrBlank()) {
                // No article body to extract or summarize; classify + maybe translate the brief.
                content = Content(item, feedBlocks, null)
                emitOpened(content, ExtractionState.IDLE)
                emitClassification(content)
                maybeAutoTranslate(content, emptyList(), targetLanguage, translateViewMode)
                return@flow
            }
            emitOpened(Content(item, feedBlocks, null), ExtractionState.EXTRACTING)
            val resolved: Content = when (val outcome = articleExtractor.extract(url)) {
                is ExtractionOutcome.Ok -> {
                    articleBodyStore.put(itemId, outcome.html)
                    Content(item, feedBlocks, mergeFeedImages(richContentParser.parse(outcome.html), feedBlocks))
                        .also { emitOpened(it, ExtractionState.DONE) }
                }
                is ExtractionOutcome.Err ->
                    Content(item, feedBlocks, null).also { emitOpened(it, ExtractionState.FAILED) }
            }
            content = resolved
        }

        emitClassification(content)
        val summaryBullets = autoSummarizeIfLongEnough(content)
        maybeAutoTranslate(content, summaryBullets, targetLanguage, translateViewMode)
    }

    /**
     * Tap-driven summary. Emits Loading → Streaming* → Done|Error. A Done after a visible
     * translate warrants the reducer re-firing [translate] with the new bullets.
     */
    fun summarize(content: Content): Flow<ReaderEvent> = flow {
        emit(ReaderEvent.SummaryLoading)
        collectOp(
            ops.summarizeStreaming(content.item.hashUuid, content.articleParagraphs()),
            onOk = { emit(ReaderEvent.SummaryStreaming(it)) },
            lastOk = { ReaderEvent.SummaryDone(it) },
            onErr = { ReaderEvent.SummaryError(it) },
        )
    }

    /**
     * Tap-driven translate over the resolved regions plus the reducer's current summary
     * bullets. Emits Loading → Streaming* → Done|Error, or [ReaderEvent.TranslateSkipped]
     * when the article already reads in [targetLanguage].
     */
    fun translate(
        content: Content,
        summaryBullets: List<String>,
        targetLanguage: String,
    ): Flow<ReaderEvent> = flow {
        val regions = TranslateRegions(
            title = listOfNotNull(content.item.title.takeIf { it.isNotBlank() }),
            summary = summaryBullets,
            brief = content.blocks.toPlainParagraphs(),
            article = content.articleBlocks?.toPlainParagraphs() ?: emptyList(),
        )
        val sourceText = buildString {
            regions.title.forEach { append(it); append(' ') }
            regions.brief.forEach { append(it); append(' ') }
            regions.article.forEach { append(it); append(' ') }
        }
        if (SimplifiedChineseDetector.shouldSkipTranslate(sourceText, targetLanguage)) {
            emit(ReaderEvent.TranslateSkipped)
            return@flow
        }
        emit(ReaderEvent.TranslateLoading)
        collectOp(
            ops.translateStreaming(content.item.hashUuid, targetLanguage, regions),
            onOk = { emit(ReaderEvent.TranslateStreaming(it)) },
            lastOk = { ReaderEvent.TranslateDone(it) },
            onErr = { ReaderEvent.TranslateError(it) },
        )
    }

    // ---- internals ----

    private suspend fun FlowCollector<ReaderEvent>.emitClassification(content: Content) {
        when (val outcome = ops.classify(content.item.hashUuid, content.articleParagraphs())) {
            is LlmOutcome.Err -> emit(ReaderEvent.ClassificationError(outcome.error.userMessage()))
            is LlmOutcome.Ok -> emit(ReaderEvent.ClassificationDone(outcome.value.classification))
        }
    }

    /**
     * Auto-summarize when the resolved article exceeds [SUMMARY_MIN_WORDS] words. The
     * summary settles fully inline (streamed to completion) so the subsequent
     * [maybeAutoTranslate] sees final bullets — the settle contract.
     * @return the final bullets, or empty when summary did not fire.
     */
    private suspend fun FlowCollector<ReaderEvent>.autoSummarizeIfLongEnough(content: Content): List<String> {
        val article = content.articleBlocks ?: return emptyList()
        val wordCount = article.toPlainParagraphs()
            .sumOf { p -> p.split(WS_REGEX).count { it.isNotBlank() } }
        if (wordCount <= SUMMARY_MIN_WORDS) return emptyList()
        emit(ReaderEvent.SummaryLoading)
        var bullets = emptyList<String>()
        collectOp(
            ops.summarizeStreaming(content.item.hashUuid, content.articleParagraphs()),
            onOk = { frame -> bullets = frame.bullets; emit(ReaderEvent.SummaryStreaming(frame)) },
            lastOk = {
                bullets = it.bullets
                ReaderEvent.SummaryDone(it)
            },
            onErr = { ReaderEvent.SummaryError(it) },
        )
        return bullets
    }

    private suspend fun FlowCollector<ReaderEvent>.maybeAutoTranslate(
        content: Content,
        summaryBullets: List<String>,
        targetLanguage: String,
        translateViewMode: TranslateViewMode,
    ) {
        if (translateViewMode == TranslateViewMode.ORIGIN) return
        collectAll(translate(content, summaryBullets, targetLanguage))
    }

    private suspend fun FlowCollector<ReaderEvent>.emitOpened(content: Content, extraction: ExtractionState) {
        emit(
            ReaderEvent.Opened(
                item = content.item,
                blocks = content.blocks,
                articleBlocks = content.articleBlocks,
                extraction = extraction,
            ),
        )
    }

    /** Streams an LLM op flow into events, emitting a terminal Done when it ended cleanly. */
    private suspend fun <T> FlowCollector<ReaderEvent>.collectOp(
        source: Flow<LlmOutcome<T>>,
        onOk: suspend (T) -> Unit,
        lastOk: (T) -> ReaderEvent,
        onErr: (String) -> ReaderEvent,
    ) {
        var errored = false
        var last: T? = null
        var lastSet = false
        source.collect { outcome ->
            when (outcome) {
                is LlmOutcome.Err -> {
                    errored = true
                    emit(onErr(outcome.error.userMessage()))
                }
                is LlmOutcome.Ok -> {
                    last = outcome.value
                    lastSet = true
                    onOk(outcome.value)
                }
            }
        }
        if (!errored && lastSet) emit(lastOk(last as T))
    }

    private suspend fun FlowCollector<ReaderEvent>.collectAll(source: Flow<ReaderEvent>) {
        source.collect { emit(it) }
    }

    private fun mergeFeedImages(extracted: List<RichBlock>, feedBlocks: List<RichBlock>): List<RichBlock> {
        val present = extracted.mapNotNull { (it as? RichBlock.Image)?.url?.takeIf(String::isNotBlank) }.toHashSet()
        val missing = feedBlocks.filterIsInstance<RichBlock.Image>()
            .filter { it.url.isNotBlank() && it.url !in present }
        return if (missing.isEmpty()) extracted else missing + extracted
    }

    private companion object {
        const val SUMMARY_MIN_WORDS = 300
        val WS_REGEX = Regex("\\s+")
    }
}
