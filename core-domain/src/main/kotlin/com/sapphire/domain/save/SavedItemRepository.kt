package com.sapphire.domain.save

import com.sapphire.domain.model.SavedItem
import com.sapphire.domain.model.SavedItemDetails
import kotlinx.coroutines.flow.Flow

/**
 * S07 Save Later repository port (PRD §3.4 `[📁 Save Later]`). Room-backed impl in core-data.
 *
 * Promoting an item here flips `FeedItem.saved_later = 1` (architecture §7 — saved items
 * are exempt from the 30-day purge). The repository is the single owner of that flag flip;
 * callers go through [save] / [unsave].
 */
interface SavedItemRepository {

    /** Promote [itemId] into the repository with a folder + optional labels. Idempotent. */
    suspend fun save(itemId: String, folder: String, labels: Map<String, String> = emptyMap())

    /** Remove [itemId] from the repository; clears `FeedItem.saved_later`. Idempotent. */
    suspend fun unsave(itemId: String)

    /** Whether [itemId] is currently saved. */
    suspend fun isSaved(itemId: String): Boolean

    /** Live list of all saved items, newest-save first. */
    fun observeAll(): Flow<List<SavedItem>>

    /** Live count of saved items. */
    fun observeCount(): Flow<Int>

    /** Live list of saved items joined with display details, newest-save first. */
    fun observeDetails(): Flow<List<SavedItemDetails>>
}
