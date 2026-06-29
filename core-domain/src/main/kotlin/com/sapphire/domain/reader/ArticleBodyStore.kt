package com.sapphire.domain.reader

/**
 * Side cache for extracted article bodies, keyed by FeedItem hash. A hit makes reader
 * re-opens free (no re-fetch, no re-extract). Decoupled from [ArticleExtractor] so the
 * extractor is testable without Room.
 */
interface ArticleBodyStore {
    /** @return the cached extracted-body HTML, or `null` if this item has none yet (cache miss — not "item has no body"). */
    suspend fun get(itemId: String): String?

    /** Persists the extracted-body HTML, overwriting any prior entry for this item. */
    suspend fun put(itemId: String, html: String)
}
