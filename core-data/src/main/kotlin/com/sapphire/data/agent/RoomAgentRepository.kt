package com.sapphire.data.agent

import com.sapphire.data.db.AgentJobDao
import com.sapphire.data.db.AgentJobEntity
import com.sapphire.data.db.AgentRunDao
import com.sapphire.data.db.AgentRunEntity
import com.sapphire.domain.agent.AgentJobInput
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRun
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.util.IdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Room-backed [AgentRepository]. Entity↔domain mapping happens here at the boundary
 * (domain code never sees Room types, per the house rule). Writes dispatch IO;
 * Room read Flows are cold and self-dispatched.
 *
 * [update] routes through [AgentJobDao.updateFields] so `created_at`, `enabled`, and
 * `next_run_intent_epoch_ms` are preserved — a REPLACE upsert would clobber all three.
 * [create] seeds a synthetic "Agent created — waiting for first run" history row so the
 * detail view's timeline is never empty (matches the design's created-agent state).
 */
class RoomAgentRepository @Inject constructor(
    private val jobDao: AgentJobDao,
    private val runDao: AgentRunDao,
    private val ids: IdGenerator,
) : AgentRepository {

    override fun observeJobs(): Flow<List<AgentJob>> =
        jobDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeJob(id: String): Flow<AgentJob?> =
        jobDao.observeById(id).map { it?.toDomain() }

    override fun observeRuns(jobId: String): Flow<List<AgentRun>> =
        runDao.observeForJob(jobId).map { list -> list.map { it.toDomain() } }

    override suspend fun create(input: AgentJobInput): String = withContext(Dispatchers.IO) {
        val id = ids.uuid()
        val now = System.currentTimeMillis()
        jobDao.upsert(input.toEntity(id, now))
        runDao.insert(
            AgentRunEntity(
                id = ids.uuid(),
                jobId = id,
                status = AgentRunStatus.EMPTY,
                itemsFiled = 0,
                tokensUsed = 0,
                ranAt = now,
                message = "Agent created — waiting for first run",
            ),
        )
        id
    }

    override suspend fun update(id: String, input: AgentJobInput) = withContext(Dispatchers.IO) {
        jobDao.updateFields(
            id = id,
            name = input.name,
            directive = input.directive,
            searchTool = input.searchTool,
            frequency = input.frequency,
            triggerTime = input.triggerTime,
            modelTier = input.modelTier,
            recency = input.recency,
            outputLanguage = input.outputLanguage,
            style = input.style,
        )
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        jobDao.setEnabled(id, enabled)
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        jobDao.delete(id)
    }

    override suspend fun recordRun(
        jobId: String,
        status: AgentRunStatus,
        itemsFiled: Int,
        tokensUsed: Int,
        message: String?,
    ) = withContext(Dispatchers.IO) {
        runDao.insert(
            AgentRunEntity(
                id = ids.uuid(),
                jobId = jobId,
                status = status,
                itemsFiled = itemsFiled,
                tokensUsed = tokensUsed,
                ranAt = System.currentTimeMillis(),
                message = message,
            ),
        )
    }

    private fun AgentJobEntity.toDomain() = AgentJob(
        id = id,
        name = name,
        directive = directive,
        searchTool = searchTool,
        frequency = frequency,
        triggerTime = triggerTime,
        modelTier = modelTier,
        recency = recency,
        outputLanguage = outputLanguage,
        style = style,
        enabled = enabled,
        nextRunIntentEpochMs = nextRunIntentEpochMs,
        createdAt = createdAt,
    )

    private fun AgentRunEntity.toDomain() = AgentRun(
        id = id,
        jobId = jobId,
        status = status,
        itemsFiled = itemsFiled,
        tokensUsed = tokensUsed,
        ranAt = ranAt,
        message = message,
    )

    private fun AgentJobInput.toEntity(id: String, createdAt: Long) = AgentJobEntity(
        id = id,
        name = name,
        directive = directive,
        searchTool = searchTool,
        frequency = frequency,
        triggerTime = triggerTime,
        modelTier = modelTier,
        recency = recency,
        outputLanguage = outputLanguage,
        style = style,
        enabled = true,
        nextRunIntentEpochMs = null,
        createdAt = createdAt,
    )
}
