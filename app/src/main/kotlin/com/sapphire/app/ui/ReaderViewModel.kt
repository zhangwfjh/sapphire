package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.reader.ReaderItemStore
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.TranslateResponse
import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.reader.BodyParagraphParser
import com.sapphire.domain.reader.ReaderMacro
import com.sapphire.domain.reader.ReaderOpsUseCase
import com.sapphire.domain.save.SavedItemRepository
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
 * - [open] loads the item body + kicks Tier-1 classification. While classification runs
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
            val paragraphs = BodyParagraphParser.parse(item.bodyRaw ?: item.summary)
            _state.value = ReaderUiState.Open(
                item = item,
                paragraphs = paragraphs,
                classification = ClassificationState.Loading,
                macros = emptyList(),
                summary = null,
                translate = null,
                translateVisible = false,
                savedLater = item.savedLater,
            )
            classify(itemId)
        }
    }

    private fun classify(itemId: String) {
        viewModelScope.launch {
            when (val outcome = readerOps.classify(itemId)) {
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
        viewModelScope.launch {
            when (val outcome = readerOps.summarize(current.item.hashUuid)) {
                is LlmOutcome.Err -> updateSummary(SummaryState.Error(outcome.error.userMessage()))
                is LlmOutcome.Ok -> updateSummary(SummaryState.Done(outcome.value.bullets))
            }
        }
    }

    fun translate() {
        val current = _state.value as? ReaderUiState.Open ?: return
        viewModelScope.launch {
            updateTranslate(TranslateState.Loading, visible = true)
            when (val outcome = readerOps.translate(current.item.hashUuid, targetLanguage)) {
                is LlmOutcome.Err -> updateTranslate(TranslateState.Error(outcome.error.userMessage()), visible = true)
                is LlmOutcome.Ok -> updateTranslate(TranslateState.Done(outcome.value), visible = true)
            }
        }
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
    }
}

/** Reader-sheet UI states. */
sealed interface ReaderUiState {
    data object Idle : ReaderUiState
    data object Loading : ReaderUiState
    data class Open(
        val item: FeedItem,
        val paragraphs: List<String>,
        val classification: ClassificationState,
        val macros: List<ReaderMacro>,
        val summary: SummaryState?,
        val translate: TranslateState?,
        val translateVisible: Boolean,
        val savedLater: Boolean,
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
    data class Done(val bullets: List<String>) : SummaryState
    data class Error(val message: String) : SummaryState
}

sealed interface TranslateState {
    data object Loading : TranslateState
    data class Done(val response: TranslateResponse) : TranslateState
    data class Error(val message: String) : TranslateState
}
