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
 * dedup guard: `INSERT OR IGNORE` drops a re-fetched duplicate without throwing (global
 * hash identity). Hash-only dedup at this layer; semantic embedding dedup is separate.
 *
 * **Timeline bound.** Every `observe*` query is capped at [TIMELINE_LIMIT] rows (newest
 * first). Only READ items are retention-purged; UNREAD items never expire, so without a
 * bound an active user's timeline grows without limit and re-materializes in full on every
 * single-row change. The limit is generous (a typical mobile feed ceiling); full paging is
 * a future enhancement.
 */
@Dao
interface FeedDao {

    /** Unified timeline: all sources, newest first. Emits on any change. */
    @Query("""
        SELECT * FROM feed_item
        ORDER BY COALESCE(published_at, fetched_at) DESC, fetched_at DESC
        LIMIT $TIMELINE_LIMIT
    """)
    fun observeTimeline(): Flow<List<FeedItemEntity>>

    /** Folder view: one category, newest first. */
    @Query("""
        SELECT * FROM feed_item
        WHERE category_id = :categoryId
        ORDER BY COALESCE(published_at, fetched_at) DESC, fetched_at DESC
        LIMIT $TIMELINE_LIMIT
    """)
    fun observeCategory(categoryId: String): Flow<List<FeedItemEntity>>

    /** Folder view over a set of categories (an L1 plus its L2 descendants), newest first. */
    @Query("""
        SELECT * FROM feed_item
        WHERE category_id IN (:categoryIds)
        ORDER BY COALESCE(published_at, fetched_at) DESC, fetched_at DESC
        LIMIT $TIMELINE_LIMIT
    """)
    fun observeCategories(categoryIds: List<String>): Flow<List<FeedItemEntity>>

    /** Items from one source (source-filter view), newest first. */
    @Query("""
        SELECT * FROM feed_item
        WHERE source_id = :sourceId
        ORDER BY COALESCE(published_at, fetched_at) DESC, fetched_at DESC
        LIMIT $TIMELINE_LIMIT
    """)
    fun observeBySource(sourceId: String): Flow<List<FeedItemEntity>>

    /** Items from a set of sources (virtual domain-group view), newest first. */
    @Query("""
        SELECT * FROM feed_item
        WHERE source_id IN (:sourceIds)
        ORDER BY COALESCE(published_at, fetched_at) DESC, fetched_at DESC
        LIMIT $TIMELINE_LIMIT
    """)
    fun observeBySources(sourceIds: List<String>): Flow<List<FeedItemEntity>>

    // ---- Source-joined variants (for dense cards that show source title) ----

    @Query("""
        SELECT f.*, s.title AS source_title
        FROM feed_item f LEFT JOIN source s ON f.source_id = s.id
        ORDER BY COALESCE(f.published_at, f.fetched_at) DESC, f.fetched_at DESC
        LIMIT $TIMELINE_LIMIT
    """)
    fun observeTimelineWithSource(): Flow<List<FeedItemWithSource>>

    @Query("""
        SELECT f.*, s.title AS source_title
        FROM feed_item f LEFT JOIN source s ON f.source_id = s.id
        WHERE f.category_id IN (:categoryIds)
        ORDER BY COALESCE(f.published_at, f.fetched_at) DESC, f.fetched_at DESC
        LIMIT $TIMELINE_LIMIT
    """)
    fun observeCategoriesWithSource(categoryIds: List<String>): Flow<List<FeedItemWithSource>>

    @Query("""
        SELECT f.*, s.title AS source_title
        FROM feed_item f LEFT JOIN source s ON f.source_id = s.id
        WHERE f.source_id = :sourceId
        ORDER BY COALESCE(f.published_at, f.fetched_at) DESC, f.fetched_at DESC
        LIMIT $TIMELINE_LIMIT
    """)
    fun observeBySourceWithSource(sourceId: String): Flow<List<FeedItemWithSource>>

    @Query("""
        SELECT f.*, s.title AS source_title
        FROM feed_item f LEFT JOIN source s ON f.source_id = s.id
        WHERE f.source_id IN (:sourceIds)
        ORDER BY COALESCE(f.published_at, f.fetched_at) DESC, f.fetched_at DESC
        LIMIT $TIMELINE_LIMIT
    """)
    fun observeBySourcesWithSource(sourceIds: List<String>): Flow<List<FeedItemWithSource>>

    @Query("SELECT COUNT(*) FROM feed_item WHERE read_state = 'UNREAD'")
    fun observeUnreadCountRaw(): Flow<Int>

    /** Cheap "is the timeline non-empty?" probe — avoids a full SELECT * just to derive a boolean. */
    @Query("SELECT EXISTS(SELECT 1 FROM feed_item)")
    fun observeHasAny(): Flow<Boolean>

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
     * no-op — this is the global hash-id dedup (the cheap layer; semantic embedding dedup
     * runs on top for the AGENT_SEARCH path only).
     *
     * @return rowids inserted (-1 rowids are conflicts that were ignored); caller uses the
     *   count to surface "N new" in the refresh UI.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<FeedItemEntity>): List<Long>

    @Query("SELECT hash_uuid FROM feed_item WHERE hash_uuid IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    /** Newest item URLs for one source — the agent re-run exclusion list. */
    @Query("SELECT url FROM feed_item WHERE source_id = :sourceId ORDER BY published_at DESC LIMIT :limit")
    suspend fun recentUrlsForSource(sourceId: String, limit: Int): List<String>

    @Query("UPDATE feed_item SET read_state = :state WHERE hash_uuid = :itemId")
    suspend fun setReadState(itemId: String, state: ReadState)

    @Query("UPDATE feed_item SET read_state = :state WHERE hash_uuid IN (:itemIds)")
    suspend fun setReadStateBatch(itemIds: List<String>, state: ReadState)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReadLog(entries: List<ReadLogEntity>)

    @Query("SELECT COUNT(*) FROM feed_item")
    suspend fun countItems(): Int

    /**
     * Single-query batched read-state probe: returns the subset of [ids] whose current
     * [state] matches. Used by the batch mutations below to avoid one `readStateOf` SELECT
     * per id (was N+1 inside a single transaction — O(N) round-trips holding the write lock).
     */
    @Query("SELECT hash_uuid FROM feed_item WHERE hash_uuid IN (:ids) AND read_state = :state")
    suspend fun idsInState(ids: List<String>, state: ReadState): List<String>

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
        if (itemIds.isEmpty()) return
        // One batched probe instead of one SELECT per id.
        val alreadyRead = idsInState(itemIds, ReadState.READ).toSet()
        val fresh = itemIds - alreadyRead
        if (fresh.isEmpty()) return
        setReadStateBatch(fresh, ReadState.READ)
        insertReadLog(fresh.map { ReadLogEntity(itemId = it, markedAt = now, mechanism = ReadMechanism.MANUAL) })
    }

    @Transaction
    suspend fun markUnreadBatch(itemIds: List<String>, now: Long) {
        if (itemIds.isEmpty()) return
        val toRevert = idsInState(itemIds, ReadState.READ)
        if (toRevert.isEmpty()) return
        setReadStateBatch(toRevert, ReadState.UNREAD)
        insertReadLog(toRevert.map { ReadLogEntity(itemId = it, markedAt = now, mechanism = ReadMechanism.MANUAL) })
    }

    @Transaction
    suspend fun undoBatch(itemIds: List<String>, now: Long) {
        if (itemIds.isEmpty()) return
        val toRevert = idsInState(itemIds, ReadState.READ)
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

    /** Reader: fetch a single item by PK for the reader sheet. */
    @Query("SELECT * FROM feed_item WHERE hash_uuid = :itemId")
    suspend fun itemById(itemId: String): FeedItemEntity?

    /** Persist the Tier-1 classification onto the row (macro source). */
    @Query("UPDATE feed_item SET classification = :classification WHERE hash_uuid = :itemId")
    suspend fun setClassification(itemId: String, classification: String)

    /** Flip the Save Later flag on an item (`[📁 Save Later]`). */
    @Query("UPDATE feed_item SET saved_later = :saved WHERE hash_uuid = :itemId")
    suspend fun setSavedLater(itemId: String, saved: Boolean)

    /**
     * Retention purge. Deletes items that are READ, not saved, and
     * fetched before [cutoff]. CASCADE sweeps `read_log` and `llm_cache`. Returns the row
     * count so the worker can log purge volume.
     *
     * The `(read_state, fetched_at)` index backs this query.
     */
    @Query("""
        DELETE FROM feed_item
        WHERE read_state = 'READ'
          AND saved_later = 0
          AND fetched_at < :cutoff
    """)
    suspend fun purgeOldRead(cutoff: Long): Int

    /** Clear every feed_item row. Returns rows deleted. CASCADE sweeps read_log/llm_cache/article_body/saved_item. */
    @Query("DELETE FROM feed_item")
    suspend fun deleteAllFeedItems(): Int
}

private const val TIMELINE_LIMIT = 1000
