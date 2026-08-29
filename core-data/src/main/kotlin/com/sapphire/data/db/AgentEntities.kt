package com.sapphire.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentRunStatus

@Entity(tableName = "agent_job", indices = [Index(value = ["name"])])
data class AgentJobEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "goal") val goal: String,
    @ColumnInfo(name = "task") val task: String,
    @ColumnInfo(name = "format") val format: String,
    @ColumnInfo(name = "rules") val rules: String,
    @ColumnInfo(name = "max_items") val maxItems: Int,
    @ColumnInfo(name = "frequency") val frequency: AgentFrequency,
    @ColumnInfo(name = "trigger_time") val triggerTime: String,
    /** Drawer folder for this agent's source; null = shared "Agents" folder. */
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "next_run_intent_epoch_ms") val nextRunIntentEpochMs: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "agent_run", foreignKeys = [ForeignKey(entity = AgentJobEntity::class, parentColumns = ["id"], childColumns = ["job_id"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["job_id"])])
data class AgentRunEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "status") val status: AgentRunStatus,
    @ColumnInfo(name = "items_filed") val itemsFiled: Int,
    @ColumnInfo(name = "tokens_used") val tokensUsed: Int,
    @ColumnInfo(name = "ran_at") val ranAt: Long,
    @ColumnInfo(name = "message") val message: String? = null,
)
