package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.reader.ReaderItemStore
import com.sapphire.domain.feed.FeedRepository
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.TranslateRegions
import com.sapphire.domain.llm.TranslateStreamFrame
import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.model.ReadMechanism
import com.sapphire.domain.model.ReadState
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
 * - When translate-view mode is BILINGUAL or TRANSLATION, translate auto-fires on open.
 *
 * The macros set is derived from the classification via [ReaderMacro.forClassification].
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val items: ReaderItemStore,
    private val readerOps: ReaderOpsUseCase,
    private val savedItems: SavedItemRepository,
    private val feedRepository: FeedRepository,
    private val richContentParser: RichContentParser,
    private val articleExtractor: ArticleExtractor,
    private val articleBodyStore: ArticleBodyStore,
    private val uiPrefsStore: com.sapphire.domain.settings.UiPrefsStore,
    private val themeConfigStore: com.sapphire.domain.settings.ThemeConfigStore,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Idle)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** How translated content renders in the reader: bilingual, origin-only, or translation-only. */
    val translateViewMode: StateFlow<com.sapphire.domain.settings.TranslateViewMode> =
        uiPrefsStore.observeTranslateView()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.sapphire.domain.settings.TranslateViewMode.BILINGUAL)

    fun setTranslateView(mode: com.sapphire.domain.settings.TranslateViewMode) {
        viewModelScope.launch { uiPrefsStore.setTranslateView(mode) }
    }

    val themePreference: StateFlow<com.sapphire.domain.settings.ThemePreference> =
        themeConfigStore.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.sapphire.domain.settings.ThemePreference.DARK)

    fun setTheme(pref: com.sapphire.domain.settings.ThemePreference) {
        viewModelScope.launch { themeConfigStore.set(pref) }
    }

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

            val feedBlocks = richContentParser.parse(item.bodyRaw ?: item.summary ?: item.title)

            val cachedHtml = articleBodyStore.get(itemId)
            if (cachedHtml != null) {
                val cachedArticle = mergeFeedImages(richContentParser.parse(cachedHtml), feedBlocks)
                publish(item, feedBlocks, cachedArticle, ExtractionState.Done)
                classify(itemId)
                autoSummarizeIfLongEnough(cachedArticle)
                autoTranslateIfWarranted()
                return@launch
            }

            val url = item.url
            if (url.isNullOrBlank()) {
                publish(item, feedBlocks, null, ExtractionState.Idle)
                classify(itemId)
                autoTranslateIfWarranted()
                return@launch
            }

            publish(item, feedBlocks, null, ExtractionState.Extracting)
            val (article, extraction) = when (val outcome = articleExtractor.extract(url)) {
                is ExtractionOutcome.Ok -> {
                    articleBodyStore.put(itemId, outcome.html)
                    mergeFeedImages(richContentParser.parse(outcome.html), feedBlocks) to ExtractionState.Done
                }
                is ExtractionOutcome.Err -> null to ExtractionState.Failed
            }
            if ((_state.value as? ReaderUiState.Open)?.item?.hashUuid == itemId) {
                publish(item, feedBlocks, article, extraction)
                classify(itemId)
                autoSummarizeIfLongEnough(article)
                autoTranslateIfWarranted()
            }
        }
    }

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

    fun summarize() {
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

    private fun autoSummarizeIfLongEnough(articleBlocks: List<RichBlock>?) {
        if (articleBlocks == null) return
        val wordCount = articleBlocks.toPlainParagraphs().sumOf { it.split(WS_REGEX).count { w -> w.isNotBlank() } }
        if (wordCount > SUMMARY_MIN_WORDS) summarize()
    }

    /** Auto-translate when the translate-view mode is BILINGUAL or TRANSLATION (not ORIGIN).
     *  Waits for auto-summarize to fully resolve (Done/Error/null) so translate captures all
     *  summary bullets — not just the partial Streaming ones. */
    private fun autoTranslateIfWarranted() {
        if (translateViewMode.value == com.sapphire.domain.settings.TranslateViewMode.ORIGIN) return
        viewModelScope.launch {
            _state.first { s ->
                val open = s as? ReaderUiState.Open ?: return@first true
                val sum = open.summary
                sum == null || sum is SummaryState.Done || sum is SummaryState.Error
            }
            translate()
        }
    }

    fun translate() {
        val current = _state.value as? ReaderUiState.Open ?: return
        val regions = TranslateRegions(
            title = listOfNotNull(current.item.title.takeIf { it.isNotBlank() }),
            summary = when (val s = current.summary) {
                is SummaryState.Done -> s.bullets
                is SummaryState.Streaming -> s.bullets
                else -> emptyList()
            },
            brief = current.blocks.toPlainParagraphs(),
            article = current.articleBlocks?.toPlainParagraphs() ?: emptyList(),
        )
        updateTranslate(TranslateState.Loading, visible = true)
        viewModelScope.launch {
            var errored = false
            var lastFrame = TranslateStreamFrame()
            readerOps
                .translateStreaming(current.item.hashUuid, targetLanguage, regions)
                .collect { outcome ->
                    when (outcome) {
                        is LlmOutcome.Err -> {
                            errored = true
                            updateTranslate(TranslateState.Error(outcome.error.userMessage()), visible = true)
                        }
                        is LlmOutcome.Ok -> {
                            lastFrame = outcome.value
                            updateTranslate(TranslateState.Streaming(outcome.value), visible = true)
                        }
                    }
                }
            if (!errored) updateTranslate(TranslateState.Done(lastFrame), visible = true)
        }
    }

    private fun currentParagraphs(): List<String> {
        val open = _state.value as? ReaderUiState.Open ?: return emptyList()
        return (open.articleBlocks ?: open.blocks).toPlainParagraphs()
    }

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

    /** Toggle read/unread for the current item. */
    fun toggleRead() {
        val current = _state.value as? ReaderUiState.Open ?: return
        val isRead = current.item.readState == ReadState.READ
        viewModelScope.launch {
            if (isRead) {
                feedRepository.markUnread(current.item.hashUuid)
            } else {
                feedRepository.markRead(current.item.hashUuid, ReadMechanism.MANUAL)
            }
            _state.value = current.copy(item = current.item.copy(readState = if (isRead) ReadState.UNREAD else ReadState.READ))
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
        // When summary completes and translate is visible, re-fire translate to include
        // the new summary bullets in the translate regions.
        if (s is SummaryState.Done && current.translateVisible) {
            translate()
        }
    }

    private fun updateTranslate(t: TranslateState, visible: Boolean) {
        val current = _state.value as? ReaderUiState.Open ?: return
        _state.value = current.copy(translate = t, translateVisible = visible)
    }

    private companion object {
        const val DEFAULT_SAVE_FOLDER = "Inbox"
        const val SUMMARY_MIN_WORDS = 300
        val WS_REGEX = Regex("\\s+")
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
    data class Streaming(val bullets: List<String>, val current: String) : SummaryState
    data class Done(val bullets: List<String>) : SummaryState
    data class Error(val message: String) : SummaryState
}

sealed interface TranslateState {
    data object Loading : TranslateState
    data class Streaming(val frame: TranslateStreamFrame) : TranslateState
    data class Done(val frame: TranslateStreamFrame) : TranslateState
    data class Error(val message: String) : TranslateState
}

sealed interface ExtractionState {
    data object Idle : ExtractionState
    data object Extracting : ExtractionState
    data object Done : ExtractionState
    data object Failed : ExtractionState
}
