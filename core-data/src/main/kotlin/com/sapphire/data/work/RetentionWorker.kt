package com.sapphire.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sapphire.domain.save.RetentionPurge
import com.sapphire.domain.save.RetentionPolicy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * S07 retention purge (architecture §7 / PRD §4.3). Runs the 30-day rolling retention:
 * deletes FeedItems that are READ, not saved, and older than the cutoff. CASCADE sweeps
 * their `read_log` and `llm_cache` rows.
 *
 * Scheduled daily by [RetentionScheduler]. Network-unconstrained (it's a local DELETE) —
 * matches the architecture decision. The cutoff is derived from the wall clock at run time
 * via [RetentionPolicy]; WorkManager deferral only affects *when* this fires, not the rule.
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val purge: RetentionPurge,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val cutoff = RetentionPolicy.cutoffEpochMs(now)
        val purged = purge.purgeOlderThan(cutoff)
        // Output data is advisory — surfaced in WorkManager logs/observability, not UI.
        return if (purged >= 0) Result.success() else Result.failure()
    }
}
