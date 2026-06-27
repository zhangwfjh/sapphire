package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.feed.FeedRepository
import com.sapphire.data.feed.FeedRefreshService
import com.sapphire.domain.feed.filterByQuery
import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.model.ReadMechanism
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: FeedRepository,
    private val refreshService: FeedRefreshService,
) : ViewModel() {

    /** In-feed free-text search query. Blank = full timeline. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Active source/category filter; null = unified timeline (all sources). */
    private val _filter = MutableStateFlow<FeedFilter?>(null)

    val visibleTimeline: StateFlow<List<FeedItem>> = combine(
        _filter.flatMapLatest { f ->
            when (f) {
                null -> repository.observeTimeline()
                is FeedFilter.BySource -> repository.observeBySource(f.sourceId)
                is FeedFilter.BySourceIds -> repository.observeBySources(f.sourceIds)
                is FeedFilter.ByCategory -> repository.observeCategories(f.categoryIds)
            }
        },
        _query,
    ) { items, q -> items.filterByQuery(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Update the in-feed search query. Blank restores the full (filtered) timeline. */
    fun setQuery(q: String) { _query.value = q }

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
    val hasAnyItems: StateFlow<Boolean> = repository.observeTimeline()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True while a refresh pass is in flight (manual or auto). Drives the spinner only. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Silent streaming refresh: fetches sources concurrently and inserts items as each
     * source completes, so the live timeline updates incrementally. No "N new" snackbar —
     * the timeline itself is the feedback. Errors are swallowed (health state is still
     * stamped on the source row for the drawer).
     */
    fun refresh() {
        viewModelScope.launch {
            if (_refreshing.value) return@launch
            _refreshing.value = true
            try {
                refreshService.refreshStreaming().collect { /* silent — timeline updates live */ }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Best-effort refresh must never crash the app. Any throw (DB error,
                // fetcher bug, flow body failure) is swallowed; source health state
                // already surfaces per-source errors in the drawer.
                android.util.Log.e("FeedViewModel", "refresh failed", t)
            } finally {
                _refreshing.value = false
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

    /** Batch-mark the selected items READ. */
    fun markReadBatch(itemIds: Collection<String>) {
        viewModelScope.launch { repository.markReadBatch(itemIds) }
    }

    /** Batch-revert the selected items to UNREAD. */
    fun markUnreadBatch(itemIds: Collection<String>) {
        viewModelScope.launch { repository.markUnreadBatch(itemIds) }
    }

    /** Batch-delete (remove) the selected items. */
    fun deleteItems(itemIds: Collection<String>) {
        viewModelScope.launch { repository.deleteItems(itemIds) }
    }
}

/** Active timeline filter applied from the sources drawer. */
sealed interface FeedFilter {
    data class BySource(val sourceId: String) : FeedFilter
    data class BySourceIds(val sourceIds: Set<String>) : FeedFilter
    data class ByCategory(val categoryIds: Set<String>) : FeedFilter
}
