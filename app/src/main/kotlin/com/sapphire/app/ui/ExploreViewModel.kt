package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.explore.ExploreCatalogRepository
import com.sapphire.domain.explore.FeedPreview
import com.sapphire.domain.explore.FeedPreviewResult
import com.sapphire.domain.explore.ExploreFeed
import com.sapphire.domain.explore.ExploreSection
import com.sapphire.domain.explore.SearchFeedsUseCase
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.source.SourceRepository
import com.sapphire.domain.source.SourceRepository.Outcome
import com.sapphire.domain.util.normalizeSourceUrl
import com.sapphire.domain.util.parseSourceKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A category the user can subscribe a feed into. */
data class CategoryOption(val id: String, val name: String)

/** A feed card in the Explore UI, with its already-subscribed flag resolved. */
data class ExploreFeedUi(
    val feed: ExploreFeed,
    val subscribed: Boolean,
)

/** UI-facing section (curated or discovered) with resolved subscription state. */
data class ExploreSectionUi(
    val title: String,
    val feeds: List<ExploreFeedUi>,
    val kind: SectionKind,
)

enum class SectionKind { CURATED, RECENTLY_DISCOVERED }

enum class SearchState { IDLE, LOADING, RESULTS, ERROR, EMPTY }

/** One-shot subscribe result for snackbar rendering. */
sealed interface SubscribeResult {
    data class Added(val folder: String) : SubscribeResult
    data object Conflict : SubscribeResult
}

/** One-shot feed-preview state for the "peek before subscribe" sheet. */
sealed interface PreviewState {
    data object Idle : PreviewState
    data class Loading(val feed: ExploreFeed) : PreviewState
    data class Loaded(
        val feed: ExploreFeed,
        val items: List<com.sapphire.domain.explore.FeedPreviewItem>,
    ) : PreviewState
    data class Empty(val feed: ExploreFeed) : PreviewState
    data class Failed(val feed: ExploreFeed) : PreviewState
}

/**
 * Holds Explore state: catalog browse rails (curated + discovered), search query/results,
 * the already-subscribed URL set (drives "Added" card state), and the category list the
 * picker needs. Subscribe delegates to [SourceRepository.addSource] and surfaces its
 * unique-index conflict as [SubscribeResult.Conflict].
 */
@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val catalogRepository: ExploreCatalogRepository,
    private val sourceRepository: SourceRepository,
    private val searchFeeds: SearchFeedsUseCase,
    private val feedPreview: FeedPreview,
) : ViewModel() {

    /** Normalized URLs of sources already subscribed, for "Added" state. */
    private val subscribedUrls: StateFlow<Set<String>> = sourceRepository.observeTree()
        .map { tree ->
            tree.flatMap { it.sources }
                .map { normalizeSourceUrl(it.source.url) }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Normalized URLs surfaced by the curated + discovered rails. Search results that
     *  duplicate a rail are dropped — the user can subscribe from the rail directly. */
    private val catalogUrls: StateFlow<Set<String>> = catalogRepository.observeCatalog()
        .map { catalog ->
            catalog.flatMap { it.feeds }
                .map { normalizeSourceUrl(it.url) }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Browse rails, with per-feed subscribed flags resolved. */
    val sections: StateFlow<List<ExploreSectionUi>> =
        combine(catalogRepository.observeCatalog(), subscribedUrls) { catalog, subs ->
            catalog.map { section ->
                ExploreSectionUi(
                    title = section.title,
                    kind = when (section) {
                        is ExploreSection.Curated -> SectionKind.CURATED
                        is ExploreSection.RecentlyDiscovered -> SectionKind.RECENTLY_DISCOVERED
                    },
                    feeds = section.feeds.map { feed ->
                        ExploreFeedUi(feed, normalizeSourceUrl(feed.url) in subs)
                    },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Flat category list for the subscribe picker. */
    val categories: StateFlow<List<CategoryOption>> = sourceRepository.observeTree()
        .map { tree -> tree.map { CategoryOption(it.category.id, it.category.name) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Whether a topic exists — the real gate for the "New folder" affordance. A topic
     * outlives its folders, so this stays true even when the user has deleted every
     * category (the tree would be empty, but folder creation must still work).
     */
    val hasTopic: StateFlow<Boolean> = sourceRepository.observeHasTopic()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _searchState = MutableStateFlow(SearchState.IDLE)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _rawSearchFeeds = MutableStateFlow<List<ExploreFeed>>(emptyList())

    /** Search results with per-feed subscribed flags resolved reactively — so a card flips
     *  to its "Added" state the moment its subscribe completes, matching the browse rails. */
    val searchResults: StateFlow<List<ExploreFeedUi>> =
        combine(_rawSearchFeeds, subscribedUrls) { feeds, subs ->
            feeds.map { feed -> ExploreFeedUi(feed, normalizeSourceUrl(feed.url) in subs) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private val _subscribeResult = MutableStateFlow<SubscribeResult?>(null)
    val subscribeResult: StateFlow<SubscribeResult?> = _subscribeResult.asStateFlow()

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    fun search(query: String) {
        if (query.isBlank()) return
        _searchState.value = SearchState.LOADING
        viewModelScope.launch {
            when (val outcome = searchFeeds(query)) {
                is LlmOutcome.Err -> {
                    _searchError.value = (outcome.error as LlmError).userMessage()
                    _searchState.value = if (outcome.error is LlmError.Empty) SearchState.EMPTY else SearchState.ERROR
                }
                is LlmOutcome.Ok -> {
                    val known = subscribedUrls.value + catalogUrls.value
                    val feeds = outcome.value
                        // Drop dupes of an already-subscribed or already-shown rail feed;
                        // the user can act on those from the browse view directly.
                        .filter { normalizeSourceUrl(it.url) !in known }
                        .distinctBy { normalizeSourceUrl(it.url) }
                        .map { feed ->
                            ExploreFeed(
                                title = feed.title,
                                url = feed.url,
                                kind = parseSourceKind(feed.kind),
                                description = feed.description,
                                language = null,
                            )
                        }
                    _rawSearchFeeds.value = feeds
                    _searchState.value = if (feeds.isEmpty()) SearchState.EMPTY else SearchState.RESULTS
                }
            }
        }
    }

    fun clearSearch() {
        _searchState.value = SearchState.IDLE
        _rawSearchFeeds.value = emptyList()
        _searchError.value = null
    }

    fun subscribe(feed: ExploreFeed, categoryId: String, folderLabel: String) {
        viewModelScope.launch {
            when (sourceRepository.addSource(categoryId, feed.url, feed.title, feed.kind)) {
                is Outcome.Ok -> _subscribeResult.value = SubscribeResult.Added(folderLabel)
                is Outcome.Conflict -> _subscribeResult.value = SubscribeResult.Conflict
            }
        }
    }

    /**
     * Create a new folder under the current topic and subscribe [feed] into it in one step.
     * Used by the category picker's "New folder" affordance. The topic id is read from the
     * topic table (not inferred from the first category) so this works even when no folders
     * exist yet — the picker gates the affordance on [hasTopic], not on the category list.
     */
    fun subscribeIntoNewFolder(feed: ExploreFeed, folderName: String) {
        val trimmed = folderName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val topicId = sourceRepository.currentTopicId() ?: return@launch
            val categoryId = sourceRepository.addCategory(topicId, trimmed)
            when (sourceRepository.addSource(categoryId, feed.url, feed.title, feed.kind)) {
                is Outcome.Ok -> _subscribeResult.value = SubscribeResult.Added(trimmed)
                is Outcome.Conflict -> _subscribeResult.value = SubscribeResult.Conflict
            }
        }
    }

    fun consumeSubscribeResult() { _subscribeResult.value = null }

    /**
     * Fetch a feed live and expose its most recent items for a "peek before subscribe" view.
     * Idempotent re-open for the same feed is a no-op; switching to a new feed reloads.
     */
    fun preview(feed: ExploreFeed) {
        val current = _previewState.value
        if (current is PreviewState.Loading && current.feed.url == feed.url) return
        if (current is PreviewState.Loaded && current.feed.url == feed.url) return
        _previewState.value = PreviewState.Loading(feed)
        viewModelScope.launch {
            _previewState.value = when (val result = feedPreview.preview(feed.url, feed.kind)) {
                is FeedPreviewResult.Loaded -> PreviewState.Loaded(feed, result.items)
                FeedPreviewResult.Empty -> PreviewState.Empty(feed)
                FeedPreviewResult.Failed -> PreviewState.Failed(feed)
            }
        }
    }

    fun clearPreview() { _previewState.value = PreviewState.Idle }
}
