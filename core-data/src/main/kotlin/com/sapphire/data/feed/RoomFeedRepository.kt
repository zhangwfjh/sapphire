package com.sapphire.data.feed

import com.sapphire.data.db.FeedDao
import com.sapphire.data.db.OnboardingDao
import com.sapphire.data.db.SourceEntity
import com.sapphire.domain.feed.FeedRepository
import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.model.HealthState
import com.sapphire.domain.model.ReadMechanism
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Room-backed [FeedRepository]. Timeline queries map entities → domain on the IO dispatcher.
 * Read-state mutations are transactional (see [FeedDao.markRead]); the `now` clock is the
 * real one — [ReadLogEntity] timestamps are advisory, not ordering-critical.
 */
class RoomFeedRepository @Inject constructor(
    private val feedDao: FeedDao,
) : FeedRepository {

    override fun observeTimeline(): Flow<List<FeedItem>> =
        feedDao.observeTimeline().map { rows -> rows.map { it.toDomain() } }

    override fun observeCategory(categoryId: String): Flow<List<FeedItem>> =
        feedDao.observeCategory(categoryId).map { rows -> rows.map { it.toDomain() } }

    override fun observeCategories(categoryIds: Set<String>): Flow<List<FeedItem>> =
        if (categoryIds.isEmpty()) {
            kotlinx.coroutines.flow.flowOf(emptyList())
        } else {
            feedDao.observeCategories(categoryIds.toList()).map { rows -> rows.map { it.toDomain() } }
        }

    override fun observeBySource(sourceId: String): Flow<List<FeedItem>> =
        feedDao.observeBySource(sourceId).map { rows -> rows.map { it.toDomain() } }

    override fun observeBySources(sourceIds: Set<String>): Flow<List<FeedItem>> =
        if (sourceIds.isEmpty()) {
            kotlinx.coroutines.flow.flowOf(emptyList())
        } else {
            feedDao.observeBySources(sourceIds.toList()).map { rows -> rows.map { it.toDomain() } }
        }

    override fun observeUnreadCount(): Flow<Int> = feedDao.observeUnreadCountRaw()

    override suspend fun markRead(itemId: String, mechanism: ReadMechanism) = withContext(Dispatchers.IO) {
        feedDao.markRead(itemId, mechanism, System.currentTimeMillis())
    }

    override suspend fun markUnread(itemId: String) = withContext(Dispatchers.IO) {
        feedDao.markUnread(itemId, System.currentTimeMillis())
    }

    override suspend fun undoMarkRead(itemIds: List<String>) = withContext(Dispatchers.IO) {
        if (itemIds.isNotEmpty()) feedDao.undoBatch(itemIds, System.currentTimeMillis())
    }

    override suspend fun markReadBatch(itemIds: Collection<String>) = withContext(Dispatchers.IO) {
        if (itemIds.isNotEmpty()) feedDao.markReadBatch(itemIds.toList(), System.currentTimeMillis())
    }

    override suspend fun markUnreadBatch(itemIds: Collection<String>) = withContext(Dispatchers.IO) {
        if (itemIds.isNotEmpty()) feedDao.markUnreadBatch(itemIds.toList(), System.currentTimeMillis())
    }

    override suspend fun markReadBySource(sourceId: String): List<String> = withContext(Dispatchers.IO) {
        val ids = feedDao.unreadIdsBySource(sourceId)
        if (ids.isNotEmpty()) feedDao.markReadBySource(sourceId, System.currentTimeMillis())
        ids
    }

    override suspend fun markReadBySources(sourceIds: Set<String>): List<String> = withContext(Dispatchers.IO) {
        if (sourceIds.isEmpty()) return@withContext emptyList()
        val ids = feedDao.unreadIdsBySources(sourceIds.toList())
        if (ids.isNotEmpty()) feedDao.markReadBySources(sourceIds.toList(), System.currentTimeMillis())
        ids
    }

    override suspend fun markReadByCategory(categoryId: String): List<String> = withContext(Dispatchers.IO) {
        val ids = feedDao.unreadIdsByCategory(categoryId)
        if (ids.isNotEmpty()) feedDao.markReadByCategory(categoryId, System.currentTimeMillis())
        ids
    }

    override suspend fun deleteItem(itemId: String) = withContext(Dispatchers.IO) {
        feedDao.deleteItem(itemId)
    }

    override suspend fun deleteItems(itemIds: Collection<String>) = withContext(Dispatchers.IO) {
        if (itemIds.isNotEmpty()) feedDao.deleteItems(itemIds.toList())
    }
}

/**
 * Read access for enabled sources, used by [FeedRefreshService] to drive ingestion.
 * Kept narrow (only what ingest needs) rather than exposing the whole DAO.
 */
interface SourceFeedQuery {
    suspend fun enabledSources(): List<SourceEntity>
    suspend fun updateSourceFetchState(id: String, health: HealthState, now: Long, errorAt: Long?)
}

/** Room-backed [SourceFeedQuery]. */
class RoomSourceFeedQuery @Inject constructor(
    private val onboardingDao: OnboardingDao,
) : SourceFeedQuery {

    override suspend fun enabledSources(): List<SourceEntity> = withContext(Dispatchers.IO) {
        onboardingDao.enabledSources()
    }

    override suspend fun updateSourceFetchState(
        id: String,
        health: HealthState,
        now: Long,
        errorAt: Long?,
    ) = withContext(Dispatchers.IO) {
        onboardingDao.updateFetchState(id, health, now, errorAt)
    }
}
