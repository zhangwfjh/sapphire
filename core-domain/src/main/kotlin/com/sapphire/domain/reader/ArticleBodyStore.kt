package com.sapphire.domain.reader

/**
 * Side cache for extracted article bodies, keyed by FeedItem hash. A hit makes reader
 * re-opens free (no re-fetch, no re-extract). Decoupled from [ArticleExtractor] so the
 * extractor is testable without Room.
 */
interface ArticleBodyStore {
    suspend fun get(itemId: String): List<String>?
    suspend fun put(itemId: String, paragraphs: List<String>)
}
