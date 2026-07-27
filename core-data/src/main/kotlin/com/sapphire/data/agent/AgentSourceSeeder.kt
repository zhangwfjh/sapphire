package com.sapphire.data.agent

import com.sapphire.data.db.SeedDao
import com.sapphire.data.db.SourceDao
import com.sapphire.data.db.SourceEntity
import com.sapphire.domain.model.HealthState
import com.sapphire.domain.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Idempotently creates the FK chain an agent's filed items need: a shared "Agents"
 * topic + category, and one [SourceEntity] per job (kind=AGENT_PROMPT). The sourceId
 * is deterministic (`agent:<jobId>`) so the worker reconstructs it without a DB lookup.
 *
 * Called on job create. On job delete, the source is removed by [SourceDao.deleteSource]
 * — CASCADE sweeps its feed items. The shared topic/category outlive individual jobs.
 */
@Singleton
class AgentSourceSeeder @Inject constructor(
    private val seedDao: SeedDao,
    private val sourceDao: SourceDao,
) {

    /**
     * Ensure the agent topic, agent category, and this job's source all exist.
     * All inserts use IGNORE — calling this twice for the same job is a no-op.
     */
    suspend fun ensureAgentSource(jobId: String, jobName: String) {
        // Shared topic + category — created once, reused by all agents.
        seedDao.insertTopicIgnore(
            com.sapphire.data.db.TopicEntity(
                id = AGENT_TOPIC_ID,
                phrase = AGENT_TOPIC_PHRASE,
                createdAt = 0L,
            ),
        )
        seedDao.insertCategoriesIgnore(
            listOf(
                com.sapphire.data.db.CategoryEntity(
                    id = AGENT_CATEGORY_ID,
                    topicId = AGENT_TOPIC_ID,
                    level = 1,
                    parentId = null,
                    name = AGENT_CATEGORY_NAME,
                    sortOrder = 999, // agents sort last in the drawer
                ),
            ),
        )
        // Per-job source — the FK parent for this agent's filed items.
        seedDao.insertSources(
            listOf(
                SourceEntity(
                    id = sourceIdFor(jobId),
                    categoryId = AGENT_CATEGORY_ID,
                    topicId = AGENT_TOPIC_ID,
                    kind = SourceKind.AGENT_PROMPT,
                    url = "agent://$jobId",
                    title = jobName,
                    configJson = null,
                    healthState = HealthState.OK,
                    lastFetchedAt = null,
                    lastErrorAt = null,
                ),
            ),
        )
    }

    /** Remove this job's source; CASCADE sweeps its feed items. */
    suspend fun removeAgentSource(jobId: String) {
        sourceDao.deleteSource(sourceIdFor(jobId))
    }

    /** Deterministic sourceId for a job — `agent:<jobId>`. Worker uses the same convention. */
    fun sourceIdFor(jobId: String): String = "agent:$jobId"

    companion object {
        const val AGENT_TOPIC_ID = "agent-topic"
        const val AGENT_TOPIC_PHRASE = "Agents"
        const val AGENT_CATEGORY_ID = "agent-cat"
        const val AGENT_CATEGORY_NAME = "Agents"
    }
}
