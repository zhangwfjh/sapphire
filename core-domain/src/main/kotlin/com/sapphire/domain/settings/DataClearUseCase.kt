package com.sapphire.domain.settings

/** Granular local-data clearing. Each returns the count of rows removed (clearAll is total). */
interface DataClearUseCase {
    suspend fun clearFeedItems(): Int
    suspend fun clearReaderCache(): Int
    suspend fun clearSaved(): Int
    suspend fun clearAll()
    /** Total on-device database file size in bytes. */
    suspend fun storageUsageBytes(): Long
    suspend fun breakdown(): DataBreakdown
}

data class DataBreakdown(
    val feedItems: Int,
    val readerCache: Int,
    val savedItems: Int,
    val totalBytes: Long,
    val feedItemsBytes: Long,
    val readerCacheBytes: Long,
    val savedItemsBytes: Long,
)
