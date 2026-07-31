package com.sapphire.domain.model

/**
 * Agent run frequency. The `HOURLY_*` variants are interval-based with a phase
 * offset (the trigger time is the *origin*, not a clock the run fires on); the
 * rest are scheduled cadences that fire at the trigger time.
 *
 * Stored via [com.sapphire.data.db.EnumTypeConverter] as `name()`. Adding new
 * hourly intervals later is safe — old rows decode by name with a default.
 */
enum class AgentFrequency {
    HOURLY_1, HOURLY_2, HOURLY_4, HOURLY_6, HOURLY_8, HOURLY_12,
    DAILY, WEEKDAY, WEEKLY;

    /** Interval cadences run every N hours; scheduled cadences do not. */
    val isHourly: Boolean get() = name.startsWith("HOURLY_")

    /** Hours between runs for `HOURLY_*`; null for scheduled cadences. */
    val intervalHours: Int? get() = if (isHourly) name.removePrefix("HOURLY_").toInt() else null
}

/** Recency window applied to the search step (maps to a search-API time filter in Slice B). */
enum class AgentRecency { H24, WEEK, MONTH, YEAR, ALL }

/** Voice / shape of the synthesized output — drives the system-prompt and the cost multiplier. */
enum class AgentStyle { BRIEF, BULLETED, CONVERSATIONAL, ACADEMIC, HOTTAKE, EXPLAINER }

/** Output language of the synthesis. MATCH_SOURCE follows each source's own language. */
enum class OutputLanguage { EN, ZH, MATCH_SOURCE }

/** Which search provider the agent uses. All are non-fatal (empty list on failure). */
enum class SearchTool { TAVILY, DDG }

/** Outcome of a single run — maps to the run-history dot states (ok / fail / empty). */
enum class AgentRunStatus { OK, FAILED, EMPTY }

/**
 * A persisted prompt-agent job (PRD §3.7). [nextRunIntentEpochMs] is advisory — Slice A
 * stores it (null until Slice B computes a real epoch); Slice B's scheduler acts on it.
 * Filed items link back to the job via a Source row created in Slice B.
 */
data class AgentJob(
    val id: String,
    val name: String,
    val directive: String,
    val searchTool: SearchTool,
    val frequency: AgentFrequency,
    val triggerTime: String,
    val maxItems: Int,
    val recency: AgentRecency,
    val outputLanguage: OutputLanguage,
    val style: AgentStyle,
    val enabled: Boolean,
    val nextRunIntentEpochMs: Long?,
    val createdAt: Long,
)

/** One executed run's record — the run-history timeline rows. */
data class AgentRun(
    val id: String,
    val jobId: String,
    val status: AgentRunStatus,
    val itemsFiled: Int,
    val tokensUsed: Int,
    val ranAt: Long,
    val message: String?,
)
