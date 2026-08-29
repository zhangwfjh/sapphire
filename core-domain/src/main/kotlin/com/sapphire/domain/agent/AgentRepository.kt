package com.sapphire.domain.agent

import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRun
import com.sapphire.domain.model.AgentRunStatus
import kotlinx.coroutines.flow.Flow

/**
 * Params for create/update — the full builder form. One object so the builder VM
 * and the repository agree on the exact field set; adding a control means changing
 * this type + the entity, and the compiler drives the rest.
 */
data class AgentJobInput(
    val name: String,
    val goal: String,
    val task: String,
    val format: String,
    val rules: String,
    val maxItems: Int,
    val frequency: AgentFrequency,
    val triggerTime: String,
    /** Drawer folder for the agent's source; null = shared "Agents" folder. */
    val categoryId: String? = null,
)

/** Aggregated run history for one job — the hub card + quick-panel numbers. */
data class AgentJobStats(val itemsFiled: Int, val totalRuns: Int, val tokensUsed: Int)

/** Totals across all jobs — the hub's hero strip. */
data class AgentTotals(val itemsFiled: Int, val totalRuns: Int)

/**
 * CRUD + observe for agent jobs and their run history. Reads are cold
 * [Flow]s so Compose recomposes only on real change; writes are suspend and
 * dispatch IO at the implementation boundary. Mirrors the [com.sapphire.domain.source.SourceRepository] shape.
 *
 * Run execution itself lives in [AgentRunService]; this port is persistence only.
 */
interface AgentRepository {
    fun observeJobs(): Flow<List<AgentJob>>
    fun observeJob(id: String): Flow<AgentJob?>
    fun observeRuns(jobId: String): Flow<List<AgentRun>>

    /** Per-job aggregates keyed by jobId — derived from run history. */
    fun observeJobStats(): Flow<Map<String, AgentJobStats>>

    /** Cross-agent totals for the hub hero strip. */
    fun observeTotals(): Flow<AgentTotals>

    /** Most recent run per job, keyed by jobId — status pills and "last run" rows. */
    fun observeLastRuns(): Flow<Map<String, AgentRun>>

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

    /**
     * URLs this agent's source has already filed, newest first (the exclusion list the
     * loop gets so re-runs pick different content).
     */
    suspend fun recentlyFiledUrls(jobId: String, limit: Int = 20): List<String>
}
