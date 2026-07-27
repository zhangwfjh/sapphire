package com.sapphire.data.agent

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sapphire.domain.model.AgentFrequency
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-job WorkManager scheduling for prompt agents. Each enabled agent gets a unique
 * periodic work named `sapphire-agent-<jobId>` with CONNECTED constraints.
 *
 * **REPLACE** on re-schedule (not KEEP): cadence/frequency edits must update the interval.
 * [RetentionScheduler] uses KEEP because its interval is fixed; agents are user-editable.
 *
 * Hourly cadences use the interval as the period. Scheduled cadences (daily/weekday/weekly)
 * use a 24h period (WorkManager's finest periodic granularity) — the worker itself is a
 * no-op if the day-of-week doesn't match. This is the pragmatic tradeoff: WorkManager
 * periodic work can't express "every weekday at 7am" directly, so the worker gates on
 * wall-clock + the cadence-note in the UI already warns "≈ intent".
 */
@Singleton
class AgentScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private fun workName(jobId: String) = AgentRunnerWorker.UNIQUE_WORK_PREFIX + jobId

    /**
     * Schedule (or re-schedule) a job's periodic work. REPLACE so cadence edits take effect.
     * No-op if the job is disabled — call [cancel] instead.
     */
    fun schedule(jobId: String, frequency: AgentFrequency, triggerTime: String) {
        val intervalHours = periodHours(frequency)
        val request = PeriodicWorkRequestBuilder<AgentRunnerWorker>(
            intervalHours, TimeUnit.HOURS,
        )
            .setInputData(Data.Builder().putString(AgentRunnerWorker.INPUT_JOB_ID, jobId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(initialDelayMinutes(frequency, triggerTime), TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workName(jobId),
            ExistingPeriodicWorkPolicy.UPDATE, // UPDATE replaces + preserves next-run timing if possible
            request,
        )
    }

    /** Cancel a job's periodic work (on pause/delete). */
    fun cancel(jobId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(jobId))
    }

    /** Enqueue an immediate one-time run (the detail "Run now" button). */
    fun runNow(jobId: String) {
        val request = OneTimeWorkRequestBuilder<AgentRunnerWorker>()
            .setInputData(Data.Builder().putString(AgentRunnerWorker.INPUT_JOB_ID, jobId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(jobId) + "-now",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** WorkManager periodic minimum is 15 minutes; hourly = N hours; scheduled = 24h. */
    private fun periodHours(frequency: AgentFrequency): Long = when {
        frequency.isHourly -> frequency.intervalHours?.toLong() ?: 24L
        else -> 24L
    }

    /** Rough initial delay so the first run lands near the trigger time. */
    private fun initialDelayMinutes(frequency: AgentFrequency, triggerTime: String): Long {
        if (frequency.isHourly) return 15L // ASAP for interval cadences
        val (h, m) = triggerTime.split(":").let { it[0].toInt() to (it.getOrNull(1)?.toInt() ?: 0) }
        val now = java.util.Calendar.getInstance()
        val target = (java.util.Calendar.getInstance()).apply {
            set(java.util.Calendar.HOUR_OF_DAY, h)
            set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) target.add(java.util.Calendar.DAY_OF_MONTH, 1)
        val delayMin = (target.timeInMillis - now.timeInMillis) / 60_000L
        return delayMin.coerceAtLeast(1L)
    }
}
