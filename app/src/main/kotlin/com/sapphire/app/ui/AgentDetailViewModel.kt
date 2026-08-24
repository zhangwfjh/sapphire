package com.sapphire.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.agent.AgentRunService
import com.sapphire.domain.agent.AgentSynthesisItem
import com.sapphire.domain.agent.AgentRunService.RunMode
import com.sapphire.domain.agent.cadenceLabel
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRun
import com.sapphire.domain.model.AgentRunStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.lifecycle.viewModelScope

/** Run-history row rendered for the timeline (the design's `.run`). */
data class RunRow(
    val status: String,   // OK / FAILED / EMPTY
    val title: String,    // run.message or synthesized
    val whenLabel: String,
    val meta: String,
)

/** Detail-screen state — job + its derived display fields + run history. */
data class AgentDetailUi(
    val job: AgentJob?,
    val cadenceLabel: String,
    val goalLabel: String,
    val nextRun: String,
    val runs: List<RunRow>,
    val itemsFiled: Int,
    val totalRuns: Int,
    val tokensUsed: String,
)

/**
 * Agent detail. Combines the job + its run history into [AgentDetailUi]. Toggle/delete
 * fire-and-forget; [deleted] flips true on delete so the screen pops. Test Run and
 * Run now both execute through [AgentRunService] — they differ only in
 * [RunMode.TEST] (records history, files nothing) vs [RunMode.FILE] (files to the feed).
 */
@HiltViewModel
class AgentDetailViewModel @Inject constructor(
    private val repository: AgentRepository,
    private val scheduler: com.sapphire.data.agent.AgentScheduler,
    private val runService: AgentRunService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val jobId: String = savedStateHandle["jobId"] ?: ""

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted

    private val _runQueued = MutableStateFlow(false)
    val runQueued: StateFlow<Boolean> = _runQueued

    private val _testResult = MutableStateFlow<TestRunResult?>(null)
    val testResult: StateFlow<TestRunResult?> = _testResult

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    val state: StateFlow<AgentDetailUi> = combine(repository.observeJob(jobId), repository.observeRuns(jobId)) { job, runs ->
        if (job == null) AgentDetailUi(
            job = null, cadenceLabel = "", goalLabel = "", nextRun = "",
            runs = emptyList(), itemsFiled = 0, totalRuns = 0, tokensUsed = "0",
        ) else AgentDetailUi(
            job = job,
            cadenceLabel = cadenceLabel(job.frequency, job.triggerTime),
            goalLabel = job.goal,
            nextRun = if (job.enabled) com.sapphire.domain.agent.nextRunText(job.frequency, job.triggerTime, System.currentTimeMillis()) else "paused",
            runs = runs.map { it.toRow() },
            itemsFiled = runs.filter { it.status == com.sapphire.domain.model.AgentRunStatus.OK }.sumOf { it.itemsFiled },
            totalRuns = runs.size,
            tokensUsed = formatTokens(runs.sumOf { it.tokensUsed }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgentDetailUi(
        job = null, cadenceLabel = "", goalLabel = "", nextRun = "",
        runs = emptyList(), itemsFiled = 0, totalRuns = 0, tokensUsed = "0",
    ))

    fun toggle() {
        val job = state.value.job ?: return
        viewModelScope.launch {
            repository.setEnabled(job.id, !job.enabled)
            if (!job.enabled) scheduler.schedule(job.id, job.frequency, job.triggerTime)
            else scheduler.cancel(job.id)
        }
    }

    /**
     * Test Run — executes the agent and shows the result inline (items, timing, sources)
     * WITHOUT filing anything to the feed. Records a history row so the timeline reflects
     * the test. Lets the user verify their agent settings before committing to runs.
     */
    fun testRun() {
        val job = state.value.job ?: return
        _testResult.value = null
        _isRunning.value = true
        viewModelScope.launch {
            try {
                val outcome = runService.run(job, RunMode.TEST)
                _testResult.value = TestRunResult(
                    success = outcome.status != AgentRunStatus.FAILED,
                    durationMs = outcome.durationMs,
                    itemCount = outcome.items.size,
                    items = outcome.items.map { it.formatForDisplay() },
                    error = if (outcome.status != AgentRunStatus.OK) outcome.message else null,
                )
            } finally {
                _isRunning.value = false
            }
        }
    }

    /**
     * Run now — executes the agent AND files items to the feed (unlike testRun which
     * only shows results). Used from the Agents list context menu.
     */
    fun runNow() {
        val job = state.value.job ?: return
        _testResult.value = null
        _isRunning.value = true
        viewModelScope.launch {
            try {
                val outcome = runService.run(job, RunMode.FILE)
                _testResult.value = TestRunResult(
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


    fun consumeTestResult() { _testResult.value = null }

    fun consumeRunQueued() { _runQueued.value = false }

    fun delete() {
        viewModelScope.launch {
            scheduler.cancel(jobId)
            repository.delete(jobId)
            _deleted.value = true
        }
    }

    private fun AgentRun.toRow(): RunRow {
        val st = when (status) {
            com.sapphire.domain.model.AgentRunStatus.OK -> "OK"
            com.sapphire.domain.model.AgentRunStatus.FAILED -> "FAILED"
            com.sapphire.domain.model.AgentRunStatus.EMPTY -> "EMPTY"
        }
        return RunRow(
            status = st,
            title = message ?: "Run",
            whenLabel = relativeWhen(ranAt),
            meta = when (status) {
                com.sapphire.domain.model.AgentRunStatus.OK -> "$itemsFiled items · ${tokensUsed} tok"
                else -> "—"
            },
        )
    }

    private fun AgentSynthesisItem.formatForDisplay(): String {
        val sources = sources.takeIf { it.isNotEmpty() }
            ?.joinToString(" | ") { "[${it.title}](${it.url})" } ?: ""
        return "$title${summary?.let { s -> " — $s" } ?: ""}${if (sources.isNotBlank()) "\n  ↳ $sources" else ""}"
    }

    private fun relativeWhen(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        val min = diff / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "${min}m ago"
            min < 1440 -> "${min / 60}h ago"
            else -> "${min / 1440}d ago"
        }
    }

    private fun formatTokens(n: Int): String = when {
        n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
        n >= 1_000 -> "${n / 1_000}k"
        else -> n.toString()
    }


}
