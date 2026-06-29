package com.sapphire.data.settings

import com.sapphire.data.db.ArticleBodyDao
import com.sapphire.data.db.FeedDao
import com.sapphire.data.db.LlmCacheDao
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.data.db.SavedItemDao
import com.sapphire.domain.settings.DataClearUseCase
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
}
