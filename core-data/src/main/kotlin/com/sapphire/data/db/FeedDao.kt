package com.sapphire.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sapphire.domain.model.ReadMechanism
import com.sapphire.domain.model.ReadState
import kotlinx.coroutines.flow.Flow

/**
 * Timeline + read-state mutations for [FeedItemEntity]. The PK `hash_uuid` is the ingest
 * dedup guard: `INSERT OR IGNORE` drops a re-fetched duplicate without throwing (PRD §3.2
 * global hash identity). S02 dedup is hash-only; semantic embedding dedup lands in S04.
 */
@Dao
interface FeedDao {

    /** Unified timeline (PRD §3.2): all sources, newest first. Emits on any change. */
    @Query("""
        SELECT * FROM feed_item
        ORDER BY COALESCE(published_at, fetched_at) DESC, fetched_at DESC
    """)
    fun observeTimeline(): Flow<List<FeedItemEntity>>

    /** Folder view (§3.2): one category, newest first. */
    @Query("""
        SELECT * FROM feed_item
        WHERE category_id = :categoryId
        ORDER BY COALESCE(published_at, fetched_at) DESC, fetched_at DESC
    """)
    fun observeCategory(categoryId: String): Flow<List<FeedItemEntity>>

    /** Folder view over a set of categories (an L1 plus its L2 descendants), newest first. */
    @Query("""
        SELECT * FROM feed_item
        WHERE category_id IN (:categoryIds)
        ORDER BY COALESCE(published_at, fetched_at) DESC, fetched_at DESC
    """)
    fun observeCategories(categoryIds: List<String>): Flow<List<FeedItemEntity>>

    /** Items from one source (source-filter view), newest first. */
    @Query("""
        SELECT * FROM feed_item
        WHERE source_id = :sourceId
        ORDER BY COALESCE(published_at, fetched_at) DESC, fetched_at DESC
    """)
    fun observeBySource(sourceId: String): Flow<List<FeedItemEntity>>

    /** Items from a set of sources (virtual domain-group view), newest first. */
    @Query("""
        SELECT * FROM feed_item
        WHERE source_id IN (:sourceIds)
        ORDER BY COALESCE(published_at, fetched_at) DESC, fetched_at DESC
    """)
    fun observeBySources(sourceIds: List<String>): Flow<List<FeedItemEntity>>

    @Query("SELECT COUNT(*) FROM feed_item WHERE read_state = 'UNREAD'")
    fun observeUnreadCountRaw(): Flow<Int>
    /** Per-category item counts for the sources tree. */
    @Query("""
        SELECT category_id AS categoryId,
               SUM(CASE WHEN read_state = 'UNREAD' THEN 1 ELSE 0 END) AS unread,
               COUNT(*) AS total
        FROM feed_item
        GROUP BY category_id
    """)
    fun observeCategoryCounts(): Flow<List<CategoryCount>>

    /** Per-source item counts for the sources tree. */
    @Query("""
        SELECT source_id AS sourceId,
               SUM(CASE WHEN read_state = 'UNREAD' THEN 1 ELSE 0 END) AS unread,
               COUNT(*) AS total
        FROM feed_item
        GROUP BY source_id
    """)
    fun observeSourceCounts(): Flow<List<SourceCount>>

    /**
     * Ingest insert. IGNORE on PK conflict: the same item re-fetched from any source is a
     * no-op — this is the global hash-id dedup PRD §3.2 requires (cheap layer; S04 adds
     * semantic embedding dedup on top for the AGENT_SEARCH path only).
     *
     * @return rowids inserted (-1 rowids are conflicts that were ignored); caller uses the
     *   count to surface "N new" in the refresh UI.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<FeedItemEntity>): List<Long>

    @Query("SELECT hash_uuid FROM feed_item WHERE hash_uuid IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    @Query("UPDATE feed_item SET read_state = :state WHERE hash_uuid = :itemId")
    suspend fun setReadState(itemId: String, state: ReadState)

    @Query("UPDATE feed_item SET read_state = :state WHERE hash_uuid IN (:itemIds)")
    suspend fun setReadStateBatch(itemIds: List<String>, state: ReadState)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReadLog(entries: List<ReadLogEntity>)

    @Query("SELECT COUNT(*) FROM feed_item")
    suspend fun countItems(): Int

    /**
     * Mark [itemId] READ and append a [ReadLogEntity] row in one transaction. Idempotent:
     * re-marking a READ item just sets the same state; the ReadLog IGNORE PK guard isn't
     * applicable (autoGenerate) so we gate the insert on the prior state to avoid dup rows.
     */
    @Transaction
    suspend fun markRead(itemId: String, mechanism: ReadMechanism, now: Long) {
        val prior = readStateOf(itemId)
        setReadState(itemId, ReadState.READ)
        if (prior != ReadState.READ) {
            insertReadLog(listOf(ReadLogEntity(itemId = itemId, markedAt = now, mechanism = mechanism)))
        }
    }

    @Transaction
    suspend fun markUnread(itemId: String, now: Long) {
        val prior = readStateOf(itemId)
        setReadState(itemId, ReadState.UNREAD)
        if (prior == ReadState.READ) {
            insertReadLog(listOf(ReadLogEntity(itemId = itemId, markedAt = now, mechanism = ReadMechanism.MANUAL)))
        }
    }

    @Transaction
    suspend fun markReadBatch(itemIds: List<String>, now: Long) {
        val fresh = itemIds.filter { readStateOf(it) != ReadState.READ }
        if (fresh.isEmpty()) return
        setReadStateBatch(fresh, ReadState.READ)
        insertReadLog(fresh.map { ReadLogEntity(itemId = it, markedAt = now, mechanism = ReadMechanism.MANUAL) })
    }

    @Transaction
    suspend fun markUnreadBatch(itemIds: List<String>, now: Long) {
        val toRevert = itemIds.filter { readStateOf(it) == ReadState.READ }
        if (toRevert.isEmpty()) return
        setReadStateBatch(toRevert, ReadState.UNREAD)
        insertReadLog(toRevert.map { ReadLogEntity(itemId = it, markedAt = now, mechanism = ReadMechanism.MANUAL) })
    }

    @Transaction
    suspend fun undoBatch(itemIds: List<String>, now: Long) {
        val toRevert = itemIds.filter { readStateOf(it) == ReadState.READ }
        if (toRevert.isEmpty()) return
        setReadStateBatch(toRevert, ReadState.UNREAD)
        insertReadLog(toRevert.map { ReadLogEntity(itemId = it, markedAt = now, mechanism = ReadMechanism.MANUAL) })
    }

    @Query("DELETE FROM feed_item WHERE hash_uuid IN (:itemIds)")
    suspend fun deleteItems(itemIds: List<String>)

    /**
     * Mark every item from [sourceId] READ in one pass (Sources drawer swipe-left). Items
     * already READ are skipped; the rest flip and get a ReadLog row each. Transactional so
     * the drawer's "mark all as read" is atomic.
     */
    @Transaction
    suspend fun markReadBySource(sourceId: String, now: Long) {
        val fresh = unreadIdsBySource(sourceId)
        if (fresh.isEmpty()) return
        setReadStateBatch(fresh, ReadState.READ)
        insertReadLog(fresh.map { ReadLogEntity(itemId = it, markedAt = now, mechanism = ReadMechanism.MANUAL) })
    }

    /**
     * Mark every item in [categoryId] READ (folder-level sweep). Same atomic log-append
     * contract as [markReadBySource].
     */
    @Transaction
    suspend fun markReadByCategory(categoryId: String, now: Long) {
        val fresh = unreadIdsByCategory(categoryId)
        if (fresh.isEmpty()) return
        setReadStateBatch(fresh, ReadState.READ)
        insertReadLog(fresh.map { ReadLogEntity(itemId = it, markedAt = now, mechanism = ReadMechanism.MANUAL) })
    }

    @Query("SELECT hash_uuid FROM feed_item WHERE source_id = :sourceId AND read_state = 'UNREAD'")
    suspend fun unreadIdsBySource(sourceId: String): List<String>

    @Query("SELECT hash_uuid FROM feed_item WHERE category_id = :categoryId AND read_state = 'UNREAD'")
    suspend fun unreadIdsByCategory(categoryId: String): List<String>

    @Query("SELECT hash_uuid FROM feed_item WHERE source_id IN (:sourceIds) AND read_state = 'UNREAD'")
    suspend fun unreadIdsBySources(sourceIds: List<String>): List<String>

    /**
     * Mark every item from any of [sourceIds] READ (virtual domain-group sweep). Same
     * atomic log-append contract as [markReadBySource].
     */
    @Transaction
    suspend fun markReadBySources(sourceIds: List<String>, now: Long) {
        val fresh = unreadIdsBySources(sourceIds)
        if (fresh.isEmpty()) return
        setReadStateBatch(fresh, ReadState.READ)
        insertReadLog(fresh.map { ReadLogEntity(itemId = it, markedAt = now, mechanism = ReadMechanism.MANUAL) })
    }

    @Query("DELETE FROM feed_item WHERE hash_uuid = :itemId")
    suspend fun deleteItem(itemId: String)

    @Query("SELECT read_state FROM feed_item WHERE hash_uuid = :itemId")
    suspend fun readStateOf(itemId: String): ReadState?

    /** S03 reader: fetch a single item by PK for the reader sheet. */
    @Query("SELECT * FROM feed_item WHERE hash_uuid = :itemId")
    suspend fun itemById(itemId: String): FeedItemEntity?

    /** S03 reader: persist the Tier-1 classification onto the row (PRD §3.5 macro source). */
    @Query("UPDATE feed_item SET classification = :classification WHERE hash_uuid = :itemId")
    suspend fun setClassification(itemId: String, classification: String)
    /** S07 reader: flip the Save Later flag on an item (PRD §3.4 [📁 Save Later]). */
    @Query("UPDATE feed_item SET saved_later = :saved WHERE hash_uuid = :itemId")
    suspend fun setSavedLater(itemId: String, saved: Boolean)

    /**
     * S07 retention purge (architecture §7). Deletes items that are READ, not saved, and
     * fetched before [cutoff]. CASCADE sweeps `read_log` and `llm_cache`. Returns the row
     * count so the worker can log purge volume.
     *
     * The `(read_state, fetched_at)` index was added in S02 for this query.
     */
    @Query("""
        DELETE FROM feed_item
        WHERE read_state = 'READ'
          AND saved_later = 0
          AND fetched_at < :cutoff
    """)
    suspend fun purgeOldRead(cutoff: Long): Int
}

