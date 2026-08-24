package com.sapphire.data.agent

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.agent.AgentRunService
import com.sapphire.domain.agent.AgentRunService.RunMode
import com.sapphire.domain.llm.LlmError
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * WorkManager adapter for one scheduled agent run: load the job through the repository,
 * hand it to [AgentRunService] (which files items and records history), and map the
 * outcome's error to WorkManager semantics.
 *
 * Error mapping: transient LLM errors (Timeout/RateLimited/Network) → [Result.retry];
 * permanent ones (NotConfigured/InvalidResponse/…) → [Result.failure].
 *
 * Scheduled by [AgentScheduler] as periodic work (per-job).
 */
@HiltWorker
class AgentRunnerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: AgentRepository,
    private val runService: AgentRunService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(INPUT_JOB_ID) ?: return Result.failure()
        val job = repository.observeJob(jobId).first() ?: return Result.success()
        val outcome = runService.run(job, RunMode.FILE)
        return when (outcome.error) {
            is LlmError.Timeout, is LlmError.RateLimited, is LlmError.Network -> Result.retry()
            null -> Result.success()
            else -> Result.failure()
        }
    }

    companion object {
        const val INPUT_JOB_ID = "jobId"
        const val UNIQUE_WORK_PREFIX = "sapphire-agent-"
    }
}
