package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.reader.ReaderItemStore
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.TranslateResponse
import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.reader.RichBlock
import com.sapphire.domain.reader.RichContentParser
import com.sapphire.domain.reader.toPlainParagraphs
import com.sapphire.domain.reader.ReaderMacro
import com.sapphire.domain.reader.ReaderOpsUseCase
import com.sapphire.domain.save.SavedItemRepository
import com.sapphire.domain.reader.ArticleBodyStore
import com.sapphire.domain.reader.ArticleExtractor
import com.sapphire.domain.reader.ExtractionOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * S03 reader-sheet state (PRD §3.4 / §3.5, architecture §9).
 *
 * Lazy-compute lifecycle:
 * - [open] resolves the article body first: a cached extraction is reused; otherwise the
 *   full article is fetched + extracted on demand (and cached); on any failure the feed
 *   body is used. Only then does it kick Tier-1 classification. While classification runs
 *   the macro slot shows shimmer (PRD §3.5); the chat input is interactive immediately.
 * - [summarize] / [translate] fire Tier-2 on tap. Results are cached by the use case, so
 *   a re-open or re-tap is a free cache hit (PRD §4.2 idempotent).
 *
 * The macros set is derived from the classification via [ReaderMacro.forClassification].
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val items: ReaderItemStore,
    private val readerOps: ReaderOpsUseCase,
    private val savedItems: SavedItemRepository,
    private val richContentParser: RichContentParser,
    private val articleExtractor: ArticleExtractor,
    private val articleBodyStore: ArticleBodyStore,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Idle)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** Default translate target — resolved from the device locale by the caller. */
    private var targetLanguage: String = "zh"

    fun open(itemId: String, targetLanguage: String = this.targetLanguage) {
        this.targetLanguage = targetLanguage
        _state.value = ReaderUiState.Loading
        viewModelScope.launch {
            val item = items.item(itemId)
        if (item == null) {
            _state.value = ReaderUiState.Error("Item not found.")
            return@launch
        }

            // The reader shows TWO body regions (PRD §3.4):
            //  1. the brief — the feed body, always visible; and
            //  2. the full article — the extracted readable body, appended below the brief
            //     behind a divider once extraction lands (omitted when there is no URL or
            //     extraction fails). `blocks` is always the brief; `articleBlocks` carries
            //     the extracted body and is null until it resolves.
            //
            // LLM ops (classify/summarize/translate) run on the full article when available
            // and fall back to the brief — see [currentParagraphs]. The paragraph-aligned
            // translate contract (block i <-> translate paragraph i) holds because the op
            // and its rendering always use the same block list.
            //
            // Readability drops images the feed body carried (lead/hero imgs outside the
            // scored region, lazy-loaded imgs with blank `src`). To keep them visible, feed
            // images are merged back into the extracted article as a dedup union: any feed
            // image whose URL is not already in the extracted blocks is prepended (lead
            // first). Images are non-text blocks, so they never consume a translate slot.
            val feedBlocks = richContentParser.parse(item.bodyRaw ?: item.summary ?: item.title)

            val cachedHtml = articleBodyStore.get(itemId)
            if (cachedHtml != null) {
                val cachedArticle = mergeFeedImages(richContentParser.parse(cachedHtml), feedBlocks)
                publish(item, feedBlocks, cachedArticle, ExtractionState.Done)
                classify(itemId)
                autoSummarizeIfLongEnough(cachedArticle)
                return@launch
            }

            val url = item.url
            if (url.isNullOrBlank()) {
                publish(item, feedBlocks, null, ExtractionState.Idle)
                classify(itemId)
                return@launch
            }

            // Show the brief while the full article is fetched/extracted. Classification is
            // deferred until the resolved article lands below.
            publish(item, feedBlocks, null, ExtractionState.Extracting)
            val (article, extraction) = when (val outcome = articleExtractor.extract(url)) {
                is ExtractionOutcome.Ok -> {
                    articleBodyStore.put(itemId, outcome.html)
                    mergeFeedImages(richContentParser.parse(outcome.html), feedBlocks) to ExtractionState.Done
                }
                is ExtractionOutcome.Err -> null to ExtractionState.Failed
            }
            // Late write: only if the user hasn't dismissed or opened a different item.
            if ((_state.value as? ReaderUiState.Open)?.item?.hashUuid == itemId) {
                publish(item, feedBlocks, article, extraction)
                classify(itemId)
                autoSummarizeIfLongEnough(article)
            }
        }
    }

    /**
     * Emits the resolved [ReaderUiState.Open] state (does not kick classification).
     *
     * A re-publish for the same item (e.g. the full article landing after the brief was
     * already shown) preserves the classification/macros/summary the user already has so
     * the slot doesn't flicker; translate is reset because its paragraph alignment is tied
     * to whichever body (brief vs full article) was translated and would break across the
     * transition.
     */
    private fun publish(
        item: com.sapphire.domain.model.FeedItem,
        blocks: List<RichBlock>,
        articleBlocks: List<RichBlock>?,
        extraction: ExtractionState,
    ) {
        val sameItem = (_state.value as? ReaderUiState.Open)?.takeIf { it.item.hashUuid == item.hashUuid }
        _state.value = ReaderUiState.Open(
            item = item,
            blocks = blocks,
            articleBlocks = articleBlocks,
            classification = sameItem?.classification ?: ClassificationState.Loading,
            macros = sameItem?.macros ?: emptyList(),
            summary = sameItem?.summary,
            translate = null,
            translateVisible = false,
            savedLater = item.savedLater,
            extraction = extraction,
        )
    }

    /**
     * Dedup union: prepends every feed-body image whose URL is not already present in the
     * extracted [RichBlock] body. Readability frequently drops images (lead/hero imgs outside
     * the scored region, lazy-loaded imgs with blank `src`); this restores them so images do
     * not "disappear" once extraction lands. Blank-URL feed images are skipped (they would
     * render nothing). Images are non-text blocks and never disturb translate alignment.
     */
    private fun mergeFeedImages(extracted: List<RichBlock>, feedBlocks: List<RichBlock>): List<RichBlock> {
        val present = extracted.mapNotNull { (it as? RichBlock.Image)?.url?.takeIf(String::isNotBlank) }.toHashSet()
        val missing = feedBlocks.filterIsInstance<RichBlock.Image>()
            .filter { it.url.isNotBlank() && it.url !in present }
        return if (missing.isEmpty()) extracted else missing + extracted
    }

    private fun classify(itemId: String) {
        viewModelScope.launch {
            when (val outcome = readerOps.classify(itemId, currentParagraphs())) {
                is LlmOutcome.Err -> updateClassification(ClassificationState.Error(outcome.error.userMessage()))
                is LlmOutcome.Ok -> {
                    val macros = ReaderMacro.forClassification(outcome.value.classification)
                    updateClassification(ClassificationState.Done(outcome.value.classification), macros)
                }
            }
        }
    }

    private fun summarize() {
        val current = _state.value as? ReaderUiState.Open ?: return
        updateSummary(SummaryState.Loading)
        viewModelScope.launch {
            var errored = false
            var lastBullets = emptyList<String>()
            readerOps
                .summarizeStreaming(current.item.hashUuid, (current.articleBlocks ?: current.blocks).toPlainParagraphs())
                .collect { outcome ->
                    when (outcome) {
                        is LlmOutcome.Err -> {
                            errored = true
                            updateSummary(SummaryState.Error(outcome.error.userMessage()))
                        }
                        is LlmOutcome.Ok -> {
                            lastBullets = outcome.value.bullets
                            updateSummary(SummaryState.Streaming(outcome.value.bullets, outcome.value.partial))
                        }
                    }
                }
            if (!errored) updateSummary(SummaryState.Done(lastBullets))
        }
    }

    /**
     * Auto-triggers the streaming summary once the full article body lands, but only when it
     * is substantial enough to warrant one (> [SUMMARY_MIN_WORDS] words). Short articles and
     * items with no extractable body are left alone. The use case is cache-first, so a
     * re-open of an already-summarized item renders instantly without re-streaming.
     */
    private fun autoSummarizeIfLongEnough(articleBlocks: List<RichBlock>?) {
        if (articleBlocks == null) return
        val wordCount = articleBlocks.toPlainParagraphs().sumOf { it.split(Regex("\\s+")).count { w -> w.isNotBlank() } }
        if (wordCount > SUMMARY_MIN_WORDS) summarize()
    }

    fun translate() {
        val current = _state.value as? ReaderUiState.Open ?: return
        viewModelScope.launch {
            updateTranslate(TranslateState.Loading, visible = true)
            when (val outcome = readerOps.translate(current.item.hashUuid, targetLanguage, (current.articleBlocks ?: current.blocks).toPlainParagraphs())) {
                is LlmOutcome.Err -> updateTranslate(TranslateState.Error(outcome.error.userMessage()), visible = true)
                is LlmOutcome.Ok -> updateTranslate(TranslateState.Done(outcome.value), visible = true)
            }
        }
    }

    private fun currentParagraphs(): List<String> {
        val open = _state.value as? ReaderUiState.Open ?: return emptyList()
        return (open.articleBlocks ?: open.blocks).toPlainParagraphs()
    }

    /**
     * S07 (PRD §3.4 [📁 Save Later]): promote/unsave the current item. Idempotent — the
     * repository transactionally writes the `saved_item` row + flips `feed_item.saved_later`.
     * Default folder is "Inbox"; relabeling/filing lands with the saved-items screen.
     */
    fun toggleSave() {
        val current = _state.value as? ReaderUiState.Open ?: return
        viewModelScope.launch {
            if (current.savedLater) {
                savedItems.unsave(current.item.hashUuid)
            } else {
                savedItems.save(current.item.hashUuid, folder = DEFAULT_SAVE_FOLDER)
            }
            _state.value = current.copy(savedLater = !current.savedLater)
        }
    }

    fun dismissError() { _state.value = ReaderUiState.Idle }

    private fun updateClassification(c: ClassificationState, macros: List<ReaderMacro>? = null) {
        val current = _state.value as? ReaderUiState.Open ?: return
        _state.value = current.copy(
            classification = c,
            macros = macros ?: current.macros,
        )
    }

    private fun updateSummary(s: SummaryState?) {
        val current = _state.value as? ReaderUiState.Open ?: return
        _state.value = current.copy(summary = s)
    }

    private fun updateTranslate(t: TranslateState, visible: Boolean) {
        val current = _state.value as? ReaderUiState.Open ?: return
        _state.value = current.copy(translate = t, translateVisible = visible)
    }

    private companion object {
        const val DEFAULT_SAVE_FOLDER = "Inbox"

        /** Articles at/under this word count are too short to auto-summarize. */
        const val SUMMARY_MIN_WORDS = 300
    }
}

/** Reader-sheet UI states. */
sealed interface ReaderUiState {
    data object Idle : ReaderUiState
    data object Loading : ReaderUiState
    data class Open(
        val item: FeedItem,
        val blocks: List<RichBlock>,
        val articleBlocks: List<RichBlock>? = null,
        val classification: ClassificationState,
        val macros: List<ReaderMacro>,
        val summary: SummaryState?,
        val translate: TranslateState?,
        val translateVisible: Boolean,
        val savedLater: Boolean,
        val extraction: ExtractionState = ExtractionState.Idle,
    ) : ReaderUiState
    data class Error(val message: String) : ReaderUiState
}

sealed interface ClassificationState {
    data object Loading : ClassificationState
    data class Done(val label: String) : ClassificationState
    data class Error(val message: String) : ClassificationState
}

sealed interface SummaryState {
    data object Loading : SummaryState
    /** Tokens are arriving: [bullets] are complete lines, [current] is the line being typed. */
    data class Streaming(val bullets: List<String>, val current: String) : SummaryState
    data class Done(val bullets: List<String>) : SummaryState
    data class Error(val message: String) : SummaryState
}

sealed interface TranslateState {
    data object Loading : TranslateState
    data class Done(val response: TranslateResponse) : TranslateState
    data class Error(val message: String) : TranslateState
}

sealed interface ExtractionState {
    data object Idle : ExtractionState
    data object Extracting : ExtractionState
    data object Done : ExtractionState
    data object Failed : ExtractionState
}
