package com.sapphire.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sapphire.domain.model.AgentFrequency
import kotlinx.coroutines.flow.Flow

/** Aggregate projection row for [AgentJobDao.observeJobStats]. */
data class JobStatsRow(
    val jobId: String,
    val itemsFiled: Int,
    val totalRuns: Int,
    val tokensUsed: Int,
)

/**
 * Agent job CRUD. Newest-first observe matches the list's "unshift on create" order.
 * [updateFields] is the edit path: a targeted UPDATE that preserves `created_at`,
 * `enabled`, and `next_run_intent_epoch_ms` (REPLACE would clobber all three).
 */
@Dao
interface AgentJobDao {
    @Query("SELECT * FROM agent_job ORDER BY created_at DESC")
    fun observeAll(): Flow<List<AgentJobEntity>>

    @Query("SELECT * FROM agent_job WHERE id = :id")
    fun observeById(id: String): Flow<AgentJobEntity?>

    @Query("SELECT * FROM agent_job WHERE id = :id")
    suspend fun getById(id: String): AgentJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentJobEntity)

    @Query("""
        UPDATE agent_job SET
            name = :name,
            goal = :goal,
            task = :task,
            format = :format,
            rules = :rules,
            max_items = :maxItems,
            frequency = :frequency,
            trigger_time = :triggerTime,
            category_id = :categoryId
        WHERE id = :id
        """)
    suspend fun updateFields(
        id: String,
        name: String,
        goal: String,
        task: String,
        format: String,
        rules: String,
        maxItems: Int,
        frequency: AgentFrequency,
        triggerTime: String,
        categoryId: String?,
    )

    @Query("UPDATE agent_job SET category_id = :categoryId WHERE id = :id")
    suspend fun setCategory(id: String, categoryId: String?)

    @Query("UPDATE agent_job SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM agent_job WHERE id = :id")
    suspend fun delete(id: String)

    /** Per-job aggregates from run history — the hub/panel numbers. */
    @Query("""
        SELECT job_id AS jobId,
               COALESCE(SUM(items_filed), 0) AS itemsFiled,
               COUNT(*) AS totalRuns,
               COALESCE(SUM(tokens_used), 0) AS tokensUsed
        FROM agent_run GROUP BY job_id
    """)
    fun observeJobStats(): Flow<List<JobStatsRow>>

    /** Most recent run per job — status pills and "last run" rows. */
    @Query("""
        SELECT r.* FROM agent_run r
        INNER JOIN (SELECT job_id, MAX(ran_at) AS mx FROM agent_run GROUP BY job_id) latest
        ON r.job_id = latest.job_id AND r.ran_at = latest.mx
    """)
    fun observeLastRuns(): Flow<List<AgentRunEntity>>
}

/** Run-history insert + observe. Observe is capped at 50 most-recent per job. */
@Dao
interface AgentRunDao {
    @Query("SELECT * FROM agent_run WHERE job_id = :jobId ORDER BY ran_at DESC LIMIT 50")
    fun observeForJob(jobId: String): Flow<List<AgentRunEntity>>

    @Insert
    suspend fun insert(entity: AgentRunEntity)
}
