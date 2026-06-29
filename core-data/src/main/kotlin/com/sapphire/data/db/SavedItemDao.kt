package com.sapphire.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * S07 Save Later repository (PRD §3.4). Promotes a FeedItem to a SavedItem row and exposes
 * the saved set as a Flow. PK is the item hash, so re-saving the same item is REPLACE —
 * the caller can update folder/labels via the same insert.
 *
 * Rows are never purged by [RetentionPurge]; they only go away via the FeedItem CASCADE
 * (an explicit source/category delete), which is intentional.
 */
@Dao
interface SavedItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedItemEntity)

    @Query("DELETE FROM saved_item WHERE item_id = :itemId")
    suspend fun delete(itemId: String)

    /** Settings §3.3: clear every saved_item row. Returns rows deleted. */
    @Query("DELETE FROM saved_item")
    suspend fun deleteAll(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM saved_item WHERE item_id = :itemId)")
    suspend fun isSaved(itemId: String): Boolean

    @Query("SELECT * FROM saved_item ORDER BY saved_at DESC")
    fun observeAll(): Flow<List<SavedItemEntity>>

    /**
     * S07: saved items joined with their feed-item details for the Saved Later screen.
     * Newest-save first. Exposes the columns the list needs (title, author, publishedAt,
     * platformTag) so the UI can reuse the card affordances without a second lookup.
     */
    @Query("""
        SELECT s.item_id AS itemId, s.folder AS folder, s.labels_json AS labelsJson, s.saved_at AS savedAt,
               f.title AS title, f.author_handle AS authorHandle, f.published_at AS publishedAt,
               f.platform_tag AS platformTag, f.summary AS summary
        FROM saved_item s
        INNER JOIN feed_item f ON f.hash_uuid = s.item_id
        ORDER BY s.saved_at DESC
    """)
    fun observeSavedWithDetails(): Flow<List<SavedItemWithDetails>>

    @Query("SELECT COUNT(*) FROM saved_item")
    fun observeCount(): Flow<Int>
}
