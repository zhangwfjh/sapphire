package com.sapphire.data.reader

import com.sapphire.data.db.FeedDao
import com.sapphire.data.db.LlmCacheDao
import com.sapphire.data.db.LlmCacheEntity
import com.sapphire.data.feed.toDomain
import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.reader.ReaderItemStore
import com.sapphire.domain.reader.ReaderOpCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Room-backed [ReaderOpCache] (architecture §3 `LlmCache`). Stores payloads keyed by the
 * domain-derived [com.sapphire.domain.util.LlmCacheKey]; the domain owns key derivation
 * and decoding, this layer owns the row shape + FK to `feed_item` (cascade-deletes on
 * retention purge, S07). REPLACE on PK conflict so a re-computed payload overwrites cleanly.
 */
class RoomReaderOpCache @Inject constructor(
    private val dao: LlmCacheDao,
) : ReaderOpCache {

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        dao.payload(key)
    }

    override suspend fun put(itemId: String, key: String, op: String, payloadJson: String) =
        withContext(Dispatchers.IO) {
            dao.insert(
                LlmCacheEntity(
                    cacheKey = key,
                    itemId = itemId,
                    op = op,
                    payloadJson = payloadJson,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
}

/**
 * Room-backed [ReaderItemStore]. Single-item lookup + classification persistence.
 */
class RoomReaderItemStore @Inject constructor(
    private val feedDao: FeedDao,
) : ReaderItemStore {

    override suspend fun item(itemId: String): FeedItem? = withContext(Dispatchers.IO) {
        feedDao.itemById(itemId)?.toDomain()
    }

    override suspend fun setClassification(itemId: String, classification: String) =
        withContext(Dispatchers.IO) {
            feedDao.setClassification(itemId, classification)
        }
}
