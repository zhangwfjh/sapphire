package com.sapphire.domain.feed

import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.model.ReadMechanism
import kotlinx.coroutines.flow.Flow

/**
 * Persistence + query boundary for the unified timeline. Room-backed impl in core-data.
 *
 * The timeline is a single reverse-chronological [Flow] over all sources — the PRD §3.2
 * "unified" view. Folder-view filters the same query by categoryId.
 */
interface FeedRepository {

    /** Live unified timeline, newest first. Emits on any insert/read-state change. */
    fun observeTimeline(): Flow<List<FeedItem>>

    /** Live items for one category (folder view), newest first. */
    fun observeCategory(categoryId: String): Flow<List<FeedItem>>

    /** Live items across a set of categories, newest first. */
    fun observeCategories(categoryIds: Set<String>): Flow<List<FeedItem>>

    /** Live items from one source, newest first. */
    fun observeBySource(sourceId: String): Flow<List<FeedItem>>

    /** Live items from a set of sources (virtual domain-group view), newest first. */
    fun observeBySources(sourceIds: Set<String>): Flow<List<FeedItem>>
    /**
     * Mark [itemId] READ, recording the [mechanism] in ReadLog. Idempotent — re-marking a
     * READ item is a no-op (no duplicate ReadLog row).
     */
    suspend fun markRead(itemId: String, mechanism: ReadMechanism)

    /** Revert [itemId] to UNREAD (manual toggle / undo), logging MANUAL. Idempotent. */
    suspend fun markUnread(itemId: String)

    /** Batch-mark a set of items READ (manual). Idempotent. */
    suspend fun markReadBatch(itemIds: Collection<String>)

    /** Batch-revert a set of items to UNREAD (manual). Idempotent. */
    suspend fun markUnreadBatch(itemIds: Collection<String>)

    /**
     * Mark every item from [sourceId] READ (Sources drawer swipe-left "mark all as read").
     * Atomic and idempotent; returns the ids newly flipped to READ so the UI can offer undo.
     */
    suspend fun markReadBySource(sourceId: String): List<String>

    /**
     * Mark every item from any of [sourceIds] READ (virtual domain-group sweep). Atomic and
     * idempotent; returns the ids newly flipped to READ so the UI can offer undo.
     */
    suspend fun markReadBySources(sourceIds: Set<String>): List<String>

    /**
     * Mark every item in [categoryId] READ (folder-level sweep). Atomic and idempotent;
     * returns the ids newly flipped to READ so the UI can offer undo.
     */
    suspend fun markReadByCategory(categoryId: String): List<String>

    /**
     * Batch-revert a set of items to UNREAD. Used by the scroll-past undo snackbar
     * (PRD §3.3 "Undo" safety net) to restore a swept batch atomically.
     */
    suspend fun undoMarkRead(itemIds: List<String>)

    /** Permanently delete a single item (and its dependents via CASCADE). */
    suspend fun deleteItem(itemId: String)

    /** Permanently delete a batch of items. */
    suspend fun deleteItems(itemIds: Collection<String>)

    /** Count of unread items, for the app badge / empty-state copy. */
    fun observeUnreadCount(): Flow<Int>
}
