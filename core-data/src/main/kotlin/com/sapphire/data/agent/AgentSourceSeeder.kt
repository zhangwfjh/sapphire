package com.sapphire.data.agent

import com.sapphire.data.db.CategoryEntity
import com.sapphire.data.db.SeedDao
import com.sapphire.data.db.SourceDao
import com.sapphire.data.db.SourceEntity
import com.sapphire.data.db.TopicEntity
import com.sapphire.domain.model.HealthState
import com.sapphire.domain.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Idempotently creates the FK chain an agent's filed items need: a shared "Agents"
 * topic, and one [SourceEntity] per job (kind=AGENT_PROMPT) placed in the user-chosen
 * drawer folder ([categoryId]) — or the shared "Agents" category when null. The sourceId
 * is deterministic (`agent:<jobId>`) so callers reconstruct it without a DB lookup.
 *
 * All inserts use IGNORE; calling twice for the same job is a no-op. [moveAgentSource]
 * re-parents the source when the user changes folders; filed items follow via the
 * source join. On job delete, the source is removed by [SourceDao.deleteSource] —
 * CASCADE sweeps its feed items.
 */
@Singleton
class AgentSourceSeeder @Inject constructor(
    private val seedDao: SeedDao,
    private val sourceDao: SourceDao,
) {

    /**
     * Ensure the agent topic, the destination category, and this job's source all exist.
     * `categoryId = null` seeds/uses the shared "Agents" category.
     */
    suspend fun ensureAgentSource(jobId: String, jobName: String, categoryId: String? = null) {
        seedDao.insertTopicIgnore(
            TopicEntity(id = AGENT_TOPIC_ID, phrase = AGENT_TOPIC_PHRASE, createdAt = 0L),
        )
        val resolvedCategoryId = categoryId ?: AGENT_CATEGORY_ID
        if (categoryId == null) {
            seedDao.insertCategoriesIgnore(
                listOf(
                    CategoryEntity(
                        id = AGENT_CATEGORY_ID,
                        topicId = AGENT_TOPIC_ID,
                        level = 1,
                        parentId = null,
                        name = AGENT_CATEGORY_NAME,
                        sortOrder = 999, // agents sort last in the drawer
                    ),
                ),
            )
        }
        seedDao.insertSources(
            listOf(
                SourceEntity(
                    id = sourceIdFor(jobId),
                    categoryId = resolvedCategoryId,
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

    /** Re-parent this job's source to a different drawer folder (null = shared "Agents"). */
    suspend fun moveAgentSource(jobId: String, categoryId: String?) {
        val title = sourceDao.sourcesByIds(listOf(sourceIdFor(jobId))).firstOrNull()?.title ?: "Agent"
        ensureAgentSource(jobId, title, categoryId)
        sourceDao.moveSource(sourceIdFor(jobId), categoryId ?: AGENT_CATEGORY_ID)
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
