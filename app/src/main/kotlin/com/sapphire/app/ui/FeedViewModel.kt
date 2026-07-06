package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.feed.FeedRepository
import com.sapphire.domain.settings.TranslateViewMode
import com.sapphire.domain.settings.UiPrefsStore
import com.sapphire.data.feed.FeedRefreshService
import com.sapphire.domain.feed.filterByQuery
import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.model.ReadMechanism
import com.sapphire.domain.source.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Timeline UI state. The timeline itself is a cold [Flow] from the repository hoisted into
 * a [StateFlow] so Compose recomposes only on real change.
 *
 * Read model: an item becomes READ only on explicit user action — opening the reader
 * ([markReadOnOpen]) or the manual mark-read button / batch mark-read. There is no
 * scroll-to-mark-read; scrolling never changes read state.
 */
@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class FeedViewModel @Inject constructor(
    private val repository: FeedRepository,
    private val refreshService: FeedRefreshService,
    private val uiPrefsStore: UiPrefsStore,
    private val savedItemRepository: com.sapphire.domain.save.SavedItemRepository,
    private val themeConfigStore: com.sapphire.domain.settings.ThemeConfigStore,
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    /** In-feed free-text search query. Blank = full timeline. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val density: StateFlow<UiPrefsStore.FeedDensity> = uiPrefsStore.observeDensity()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiPrefsStore.FeedDensity.DEFAULT)

    val translateView: StateFlow<TranslateViewMode> = uiPrefsStore.observeTranslateView()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TranslateViewMode.BILINGUAL)

    /** Active read-state scope chip (All / Unread / Saved). Applied client-side. */
    private val _scope = MutableStateFlow(FeedScope.ALL)
    val feedScope: StateFlow<FeedScope> = _scope.asStateFlow()
    /** Active source/category filter; null = unified timeline (all sources). */
    private val _filter = MutableStateFlow<FeedFilter?>(null)

    /**
     * Items staged for deletion but not yet committed. Kept out of [visibleTimeline] so the
     * UI hides them immediately while an Undo snackbar is showing; committed after a short
     * delay (or cancelled wholesale by [undoDelete]).
     */
    private val _pendingDeletion = MutableStateFlow<Set<String>>(emptySet())
    private var deleteCommitJob: Job? = null

    /** "Has the user ever onboarded a topic?" — gates the true cold-start empty state. */
    val hasTopic: StateFlow<Boolean> = sourceRepository.observeHasTopic()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True once the first refresh pass has completed (success or fail). */
    private val _firstRefreshDone = MutableStateFlow(false)
    val firstRefreshDone: StateFlow<Boolean> = _firstRefreshDone.asStateFlow()

    /** One-shot stream of non-zero source-error counts from a completed refresh pass. */
    private val _refreshErrorEvents = Channel<Int>(Channel.BUFFERED)
    val refreshErrorEvents = _refreshErrorEvents.receiveAsFlow()

    val visibleTimeline: StateFlow<List<FeedItem>> = combine(
        _filter.flatMapLatest { f ->
            when (f) {
                null -> repository.observeTimeline()
                is FeedFilter.BySource -> repository.observeBySource(f.sourceId)
                is FeedFilter.BySourceIds -> repository.observeBySources(f.sourceIds)
                is FeedFilter.ByCategory -> repository.observeCategories(f.categoryIds)
            }
        },
        // Debounce keystrokes so we don't refilter the whole timeline (O(N), 3 lowercase
        // allocations per item) on every character. Also dedups consecutive equal queries.
        _query.debounce(QUERY_DEBOUNCE_MS).distinctUntilChanged(),
        _scope,
        _pendingDeletion,
    ) { items, q, scope, pending ->
        items.filterByQuery(q).filterByScope(scope).filter { it.hashUuid !in pending }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Update the in-feed search query. Blank restores the full (filtered) timeline. */
    fun setQuery(q: String) { _query.value = q }

    /** Set the read-state scope chip (All / Unread / Saved). */
    fun setScope(scope: FeedScope) { _scope.value = scope }


    fun setDensity(d: UiPrefsStore.FeedDensity) {
        viewModelScope.launch { uiPrefsStore.setDensity(d) }
    }

    fun setTranslateView(mode: TranslateViewMode) {
        viewModelScope.launch { uiPrefsStore.setTranslateView(mode) }
    }

    val themePreference: StateFlow<com.sapphire.domain.settings.ThemePreference> =
        themeConfigStore.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.sapphire.domain.settings.ThemePreference.DARK)

    fun setTheme(pref: com.sapphire.domain.settings.ThemePreference) {
        viewModelScope.launch { themeConfigStore.set(pref) }
    }
    fun setSourceFilter(sourceId: String, label: String) {
        _filter.value = FeedFilter.BySource(sourceId)
        _filterLabel.value = label
    }

    /** Filter the timeline to a virtual domain-group (set of source ids). */
    fun setSourceGroupFilter(sourceIds: Set<String>, label: String) {
        _filter.value = FeedFilter.BySourceIds(sourceIds)
        _filterLabel.value = label
    }

    fun setCategoryFilter(categoryIds: Set<String>, label: String) {
        _filter.value = FeedFilter.ByCategory(categoryIds)
        _filterLabel.value = label
    }

    fun clearFilter() {
        _filter.value = null
        _filterLabel.value = null
    }

    /** Human-readable label for the active filter, surfaced in the top bar. Null = "All Feeds". */
    private val _filterLabel = MutableStateFlow<String?>(null)
    val filterLabel: StateFlow<String?> = _filterLabel.asStateFlow()

    /** True when the raw (unfiltered) timeline has at least one item. Lets the UI tell
     *  "no items at all" (→ onboarding empty state) apart from "search yields nothing". */
    val hasAnyItems: StateFlow<Boolean> = repository.observeHasAny()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True while a refresh pass is in flight (manual or auto). Drives the spinner only. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        // One silent streaming refresh per VM lifetime. The VM is scoped to the FEED
        // NavBackStackEntry, which survives navigation, so this fires only on the genuine
        // first entry to the feed — not on every return from another screen (e.g. the +
        // / onboarding flow), which would needlessly re-fetch.
        refresh()
    }
    /**
     * Streaming refresh: fetches sources concurrently and inserts items as each completes,
     * so the live timeline updates incrementally. Source errors are tallied and surfaced
     * via [refreshErrorEvents] when the pass ends with ≥1 failure (the timeline itself is
     * still the success signal — no "N new" toast). Any throw is swallowed so refresh can
     * never crash the app; per-source health state is still stamped for the drawer.
     */
    fun refresh() {
        viewModelScope.launch {
            if (_refreshing.value) return@launch
            _refreshing.value = true
            var errorCount = 0
            try {
                refreshService.refreshStreaming().collect { ev ->
                    if (ev is FeedRefreshService.StreamEvent.SourceError) errorCount++
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.e("FeedViewModel", "refresh failed", t)
            } finally {
                _refreshing.value = false
                _firstRefreshDone.value = true
                if (errorCount > 0) _refreshErrorEvents.trySend(errorCount)
            }
        }
    }

    /** Mark an item READ when the reader opens it (the only implicit read transition). */
    fun markReadOnOpen(itemId: String) {
        viewModelScope.launch { repository.markRead(itemId, ReadMechanism.MANUAL) }
    }

    /** Manual per-card toggle: READ→UNREAD or UNREAD→READ. */
    fun toggleRead(itemId: String, isCurrentlyRead: Boolean) {
        viewModelScope.launch {
            if (isCurrentlyRead) repository.markUnread(itemId)
            else repository.markRead(itemId, ReadMechanism.MANUAL)
        }
    }

    /** Toggle saved/unsaved for an item (swipe-left gesture). */
    fun toggleSaved(itemId: String, isCurrentlySaved: Boolean) {
        viewModelScope.launch {
            if (isCurrentlySaved) savedItemRepository.unsave(itemId)
            else savedItemRepository.save(itemId, folder = DEFAULT_SAVE_FOLDER)
        }
    }

    private companion object {
        const val DEFAULT_SAVE_FOLDER = "Inbox"
        const val QUERY_DEBOUNCE_MS = 250L
        const val DELETE_UNDO_DELAY_MS = 4500L
    }

    /** Batch-mark the selected items READ. */
    fun markReadBatch(itemIds: Collection<String>) {
        viewModelScope.launch { repository.markReadBatch(itemIds) }
    }

    /** Batch-revert the selected items to UNREAD. */
    fun markUnreadBatch(itemIds: Collection<String>) {
        viewModelScope.launch { repository.markUnreadBatch(itemIds) }
    }

    /**
     * Stage [itemIds] for deletion: hides them from [visibleTimeline] immediately, then
     * commits after [DELETE_UNDO_DELAY_MS]. [undoDelete] cancels the commit and restores
     * the items wholesale. Mirrors the swipe-to-save/read undo semantics on the same screen.
     */
    fun deleteItems(itemIds: Collection<String>) {
        val ids = itemIds.toSet()
        if (ids.isEmpty()) return
        _pendingDeletion.value = _pendingDeletion.value + ids
        deleteCommitJob?.cancel()
        deleteCommitJob = viewModelScope.launch {
            delay(DELETE_UNDO_DELAY_MS)
            repository.deleteItems(ids)
            _pendingDeletion.value = _pendingDeletion.value - ids
        }
    }

    /** Cancel a pending batch delete and restore the staged items to the timeline. */
    fun undoDelete() {
        deleteCommitJob?.cancel()
        _pendingDeletion.value = emptySet()
    }

    /**
     * Mark every item currently in the visible timeline READ — the top-bar "mark all as
     * read" sweep. Respects the active source/category filter and the scope chip, so e.g.
     * in UNREAD scope it clears the visible unread queue. Undo is not offered here (the
     * action is explicit and the items remain in the timeline, just read).
     */
    fun markAllVisibleRead() {
        val ids = visibleTimeline.value.map { it.hashUuid }
        if (ids.isEmpty()) return
        viewModelScope.launch { repository.markReadBatch(ids) }
    }
}

/** Active timeline filter applied from the sources drawer. */
sealed interface FeedFilter {
    data class BySource(val sourceId: String) : FeedFilter
    data class BySourceIds(val sourceIds: Set<String>) : FeedFilter
    data class ByCategory(val categoryIds: Set<String>) : FeedFilter
}

/** Read-state scope for the timeline filter-chip row (All / Unread / Saved). */
enum class FeedScope { ALL, UNREAD, SAVED }

/** Client-side scope filter applied after the source/category + query filters. */
private fun List<FeedItem>.filterByScope(scope: FeedScope): List<FeedItem> = when (scope) {
    FeedScope.ALL -> this
    FeedScope.UNREAD -> filter { it.readState == com.sapphire.domain.model.ReadState.UNREAD }
    FeedScope.SAVED -> filter { it.savedLater }
}
