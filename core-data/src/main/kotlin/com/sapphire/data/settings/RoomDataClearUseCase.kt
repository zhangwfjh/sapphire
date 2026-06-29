package com.sapphire.data.settings

import com.sapphire.data.db.ArticleBodyDao
import com.sapphire.data.db.FeedDao
import com.sapphire.data.db.LlmCacheDao
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.data.db.SavedItemDao
import com.sapphire.domain.settings.DataClearUseCase
import com.sapphire.domain.settings.DataBreakdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Room-backed [DataClearUseCase] (PRD §3.3 data-clear controls). Each granular clear runs
 * on the IO dispatcher and returns the deleted-row count; `clearAll` delegates to
 * [RoomDatabase.clearAllTables], which wipes every entity table atomically. The
 * [com.sapphire.data.db.SeedDefaultFeedsCallback] re-seeds baseline sources on the next
 * app open when the source table is empty, so default feeds return automatically — this
 * use-case does not re-seed inline.
 */
class RoomDataClearUseCase @Inject constructor(
    private val feedDao: FeedDao,
    private val llmCacheDao: LlmCacheDao,
    private val articleBodyDao: ArticleBodyDao,
    private val savedItemDao: SavedItemDao,
    private val database: SapphireDatabase,
) : DataClearUseCase {

    override suspend fun clearFeedItems(): Int =
        withContext(Dispatchers.IO) { feedDao.deleteAllFeedItems() }

    override suspend fun clearReaderCache(): Int = withContext(Dispatchers.IO) {
        val caches = llmCacheDao.deleteAll()
        val bodies = articleBodyDao.deleteAll()
        caches + bodies
    }

    override suspend fun clearSaved(): Int =
        withContext(Dispatchers.IO) { savedItemDao.deleteAll() }

    override suspend fun clearAll() =
        withContext(Dispatchers.IO) { database.clearAllTables() }

    override suspend fun storageUsageBytes(): Long = withContext(Dispatchers.IO) {
        val path = database.openHelper.readableDatabase.path
        java.io.File(path).length()
    }

    override suspend fun breakdown(): DataBreakdown = withContext(Dispatchers.IO) {
        val perTable = tableBytesViaDbstat()
        DataBreakdown(
            feedItems = feedDao.countItems(),
            readerCache = llmCacheDao.count() + articleBodyDao.count(),
            savedItems = savedItemDao.count(),
            totalBytes = storageUsageBytes(),
            feedItemsBytes = perTable["feed_item"] ?: 0L,
            readerCacheBytes = (perTable["article_body"] ?: 0L) + (perTable["llm_cache"] ?: 0L),
            savedItemsBytes = perTable["saved_item"] ?: 0L,
        )
    }

    /**
     * Per-table byte sizes via the SQLite `dbstat` virtual table (each table + its indexes,
     * grouped by base table via sqlite_master). Returns an empty map if dbstat is unavailable
     * on the device's SQLite build — callers degrade to 0-byte category stats.
     */
    private fun tableBytesViaDbstat(): Map<String, Long> {
        return runCatching {
            database.openHelper.readableDatabase.query(
                "SELECT s.tbl_name AS tbl, SUM(d.pgsize) AS bytes " +
                    "FROM dbstat d JOIN sqlite_master s ON d.name = s.name GROUP BY s.tbl_name",
            ).use { c ->
                buildMap {
                    while (c.moveToNext()) put(c.getString(0), c.getLong(1))
                }
            }
        }.getOrDefault(emptyMap())
    }
}
