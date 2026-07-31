package com.sapphire.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentRecency
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.model.AgentStyle
import com.sapphire.domain.model.OutputLanguage
import com.sapphire.domain.model.SearchTool

/**
 * A persisted prompt-agent job (PRD §3.7). Top-level config row — no foreign keys:
 * the job outlives any source/category it later files items into (Slice B creates a
 * kind=AGENT_PROMPT source per job). `next_run_intent_epoch_ms` is advisory until
 * Slice B's scheduler acts on it.
 *
 * Mirrors the [DiscoveredFeedEntity] pattern: own file, name()-encoded enums via
 * [EnumTypeConverter], snake_case columns. `created_at` is preserved across edits
 * by the dedicated `updateFields` query (REPLACE would clobber it).
 */
@Entity(
    tableName = "agent_job",
    indices = [Index(value = ["name"])],
)
data class AgentJobEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "directive") val directive: String,
    @ColumnInfo(name = "search_tool") val searchTool: SearchTool,
    @ColumnInfo(name = "frequency") val frequency: AgentFrequency,
    @ColumnInfo(name = "trigger_time") val triggerTime: String,
    @ColumnInfo(name = "max_items") val maxItems: Int,
    @ColumnInfo(name = "recency") val recency: AgentRecency,
    @ColumnInfo(name = "output_language") val outputLanguage: OutputLanguage,
    @ColumnInfo(name = "style") val style: AgentStyle,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "next_run_intent_epoch_ms") val nextRunIntentEpochMs: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * One executed run's record (the run-history timeline). Cascade-deletes with its
 * parent job so deleting an agent sweeps its history. Capped at the 50 most recent
 * per job by the observe query.
 */
@Entity(
    tableName = "agent_run",
    foreignKeys = [
        ForeignKey(
            entity = AgentJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["job_id"])],
)
data class AgentRunEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "status") val status: AgentRunStatus,
    @ColumnInfo(name = "items_filed") val itemsFiled: Int,
    @ColumnInfo(name = "tokens_used") val tokensUsed: Int,
    @ColumnInfo(name = "ran_at") val ranAt: Long,
    @ColumnInfo(name = "message") val message: String? = null,
)
