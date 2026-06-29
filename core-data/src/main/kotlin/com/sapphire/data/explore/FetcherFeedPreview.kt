package com.sapphire.data.explore

import com.sapphire.data.feed.FetcherRegistry
import com.sapphire.domain.explore.FeedPreview
import com.sapphire.domain.explore.FeedPreviewItem
import com.sapphire.domain.explore.FeedPreviewResult
import com.sapphire.domain.feed.FetchResult
import com.sapphire.domain.model.SourceKind
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FetcherRegistry-backed [FeedPreview]. Dispatches on [SourceKind] like the ingest path,
 * takes the most recent items by `publishedAt` (falling back to feed order), and surfaces
 * a [Failed] result for kinds with no fetcher (AGENT_*) so the UI can degrade
 * gracefully instead of hanging on an unsupported source.
 */
class FetcherFeedPreview @Inject constructor(
    private val fetchers: FetcherRegistry,
) : FeedPreview {

    override suspend fun preview(url: String, kind: SourceKind): FeedPreviewResult =
        withContext(Dispatchers.IO) {
            val fetcher = fetchers.forKind(kind) ?: return@withContext FeedPreviewResult.Failed
            when (val result = fetcher.fetch(url, null)) {
                is FetchResult.Success -> {
                    val items = result.items
                        .sortedByDescending { it.publishedAt ?: 0L }
                        .take(PREVIEW_COUNT)
                        .map { FeedPreviewItem(title = it.title, summary = it.summary) }
                    if (items.isEmpty()) FeedPreviewResult.Empty else FeedPreviewResult.Loaded(items)
                }
                is FetchResult.TransientError -> FeedPreviewResult.Failed
                is FetchResult.PersistentFailure -> FeedPreviewResult.Failed
            }
        }

    private companion object {
        const val PREVIEW_COUNT = 3
    }
}
