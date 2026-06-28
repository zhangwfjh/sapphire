package com.sapphire.domain.explore

import com.sapphire.domain.model.SourceKind

/**
 * One recent item in a feed preview — enough to judge a feed before subscribing.
 */
data class FeedPreviewItem(
    val title: String,
    val summary: String?,
)

/**
 * Outcome of previewing a feed live: the most recent items so the reader can decide
 * whether to subscribe, a fallback when the feed is unreachable, or empty when it parses
 * but has nothing to show.
 */
sealed interface FeedPreviewResult {
    data class Loaded(val items: List<FeedPreviewItem>) : FeedPreviewResult
    data object Empty : FeedPreviewResult
    data object Failed : FeedPreviewResult
}

/**
 * Fetches a feed on-demand and returns its most recent items for a "peek before you
 * subscribe" view (Explore §preview). Pure-domain contract; the fetcher dispatch lives in
 * core-data. [SourceKind]s without a fetcher (e.g. RSSHUB, AGENT_*) resolve to [Failed].
 */
interface FeedPreview {
    suspend fun preview(url: String, kind: SourceKind): FeedPreviewResult
}
