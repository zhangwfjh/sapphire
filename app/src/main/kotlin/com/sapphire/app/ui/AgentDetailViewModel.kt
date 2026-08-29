package com.sapphire.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.data.agent.AgentScheduler
import com.sapphire.domain.agent.AgentJobInput
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.agent.AgentRunService
import com.sapphire.domain.agent.AgentRunService.RunMode
import com.sapphire.domain.agent.AgentSynthesisItem
import com.sapphire.domain.agent.cadenceLabel
import com.sapphire.domain.agent.nextRunText
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRun
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.source.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Run-history row rendered for the timeline. */
data class RunRow(
    val status: String,   // OK / FAILED / EMPTY
    val title: String,    // run.message or synthesized
    val whenLabel: String,
    val meta: String,
)

/** One row result shown inline after a Run-now (items, timing, error). */
data class AgentRunResult(
    val success: Boolean,
    val durationMs: Long,
    val itemCount: Int,
    val items: List<String>,
    val error: String?,
)

/** A folder the agent can be moved into; null categoryId = the shared ✦ Agents folder. */
data class AgentFolderOption(val categoryId: String?, val label: String)

/** Detail-screen state — job + its derived display fields + real run stats. */
data class AgentDetailUi(
    val job: AgentJob?,
    val status: AgentStatusUi,
    val cadenceLabel: String,
    val goalLabel: String,
    val nextRun: String,
    val folderLabel: String,
    val runs: List<RunRow>,
    val itemsFiled: Int,
    val totalRuns: Int,
    val tokensUsed: String,
    val successRate: String,
)

private val EMPTY_DETAIL = AgentDetailUi(
    job = null, status = AgentStatusUi.IDLE, cadenceLabel = "", goalLabel = "", nextRun = "",
    folderLabel = "", runs = emptyList(), itemsFiled = 0, totalRuns = 0, tokensUsed = "0", successRate = "—",
)

/**
 * Agent detail. Combines the job + its run history + per-job aggregates (the stats strip:
 * items filed, runs, tokens, success rate) + the source tree (folder label). Run-now
 * executes through [AgentRunService] with [RunMode.FILE]; the transient RUNNING pill comes
 * from [isRunning]. [moveToFolder] re-files the agent's source by re-issuing the full
 * [AgentJobInput] with the new categoryId.
 */
@HiltViewModel
class AgentDetailViewModel @Inject constructor(
    private val repository: AgentRepository,
    private val scheduler: AgentScheduler,
    private val runService: AgentRunService,
    private val sourceRepository: SourceRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val jobId: String = savedStateHandle["jobId"] ?: ""

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    private val _runQueued = MutableStateFlow(false)
    val runQueued: StateFlow<Boolean> = _runQueued.asStateFlow()

    private val _runResult = MutableStateFlow<AgentRunResult?>(null)
    val runResult: StateFlow<AgentRunResult?> = _runResult.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    val state: StateFlow<AgentDetailUi> = combine(
        repository.observeJob(jobId),
        repository.observeRuns(jobId),
        repository.observeJobStats(),
        sourceRepository.observeTree(),
        isRunning,
    ) { job, runs, statsMap, tree, running ->
        if (job == null) EMPTY_DETAIL else AgentDetailUi(
            job = job,
            status = agentStatusOf(job, runs.firstOrNull(), running),
            cadenceLabel = cadenceLabel(job.frequency, job.triggerTime),
            goalLabel = job.goal,
            nextRun = if (job.enabled) nextRunText(job.frequency, job.triggerTime, System.currentTimeMillis()) else "paused",
            folderLabel = agentFolderLabel(tree, job.categoryId),
            runs = runs.map { it.toRow() },
            itemsFiled = statsMap[jobId]?.itemsFiled ?: runs.filter { it.status == AgentRunStatus.OK }.sumOf { it.itemsFiled },
            totalRuns = statsMap[jobId]?.totalRuns ?: runs.size,
            tokensUsed = formatAgentTokens(statsMap[jobId]?.tokensUsed ?: runs.sumOf { it.tokensUsed }),
            successRate = successRateOf(runs),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EMPTY_DETAIL)

    /** Folder-picker options: the ✦ Agents default first, then every drawer folder. */
    val folders: StateFlow<List<AgentFolderOption>> = sourceRepository.observeTree()
        .map { tree ->
            listOf(AgentFolderOption(null, "✦ Agents")) +
                tree.map { AgentFolderOption(it.category.id, it.category.name) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggle() {
        val job = state.value.job ?: return
        viewModelScope.launch {
            repository.setEnabled(job.id, !job.enabled)
            if (!job.enabled) scheduler.schedule(job.id, job.frequency, job.triggerTime)
            else scheduler.cancel(job.id)
        }
    }

    /**
     * Run now — executes the agent AND files items to the feed. The RUNNING pill shows
     * while [isRunning] is set; the result panel summarizes the outcome.
     */
    fun runNow() {
        val job = state.value.job ?: return
        _runResult.value = null
        _isRunning.value = true
        viewModelScope.launch {
            try {
                val outcome = runService.run(job, RunMode.FILE)
                _runResult.value = AgentRunResult(
                    success = outcome.status != AgentRunStatus.FAILED,
                    durationMs = outcome.durationMs,
                    itemCount = outcome.itemsFiled,
                    items = outcome.items.map { it.formatForDisplay() },
                    error = if (outcome.status != AgentRunStatus.OK) outcome.message else null,
                )
                if (outcome.items.isNotEmpty()) _runQueued.value = true
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun consumeRunQueued() { _runQueued.value = false }

    /**
     * Move the agent (and its filed items' source) to another folder. Re-issues the full
     * [AgentJobInput] with the new categoryId — the repository moves the agent source and
     * the items follow via their category join.
     */
    fun moveToFolder(categoryId: String?) {
        viewModelScope.launch {
            val job = repository.observeJob(jobId).first() ?: return@launch
            repository.update(
                job.id,
                AgentJobInput(
                    name = job.name,
                    goal = job.goal,
                    task = job.task,
                    format = job.format,
                    rules = job.rules,
                    maxItems = job.maxItems,
                    frequency = job.frequency,
                    triggerTime = job.triggerTime,
                    categoryId = categoryId,
                ),
            )
        }
    }

    fun delete() {
        viewModelScope.launch {
            scheduler.cancel(jobId)
            repository.delete(jobId)
            _deleted.value = true
        }
    }

    private fun successRateOf(runs: List<AgentRun>): String {
        if (runs.isEmpty()) return "—"
        val ok = runs.count { it.status == AgentRunStatus.OK }
        return "${Math.round(ok * 100.0 / runs.size)}%"
    }

    private fun AgentRun.toRow(): RunRow {
        val st = when (status) {
            AgentRunStatus.OK -> "OK"
            AgentRunStatus.FAILED -> "FAILED"
            AgentRunStatus.EMPTY -> "EMPTY"
        }
        return RunRow(
            status = st,
            title = message ?: "Run",
            whenLabel = agentRelativeTime(ranAt),
            meta = when (status) {
                AgentRunStatus.OK -> "$itemsFiled items · ${formatAgentTokens(tokensUsed)} tok"
                else -> "—"
            },
        )
    }

    private fun AgentSynthesisItem.formatForDisplay(): String {
        val sources = sources.takeIf { it.isNotEmpty() }
            ?.joinToString(" | ") { "[${it.title}](${it.url})" } ?: ""
        return "$title${summary?.let { s -> " — $s" } ?: ""}${if (sources.isNotBlank()) "\n  ↳ $sources" else ""}".trimEnd()
    }
}
