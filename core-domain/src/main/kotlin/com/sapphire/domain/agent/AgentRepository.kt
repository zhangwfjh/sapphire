package com.sapphire.domain.agent

import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRecency
import com.sapphire.domain.model.AgentRun
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.model.AgentStyle
import com.sapphire.domain.model.OutputLanguage
import kotlinx.coroutines.flow.Flow

/**
 * Params for create/update — the full builder form. One object so the builder VM
 * and the repository agree on the exact field set; adding a control means changing
 * this type + the entity, and the compiler drives the rest.
 */
data class AgentJobInput(
    val name: String,
    val directive: String,
    val frequency: AgentFrequency,
    val triggerTime: String,
    val maxItems: Int,
    val recency: AgentRecency,
    val outputLanguage: OutputLanguage,
    val style: AgentStyle,
)

/**
 * CRUD + observe for prompt-agent jobs and their run history. Reads are cold
 * [Flow]s so Compose recomposes only on real change; writes are suspend and
 * dispatch IO at the implementation boundary. Mirrors the [com.sapphire.domain.source.SourceRepository] shape.
 *
 * Slice A persists + observes only; [recordRun] is called by the builder on
 * create (seeds an "Agent created" history row) and by Slice B's worker on each run.
 */
interface AgentRepository {
    fun observeJobs(): Flow<List<AgentJob>>
    fun observeJob(id: String): Flow<AgentJob?>
    fun observeRuns(jobId: String): Flow<List<AgentRun>>
    suspend fun create(input: AgentJobInput): String
    suspend fun update(id: String, input: AgentJobInput)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun delete(id: String)
    suspend fun recordRun(
        jobId: String,
        status: AgentRunStatus,
        itemsFiled: Int,
        tokensUsed: Int,
        message: String?,
    )
    suspend fun fileAgentItems(jobId: String, items: List<AgentSynthesisItem>, agentName: String): Int
}
