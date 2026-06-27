package com.sapphire.data.save

import com.sapphire.data.db.FeedDao
import com.sapphire.domain.save.RetentionPurge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Room-backed [RetentionPurge]. Delegates to [FeedDao.purgeOldRead]; the CASCADE on
 * `read_log` and `llm_cache` sweeps those rows automatically. The cutoff is supplied by
 * the caller (derived from [com.sapphire.domain.save.RetentionPolicy]).
 */
class RoomRetentionPurge @Inject constructor(
    private val feedDao: FeedDao,
) : RetentionPurge {

    override suspend fun purgeOlderThan(cutoffEpochMs: Long): Int = withContext(Dispatchers.IO) {
        feedDao.purgeOldRead(cutoffEpochMs)
    }
}
