package com.sapphire.domain.model

enum class AgentFrequency {
    HOURLY_1, HOURLY_2, HOURLY_4, HOURLY_6, HOURLY_8, HOURLY_12,
    DAILY, WEEKDAY, WEEKLY;
    val isHourly: Boolean get() = name.startsWith("HOURLY_")
    val intervalHours: Int? get() = if (isHourly) name.removePrefix("HOURLY_").toInt() else null
}

enum class AgentRunStatus { OK, FAILED, EMPTY }

data class AgentJob(
    val id: String,
    val name: String,
    val goal: String,
    val task: String,
    val format: String,
    val rules: String,
    val maxItems: Int,
    val frequency: AgentFrequency,
    val triggerTime: String,
    /** Drawer folder this agent's source + filed items live in; null = shared "Agents" folder. */
    val categoryId: String? = null,
    val enabled: Boolean,
    val nextRunIntentEpochMs: Long?,
    val createdAt: Long,
) {
    val directive: String
        get() = com.sapphire.domain.agent.DirectiveAssembler.assemble(goal, task, format, rules, maxItems)
}

data class AgentRun(
    val id: String,
    val jobId: String,
    val status: AgentRunStatus,
    val itemsFiled: Int,
    val tokensUsed: Int,
    val ranAt: Long,
    val message: String?,
)
