package com.sapphire.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S07 retention scheduler. Enqueues [RetentionWorker] as a daily periodic work with a
 * KEEP policy — re-calling on every app start is idempotent and never duplicates the job.
 *
 * Architecture §7: network-not-required (local DELETE). The 24h interval is WorkManager's
 * effective cadence; the OS may defer it under doze, which is acceptable for a purge whose
 * cutoff is derived at run time, not at schedule time.
 */
@Singleton
class RetentionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(
            RETENTION_INTERVAL_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "sapphire-retention-purge"
        const val RETENTION_INTERVAL_HOURS = 24L
    }
}
