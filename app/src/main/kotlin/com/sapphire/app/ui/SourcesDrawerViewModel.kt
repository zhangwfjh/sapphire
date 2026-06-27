package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.feed.FeedRepository
import com.sapphire.domain.model.SourceKind
import com.sapphire.domain.source.SourceFolderNode
import com.sapphire.domain.source.SourceRepository
import com.sapphire.domain.source.SourceRepository.Outcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Emitted after a "mark all as read" sweep so the UI can surface an Undo snackbar.
 * [itemIds] are the ids that were newly flipped READ — reverting them restores the prior
 * unread state.
 */
data class MarkAllReadEvent(val itemIds: List<String>, val label: String, val count: Int)

/**
 * State for the Sources drawer. The tree is a cold [Flow] hoisted into a [StateFlow];
 * every mutation re-fetches through the repository, so the tree recomposes live.
 *
 * Conflict snackbar: add/move/update that collide with the `(category_id, url)` unique index
 * surface a short message instead of silently dropping.
 *
 * Mark-all-read: swiping a source (or folder) left sweeps its unread items to READ and
 * emits a [MarkAllReadEvent] on [markReadEvents] so the drawer can offer an Undo snackbar
 * (PRD §3.3 "Undo" safety net). Reverting forwards through [undoMarkRead].
 */
@HiltViewModel
class SourcesDrawerViewModel @Inject constructor(
    private val repository: SourceRepository,
    private val feedRepository: FeedRepository,
) : ViewModel() {

    val tree: StateFlow<List<SourceFolderNode>> = repository.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _conflict = MutableStateFlow<String?>(null)
    val conflict: StateFlow<String?> = _conflict.asStateFlow()

    private val _markReadEvents = Channel<MarkAllReadEvent>(capacity = Channel.BUFFERED)
    /** One-shot mark-all-read events for the Undo snackbar. */
    val markReadEvents = _markReadEvents.receiveAsFlow()

    fun addSource(categoryId: String, title: String, url: String, kind: SourceKind) {
        viewModelScope.launch {
            when (val r = repository.addSource(categoryId, url.trim(), title.trim(), kind)) {
                is Outcome.Ok -> Unit
                is Outcome.Conflict -> _conflict.value = "A source with that URL already exists in the target folder."
            }
        }
    }

    fun updateSource(id: String, title: String, url: String, kind: SourceKind, enabled: Boolean) {
        viewModelScope.launch {
            when (val r = repository.updateSource(id, title.trim(), url.trim(), kind, enabled)) {
                is Outcome.Ok -> Unit
                is Outcome.Conflict -> _conflict.value = "Another source in this folder already uses that URL."
            }
        }
    }

    fun moveSource(id: String, toCategoryId: String) {
        viewModelScope.launch {
            when (val r = repository.moveSource(id, toCategoryId)) {
                is Outcome.Ok -> Unit
                is Outcome.Conflict -> _conflict.value = "That URL already exists in the target folder."
            }
        }
    }

    /** Batch-move a set of sources into [toCategoryId]. Conflicting URLs are skipped. */
    fun moveSources(ids: Set<String>, toCategoryId: String) {
        viewModelScope.launch {
            var conflicts = 0
            for (id in ids) {
                when (repository.moveSource(id, toCategoryId)) {
                    is Outcome.Ok -> Unit
                    is Outcome.Conflict -> conflicts++
                }
            }
            if (conflicts > 0) {
                _conflict.value = "$conflicts source(s) skipped — URL already in target folder."
            }
        }
    }

    fun deleteSource(id: String) {
        viewModelScope.launch { repository.deleteSource(id) }
    }

    /** Batch-delete a set of sources. */
    fun deleteSources(ids: Set<String>) {
        viewModelScope.launch {
            for (id in ids) repository.deleteSource(id)
        }
    }

    fun addCategory(topicId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { repository.addCategory(topicId, trimmed) }
    }

    /**
     * Create a new folder under the current topic. The topic id is read from the topic
     * table (not inferred from the first category) so this works even when the user has
     * deleted every folder — the drawer's "New folder" button stays usable.
     */
    fun addTopLevelCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val topicId = repository.currentTopicId()
            if (topicId == null) {
                _conflict.value = "Curate a topic first — folders live under a topic."
                return@launch
            }
            repository.addCategory(topicId, trimmed)
        }
    }

    fun renameCategory(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { repository.renameCategory(id, trimmed) }
    }


    /** Merge all sources from [fromId] into [toId], then delete [fromId]. Dedup by URL. */
    fun mergeCategory(fromId: String, toId: String) {
        viewModelScope.launch {
            runCatching { repository.mergeCategory(fromId, toId) }
                .onFailure { _conflict.value = "Merge failed: ${it.message}" }
        }
    }
    fun deleteCategory(id: String) {
        viewModelScope.launch { repository.deleteCategory(id) }
    }

    fun dismissConflict() { _conflict.value = null }

    /**
     * Mark every unread item from any of [sourceIds] READ (virtual domain-group sweep).
     * Same Undo contract as [markAllReadInSource].
     */
    fun markAllReadInSourceGroup(sourceIds: Set<String>, label: String) {
        viewModelScope.launch {
            val ids = feedRepository.markReadBySources(sourceIds)
            if (ids.isNotEmpty()) {
                _markReadEvents.trySend(MarkAllReadEvent(ids, label, ids.size))
            }
        }
    }

    /**
     * Mark every unread item from [sourceId] READ. Emits a [MarkAllReadEvent] with the
     * newly-read ids so the drawer can show "Marked N as read" with an Undo action.
     * No-op (no event) when there were zero unread items.
     */
    fun markAllReadInSource(sourceId: String, label: String) {
        viewModelScope.launch {
            val ids = feedRepository.markReadBySource(sourceId)
            if (ids.isNotEmpty()) {
                _markReadEvents.trySend(MarkAllReadEvent(ids, label, ids.size))
            }
        }
    }

    /**
     * Mark every unread item in [categoryId] READ (folder-level sweep). Same Undo contract
     * as [markAllReadInSource].
     */
    fun markAllReadInCategory(categoryId: String, label: String) {
        viewModelScope.launch {
            val ids = feedRepository.markReadByCategory(categoryId)
            if (ids.isNotEmpty()) {
                _markReadEvents.trySend(MarkAllReadEvent(ids, label, ids.size))
            }
        }
    }

    /** Revert a mark-all-read sweep (Undo snackbar). Idempotent. */
    fun undoMarkRead(itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        viewModelScope.launch { feedRepository.undoMarkRead(itemIds) }
    }
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _exportXml = MutableStateFlow<String?>(null)
    val exportXml: StateFlow<String?> = _exportXml.asStateFlow()

    /**
     * Import an arbitrary OPML 2.0 stream. Emits [ImportState] transitions; the UI owns
     * opening the stream (SAF picker or the bundled asset).
     */
    fun importOpml(stream: java.io.InputStream) {
        viewModelScope.launch {
            _importState.value = ImportState.Importing
            try {
                val count = repository.importFromOpml(stream)
                _importState.value = ImportState.Done(count)
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Import failed")
            }
        }
    }

    /**
     * Build the OPML 2.0 export of the current tree and emit it on [exportXml]. The UI
     * observes the flow and writes it to the user-chosen SAF document, then calls
     * [consumeExport] once written.
     */
    fun exportOpml() {
        viewModelScope.launch {
            try {
                _exportXml.value = repository.exportToOpml()
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Export failed")
            }
        }
    }

    /** Clear the pending export payload after the UI has written it out. */
    fun consumeExport() {
        _exportXml.value = null
    }

    fun dismissImportState() {
        _importState.value = ImportState.Idle
    }
}

sealed interface ImportState {
    data object Idle : ImportState
    data object Importing : ImportState
    data class Done(val sourcesImported: Int) : ImportState
    data class Error(val message: String) : ImportState
}
