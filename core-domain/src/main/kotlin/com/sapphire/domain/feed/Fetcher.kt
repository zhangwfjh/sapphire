package com.sapphire.domain.feed

/**
 * A normalized, pre-dedup feed item emitted by a [Fetcher]. The ingestion layer resolves
 * [hashUuid], assigns `categoryId`, stamps `fetchedAt`, and inserts it. This type carries
 * only what a fetcher can know at fetch time.
 *
 * `canonicalUrl` is the identity-bearing permalink; when null the ingest path falls back to
 * `(title + publishedAt)` for hashing (see [com.sapphire.domain.util.FeedItemId]).
 */
data class FeedItemCandidate(
    val title: String,
    val summary: String?,
    val canonicalUrl: String?,
    val authorHandle: String?,
    val publishedAt: Long?,
    val platformTag: String?,
    val mediaUrl: String?,
    val bodyRaw: String? = null,
)

/** Outcome of a single source fetch. */
sealed interface FetchResult {
    /** Successful fetch; [items] may be empty if the feed legitimately had nothing new. */
    data class Success(val items: List<FeedItemCandidate>) : FetchResult
    /** Transient failure (network, 5xx). Caller should leave healthState as-is and retry. */
    data class TransientError(val message: String) : FetchResult
    /** Persistent failure (410 Gone, route rot). Caller should flip healthState to FAILED. */
    data class PersistentFailure(val message: String) : FetchResult
}

/**
 * Fetches a feed source and normalizes to [FeedItemCandidate]s. Implementations:
 * RSS/Atom (`RssAtomFetcher`), JSON Feed (`JsonFeedFetcher`), RSSHub (`RsshubFetcher` — S06),
 * web-search agent (`WebSearchFetcher` — S04).
 *
 * Dispatched on [com.sapphire.domain.model.Source.kind]. Pure-domain contract; the HTTP +
 * parsing impls live in core-data.
 */
interface Fetcher {
    suspend fun fetch(url: String, configJson: String?): FetchResult
}
