package com.sapphire.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentRecency
import com.sapphire.domain.model.AgentStyle
import com.sapphire.domain.model.OutputLanguage
import com.sapphire.domain.model.SearchTool
import kotlinx.coroutines.flow.Flow

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

    @Query(
        """
        UPDATE agent_job SET
            name = :name,
            directive = :directive,
            search_tool = :searchTool,
            frequency = :frequency,
            trigger_time = :triggerTime,
            max_items = :maxItems,
            recency = :recency,
            output_language = :outputLanguage,
            style = :style
        WHERE id = :id
        """,
    )
    suspend fun updateFields(
        id: String,
        name: String,
        directive: String,
        searchTool: SearchTool,
        frequency: AgentFrequency,
        triggerTime: String,
        maxItems: Int,
        recency: AgentRecency,
        outputLanguage: OutputLanguage,
        style: AgentStyle,
    )

    @Query("UPDATE agent_job SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM agent_job WHERE id = :id")
    suspend fun delete(id: String)
}

/** Run-history insert + observe. Observe is capped at 50 most-recent per job. */
@Dao
interface AgentRunDao {
    @Query("SELECT * FROM agent_run WHERE job_id = :jobId ORDER BY ran_at DESC LIMIT 50")
    fun observeForJob(jobId: String): Flow<List<AgentRunEntity>>

    @Insert
    suspend fun insert(entity: AgentRunEntity)
}
