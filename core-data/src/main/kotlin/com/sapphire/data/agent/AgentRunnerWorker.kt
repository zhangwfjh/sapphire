package com.sapphire.data.agent

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sapphire.data.db.AgentJobEntity
import com.sapphire.data.db.AgentRunEntity
import com.sapphire.data.db.FeedDao
import com.sapphire.data.db.FeedItemEntity
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.agent.AgentSynthesisService
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.util.FeedItemId
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID

/**
 * Executes one agent run: search → synthesize → file items → record run history.
 *
 * Input: `INPUT_JOB_ID` in WorkData. Loads the job, runs [AgentSynthesisService], then
 * builds [FeedItemEntity] rows with `agentTag` set (the ONLY path that sets it — the
 * shared [com.sapphire.data.feed.FeedRefreshService] never touches agentTag). Items
 * attach to the job's source (`agent:<jobId>`) so FK + cascade work.
 *
 * Error mapping: transient LLM errors (Timeout/RateLimited/Network) → [Result.retry];
 * permanent ones (NotConfigured/InvalidResponse) → [Result.failure]. Search failure is
 * non-fatal (the synth service degrades to knowledge-only).
 *
 * Scheduled by [AgentScheduler] as periodic work (per-job) or enqueued one-time for "Run now".
 */
@HiltWorker
class AgentRunnerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val database: SapphireDatabase,
    private val repository: AgentRepository,
    private val synthesis: AgentSynthesisService,
    private val sourceSeeder: AgentSourceSeeder,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(INPUT_JOB_ID) ?: run {
            android.util.Log.e("AgentRunner", "doWork: no jobId in input data")
            return Result.failure()
        }
        android.util.Log.i("AgentRunner", "doWork START for job=$jobId")
        val jobEntity = database.agentJobDao().getById(jobId) ?: return Result.success()
        val job = jobEntity.toDomainJob()

        // Run the pipeline.
        when (val outcome = synthesis.run(job)) {
            is LlmOutcome.Ok -> {
                val items = outcome.value.items
                if (items.isEmpty()) {
                    repository.recordRun(jobId, AgentRunStatus.EMPTY, 0, 0, "No items worth filing")
                    android.util.Log.i("AgentRunner", "doWork: empty items from synth")
                    return Result.success()
                }
                val now = System.currentTimeMillis()
                val sourceId = sourceSeeder.sourceIdFor(jobId)
                val entities = items.mapIndexed { i, item ->
                    val itemUrl = item.url
                        ?: item.sources.firstOrNull()?.url
                        ?: "agent://$jobId#$now-$i"
                    val hashUuid = FeedItemId.fromUrl(sourceId, itemUrl)
                    // Build body with appended Sources section so the reader shows
                    // clickable references the user can follow and verify.
                    val bodyWithSources = buildString {
                        item.body?.let { appendLine(it); appendLine() }
                        if (item.sources.isNotEmpty()) {
                            appendLine("---")
                            appendLine("Sources:")
                            item.sources.forEach { src -> appendLine("• ${src.title}: ${src.url}") }
                        }
                    }.ifBlank { null }
                    FeedItemEntity(
                        hashUuid = hashUuid,
                        sourceId = sourceId,
                        categoryId = AgentSourceSeeder.AGENT_CATEGORY_ID,
                        title = item.title,
                        summary = item.summary,
                        bodyRaw = bodyWithSources,
                        publishedAt = now,
                        fetchedAt = now,
                        agentTag = job.name,
                        url = itemUrl,
                    )
                }
                val rowIds = database.feedDao().insertItems(entities)
                val filed = rowIds.count { it > 0 }
                repository.recordRun(
                    jobId,
                    if (filed > 0) AgentRunStatus.OK else AgentRunStatus.EMPTY,
                    filed,
                    items.size * 1200, // approx tokens (Tier-1 base × items)
                    if (filed < items.size) "$filed/${items.size} items (some deduped)" else "$filed items filed",
                )
                android.util.Log.i("AgentRunner", "doWork DONE: filed=$filed/${items.size} items")
                return Result.success()
            }
            is LlmOutcome.Err -> {
                val err = outcome.error
                repository.recordRun(jobId, AgentRunStatus.FAILED, 0, 0, err.userMessage())
                android.util.Log.e("AgentRunner", "doWork FAILED: ${err.userMessage()}")
                return when (err) {
                    is LlmError.Timeout, is LlmError.RateLimited, is LlmError.Network -> Result.retry()
                    else -> Result.failure()
                }
            }
        }
    }

    /** Minimal mapper — the full mapper lives in RoomAgentRepository but the worker
     *  needs the domain type for AgentSynthesisService without a repo round-trip. */
    private fun AgentJobEntity.toDomainJob() = com.sapphire.domain.model.AgentJob(
        id = id,
        name = name,
        directive = directive,
        searchTool = searchTool,
        frequency = frequency,
        triggerTime = triggerTime,
        recency = recency,
        outputLanguage = outputLanguage,
        style = style,
        enabled = enabled,
        nextRunIntentEpochMs = nextRunIntentEpochMs,
        createdAt = createdAt,
    )

    companion object {
        const val INPUT_JOB_ID = "jobId"
        const val UNIQUE_WORK_PREFIX = "sapphire-agent-"
    }
}
