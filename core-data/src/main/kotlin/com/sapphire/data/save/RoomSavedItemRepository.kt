package com.sapphire.data.save

import androidx.room.withTransaction
import com.sapphire.data.db.FeedDao
import com.sapphire.data.db.SavedItemDao
import com.sapphire.data.db.SavedItemEntity
import com.sapphire.data.db.SavedItemWithDetails
import com.sapphire.domain.model.SavedItem
import com.sapphire.domain.model.SavedItemDetails
import com.sapphire.domain.save.SavedItemRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Room-backed [SavedItemRepository]. Save/unsave is transactional across the two tables:
 * the `saved_item` row and the `feed_item.saved_later` flag flip together, so the purge
 * query (which gates on `saved_later = 0`) and the saved-items list can never disagree.
 *
 * `labels` is encoded to JSON via [SavedItemLabelsCodec]; the domain layer owns the map
 * shape, this layer owns the storage encoding.
 */
class RoomSavedItemRepository @Inject constructor(
    private val db: com.sapphire.data.db.SapphireDatabase,
    private val savedItemDao: SavedItemDao,
    private val feedDao: FeedDao,
) : SavedItemRepository {

    override suspend fun save(itemId: String, folder: String, labels: Map<String, String>) =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                savedItemDao.upsert(
                    SavedItemEntity(
                        itemId = itemId,
                        folder = folder,
                        labelsJson = SavedItemLabelsCodec.encode(labels),
                        savedAt = System.currentTimeMillis(),
                    ),
                )
                feedDao.setSavedLater(itemId, saved = true)
            }
        }

    override suspend fun unsave(itemId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            savedItemDao.delete(itemId)
            feedDao.setSavedLater(itemId, saved = false)
        }
    }

    override suspend fun isSaved(itemId: String): Boolean = withContext(Dispatchers.IO) {
        savedItemDao.isSaved(itemId)
    }

    override fun observeAll(): Flow<List<SavedItem>> =
        savedItemDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeCount(): Flow<Int> = savedItemDao.observeCount()

    override fun observeDetails(): Flow<List<SavedItemDetails>> =
        savedItemDao.observeSavedWithDetails().map { rows -> rows.map { it.toDetails() } }
}

internal fun SavedItemWithDetails.toDetails(): SavedItemDetails = SavedItemDetails(
    itemId = itemId,
    folder = folder,
    labels = SavedItemLabelsCodec.decode(labelsJson),
    savedAt = savedAt,
    title = title,
    authorHandle = authorHandle,
    publishedAt = publishedAt,
    platformTag = platformTag,
    summary = summary,
)
internal fun SavedItemEntity.toDomain(): SavedItem = SavedItem(
    itemId = itemId,
    folder = folder,
    labels = SavedItemLabelsCodec.decode(labelsJson),
    savedAt = savedAt,
)
