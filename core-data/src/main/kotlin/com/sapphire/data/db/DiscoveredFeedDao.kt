package com.sapphire.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Explore discovered pool DAO. Upsert keyed by the url unique index (entity PK id is a
 * hash of the normalized URL). incrementSubscribeCount is the re-subscribe path.
 */
@Dao
interface DiscoveredFeedDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DiscoveredFeedEntity)

    @Query("UPDATE discovered_feed SET subscribe_count = subscribe_count + 1 WHERE id = :id")
    suspend fun incrementSubscribeCount(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM discovered_feed WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("SELECT * FROM discovered_feed ORDER BY subscribe_count DESC, discovered_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiscoveredFeedEntity>>

}