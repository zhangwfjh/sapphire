package com.sapphire.data.reader

import com.sapphire.data.db.ArticleBodyDao
import com.sapphire.data.db.ArticleBodyEntity
import com.sapphire.domain.reader.ArticleBodyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Room-backed [ArticleBodyStore]. `body_html` holds the Readability-cleaned article HTML;
 * the DAO upserts with REPLACE so a re-extraction overwrites cleanly. Rows are PK-aligned
 * with their FeedItem hash and cascade-delete with it, so retention purge sweeps stale
 * bodies for free.
 */
class RoomArticleBodyStore @Inject constructor(
    private val dao: ArticleBodyDao,
) : ArticleBodyStore {

    override suspend fun get(itemId: String): String? = withContext(Dispatchers.IO) {
        dao.findByItem(itemId)?.bodyHtml
    }

    override suspend fun put(itemId: String, html: String) = withContext(Dispatchers.IO) {
        dao.upsert(
            ArticleBodyEntity(
                itemId = itemId,
                bodyHtml = html,
                fetchedAt = System.currentTimeMillis(),
            ),
        )
    }
}
