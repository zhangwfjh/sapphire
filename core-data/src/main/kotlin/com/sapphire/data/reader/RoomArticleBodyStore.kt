package com.sapphire.data.reader

import com.sapphire.data.db.ArticleBodyDao
import com.sapphire.data.db.ArticleBodyEntity
import com.sapphire.domain.reader.ArticleBodyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Room-backed [ArticleBodyStore]. `paragraphs_json` holds a JSON array of strings;
 * the DAO upserts with REPLACE so a re-extraction overwrites cleanly. Rows are PK-aligned
 * with their FeedItem hash and cascade-delete with it, so retention purge sweeps stale
 * bodies for free.
 */
class RoomArticleBodyStore @Inject constructor(
    private val dao: ArticleBodyDao,
) : ArticleBodyStore {

    private val json = Json
    private val listSerializer = ListSerializer(String.serializer())

    override suspend fun get(itemId: String): List<String>? = withContext(Dispatchers.IO) {
        dao.findByItem(itemId)?.let { json.decodeFromString(listSerializer, it.paragraphsJson) }
    }

    override suspend fun put(itemId: String, paragraphs: List<String>) = withContext(Dispatchers.IO) {
        dao.upsert(
            ArticleBodyEntity(
                itemId = itemId,
                paragraphsJson = json.encodeToString(listSerializer, paragraphs),
                fetchedAt = System.currentTimeMillis(),
            ),
        )
    }
}
