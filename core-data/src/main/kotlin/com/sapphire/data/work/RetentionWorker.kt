package com.sapphire.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sapphire.domain.settings.RetentionConfigStore
import com.sapphire.domain.save.RetentionPurge
import com.sapphire.domain.save.RetentionPolicy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Retention purge. Runs the rolling retention configured in Settings
 * ([RetentionConfigStore], default 30 days): deletes FeedItems that are READ, not saved,
 * and older than the cutoff. CASCADE sweeps their `read_log` and `llm_cache` rows.
 *
 * Scheduled daily by [RetentionScheduler]. Network-unconstrained (it's a local DELETE).
 * The cutoff is derived from the wall clock at run time via [RetentionPolicy];
 * WorkManager deferral only affects *when* this fires, not the rule.
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val purge: RetentionPurge,
    private val retentionConfig: RetentionConfigStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val days = retentionConfig.observe().first()
        val now = System.currentTimeMillis()
        val cutoff = RetentionPolicy.cutoffEpochMs(now, days)
        val purged = purge.purgeOlderThan(cutoff)
        // Output data is advisory — surfaced in WorkManager logs/observability, not UI.
        return if (purged >= 0) Result.success() else Result.failure()
    }
}
