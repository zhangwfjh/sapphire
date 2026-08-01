package com.sapphire.data.agent

import com.sapphire.data.db.AgentJobDao
import com.sapphire.data.db.AgentJobEntity
import com.sapphire.data.db.AgentRunDao
import com.sapphire.data.db.AgentRunEntity
import com.sapphire.data.db.FeedDao
import com.sapphire.domain.agent.AgentJobInput
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.agent.AgentSynthesisItem
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRun
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.util.IdGenerator
import com.sapphire.domain.util.FeedItemId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class RoomAgentRepository @Inject constructor(
    private val jobDao: AgentJobDao,
    private val runDao: AgentRunDao,
    private val feedDao: FeedDao,
    private val ids: IdGenerator,
    private val sourceSeeder: AgentSourceSeeder,
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
        // Ensure the FK source exists so the worker can file items.
        sourceSeeder.ensureAgentSource(id, input.name)
        id
    }

    override suspend fun update(id: String, input: AgentJobInput) = withContext(Dispatchers.IO) {
        jobDao.updateFields(
            id = id,
            name = input.name,
            directive = input.directive,
            frequency = input.frequency,
            triggerTime = input.triggerTime,
            maxItems = input.maxItems,
            recency = input.recency,
            outputLanguage = input.outputLanguage,
            style = input.style,
        )
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        jobDao.setEnabled(id, enabled)
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        sourceSeeder.removeAgentSource(id)
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

    override suspend fun fileAgentItems(jobId: String, items: List<AgentSynthesisItem>, agentName: String): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val sourceId = sourceSeeder.sourceIdFor(jobId)
        val entities = items.mapIndexed { i, item ->
            val itemUrl = item.url ?: item.sources.firstOrNull()?.url ?: "agent://$jobId#$now-$i"
            val hashUuid = FeedItemId.fromUrl(sourceId, itemUrl)
            val bodyWithSources = buildString {
                item.body?.let { appendLine(it); appendLine() }
                if (item.sources.isNotEmpty()) {
                    appendLine("---")
                    appendLine("Sources:")
                    item.sources.forEach { src -> appendLine("• ${src.title}: ${src.url}") }
                }
            }.ifBlank { null }
            com.sapphire.data.db.FeedItemEntity(
                hashUuid = hashUuid,
                sourceId = sourceId,
                categoryId = AgentSourceSeeder.AGENT_CATEGORY_ID,
                title = item.title,
                summary = item.summary,
                bodyRaw = bodyWithSources,
                publishedAt = now,
                fetchedAt = now,
                agentTag = agentName,
                url = itemUrl,
            )
        }
        val rowIds = feedDao.insertItems(entities)
        rowIds.count { it > 0 }
    }

    private fun AgentJobEntity.toDomain() = AgentJob(
        id = id,
        name = name,
        directive = directive,
        frequency = frequency,
        triggerTime = triggerTime,
        maxItems = maxItems,
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
        frequency = frequency,
        triggerTime = triggerTime,
        maxItems = maxItems,
        recency = recency,
        outputLanguage = outputLanguage,
        style = style,
        enabled = true,
        nextRunIntentEpochMs = null,
        createdAt = createdAt,
    )
}
