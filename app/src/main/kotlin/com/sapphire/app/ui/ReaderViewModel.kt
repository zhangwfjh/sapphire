package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.feed.FeedRepository
import com.sapphire.domain.llm.TranslateStreamFrame
import com.sapphire.domain.model.ReadMechanism
import com.sapphire.domain.model.ReadState
import com.sapphire.domain.reader.ReaderMacro
import com.sapphire.domain.reader.ReaderSession
import com.sapphire.domain.reader.ReaderSession.Content
import com.sapphire.domain.reader.ReaderSession.ReaderEvent
import com.sapphire.domain.settings.ThemePreference
import com.sapphire.domain.settings.ThemeConfigStore
import com.sapphire.domain.settings.UiPrefsStore
import com.sapphire.domain.settings.TranslateViewMode
import com.sapphire.domain.save.SavedItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reader-sheet state. A thin reducer over [ReaderSession] events — the session owns the
 * open ladder, auto-op gating, and region assembly; this class only maps events to
 * [ReaderUiState] and forwards taps. The one piece of reducer policy: when a summary
 * settles after translate became visible, translate re-fires so its regions include the
 * new bullets.
 *
 * Prefs (translate-view, theme) surface here for the right drawer, as in FeedViewModel.
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val session: ReaderSession,
    private val savedItems: SavedItemRepository,
    private val feedRepository: FeedRepository,
    private val uiPrefsStore: UiPrefsStore,
    private val themeConfigStore: ThemeConfigStore,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Idle)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var openJob: Job? = null
    private var targetLanguage: String = "zh"

    /** Summary bullets of the open session — fed back into translate regions. */
    private var summaryBullets: List<String> = emptyList()

    val translateViewMode: StateFlow<TranslateViewMode> =
        uiPrefsStore.observeTranslateView()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TranslateViewMode.BILINGUAL)

    fun setTranslateView(mode: TranslateViewMode) {
        viewModelScope.launch { uiPrefsStore.setTranslateView(mode) }
    }

    val themePreference: StateFlow<ThemePreference> =
        themeConfigStore.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemePreference.DARK)

    fun setTheme(pref: ThemePreference) {
        viewModelScope.launch { themeConfigStore.set(pref) }
    }

    fun open(itemId: String, targetLanguage: String = this.targetLanguage) {
        this.targetLanguage = targetLanguage
        summaryBullets = emptyList()
        _state.value = ReaderUiState.Loading
        openJob?.cancel()
        openJob = viewModelScope.launch {
            session.open(itemId, targetLanguage, translateViewMode.value).collect(::reduce)
        }
    }

    fun summarize() {
        val content = currentContent() ?: return
        updateSummary(SummaryState.Loading)
        viewModelScope.launch {
            session.summarize(content).collect(::reduce)
        }
    }

    fun translate() {
        val current = _state.value as? ReaderUiState.Open ?: return
        val content = Content(current.item, current.blocks, current.articleBlocks)
        viewModelScope.launch {
            session.translate(content, summaryBullets, targetLanguage).collect(::reduce)
        }
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

    // ---- reducer ----

    private fun reduce(event: ReaderEvent) {
        when (event) {
            is ReaderEvent.NotFound -> _state.value = ReaderUiState.Error(event.message)
            is ReaderEvent.Opened -> {
                // A late Opened (e.g. extraction finished after dismiss) must not
                // resurrect the sheet — original behavior gated on still being open.
                if (_state.value is ReaderUiState.Idle || _state.value is ReaderUiState.Error) return
                val sameItem = (_state.value as? ReaderUiState.Open)
                    ?.takeIf { it.item.hashUuid == event.item.hashUuid }
                _state.value = ReaderUiState.Open(
                    item = event.item,
                    blocks = event.blocks,
                    articleBlocks = event.articleBlocks,
                    classification = sameItem?.classification ?: ClassificationState.Loading,
                    macros = sameItem?.macros ?: emptyList(),
                    summary = sameItem?.summary,
                    translate = null,
                    translateVisible = false,
                    savedLater = event.item.savedLater,
                    extraction = event.extraction.toUi(),
                )
            }
            is ReaderEvent.ClassificationDone ->
                updateClassification(ClassificationState.Done(event.label), ReaderMacro.forClassification(event.label))
            is ReaderEvent.ClassificationError ->
                updateClassification(ClassificationState.Error(event.message))
            is ReaderEvent.SummaryLoading -> updateSummary(SummaryState.Loading)
            is ReaderEvent.SummaryStreaming -> {
                summaryBullets = event.frame.bullets
                updateSummary(SummaryState.Streaming(event.frame.bullets, event.frame.partial))
            }
            is ReaderEvent.SummaryDone -> {
                summaryBullets = event.frame.bullets
                // Re-fire policy: a settled summary after translate became visible means
                // translate should pick up the new bullets.
                val visible = (_state.value as? ReaderUiState.Open)?.translateVisible == true
                updateSummary(SummaryState.Done(event.frame.bullets))
                if (visible) translate()
            }
            is ReaderEvent.SummaryError -> updateSummary(SummaryState.Error(event.message))
            is ReaderEvent.TranslateLoading -> updateTranslate(TranslateState.Loading, visible = true)
            is ReaderEvent.TranslateStreaming -> updateTranslate(TranslateState.Streaming(event.frame), visible = true)
            is ReaderEvent.TranslateDone -> updateTranslate(TranslateState.Done(event.frame), visible = true)
            is ReaderEvent.TranslateError -> updateTranslate(TranslateState.Error(event.message), visible = true)
            is ReaderEvent.TranslateSkipped -> {
                val current = _state.value as? ReaderUiState.Open ?: return
                _state.value = current.copy(translate = null, translateVisible = false)
            }
        }
    }

    private fun currentContent(): Content? {
        val open = _state.value as? ReaderUiState.Open ?: return null
        return Content(open.item, open.blocks, open.articleBlocks)
    }

    private fun ReaderSession.ExtractionState.toUi(): ExtractionState = when (this) {
        ReaderSession.ExtractionState.IDLE -> ExtractionState.Idle
        ReaderSession.ExtractionState.EXTRACTING -> ExtractionState.Extracting
        ReaderSession.ExtractionState.DONE -> ExtractionState.Done
        ReaderSession.ExtractionState.FAILED -> ExtractionState.Failed
    }

    private fun updateClassification(c: ClassificationState, macros: List<ReaderMacro>? = null) {
        val current = _state.value as? ReaderUiState.Open ?: return
        _state.value = current.copy(classification = c, macros = macros ?: current.macros)
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
    }
}

/** Reader-sheet UI states. */
sealed interface ReaderUiState {
    data object Idle : ReaderUiState
    data object Loading : ReaderUiState
    data class Open(
        val item: com.sapphire.domain.model.FeedItem,
        val blocks: List<com.sapphire.domain.reader.RichBlock>,
        val articleBlocks: List<com.sapphire.domain.reader.RichBlock>? = null,
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
