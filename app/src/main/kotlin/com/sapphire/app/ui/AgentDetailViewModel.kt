package com.sapphire.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.agent.cadenceLabel
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRun
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val toolLabel: String,
    val cadenceLabel: String,
    val recencyLabel: String,
    val styleLabel: String,
    val langLabel: String,
    val nextRun: String,
    val runs: List<RunRow>,
    val itemsFiled: Int,
    val totalRuns: Int,
    val tokensUsed: String,
)

/**
 * Agent detail. Combines the job + its run history into [AgentDetailUi]. Toggle/delete
 * fire-and-forget; [deleted] flips true on delete so the screen pops. Run-now is a stub
 * in Slice A (no worker yet) — it seeds an EMPTY "Run-now queued" history row.
 */
@HiltViewModel
class AgentDetailViewModel @Inject constructor(
    private val repository: AgentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val jobId: String = savedStateHandle["jobId"] ?: ""

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted

    val state: StateFlow<AgentDetailUi> = combine(repository.observeJob(jobId), repository.observeRuns(jobId)) { job, runs ->
        if (job == null) AgentDetailUi(
        job = null, toolLabel = "", cadenceLabel = "",
            recencyLabel = "", styleLabel = "", langLabel = "", nextRun = "",
            runs = emptyList(), itemsFiled = 0, totalRuns = 0, tokensUsed = "0",
        ) else AgentDetailUi(
            job = job,
            toolLabel = job.searchTool.name,
            cadenceLabel = cadenceLabel(job.frequency, job.triggerTime),
            recencyLabel = recencyWindow(job),
            styleLabel = styleName(job),
            langLabel = langName(job),
            nextRun = if (job.enabled) com.sapphire.domain.agent.nextRunText(job.frequency, job.triggerTime, System.currentTimeMillis()) else "paused",
            runs = runs.map { it.toRow() },
            itemsFiled = runs.filter { it.status == com.sapphire.domain.model.AgentRunStatus.OK }.sumOf { it.itemsFiled },
            totalRuns = runs.size,
            tokensUsed = formatTokens(runs.sumOf { it.tokensUsed }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgentDetailUi(
        job = null, toolLabel = "", cadenceLabel = "",
        recencyLabel = "", styleLabel = "", langLabel = "", nextRun = "",
        runs = emptyList(), itemsFiled = 0, totalRuns = 0, tokensUsed = "0",
    ))

    fun toggle() {
        val job = state.value.job ?: return
        viewModelScope.launch { repository.setEnabled(job.id, !job.enabled) }
    }

    fun runNow() {
        // Slice A stub: no worker yet. Seed an EMPTY history row so the timeline reflects the tap.
        viewModelScope.launch {
            repository.recordRun(jobId, com.sapphire.domain.model.AgentRunStatus.EMPTY, 0, 0, "Run-now queued — execution ships in Slice B")
        }
    }

    fun delete() {
        viewModelScope.launch {
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

    private fun recencyWindow(job: AgentJob) = when (job.recency) {
        com.sapphire.domain.model.AgentRecency.H24 -> "24h window"
        com.sapphire.domain.model.AgentRecency.WEEK -> "Week window"
        com.sapphire.domain.model.AgentRecency.MONTH -> "Month window"
        com.sapphire.domain.model.AgentRecency.YEAR -> "Year window"
        com.sapphire.domain.model.AgentRecency.ALL -> "All-time window"
    }
    private fun styleName(job: AgentJob) = when (job.style) {
        com.sapphire.domain.model.AgentStyle.BRIEF -> "Analyst brief"
        com.sapphire.domain.model.AgentStyle.BULLETED -> "Bulleted"
        com.sapphire.domain.model.AgentStyle.CONVERSATIONAL -> "Conversational"
        com.sapphire.domain.model.AgentStyle.ACADEMIC -> "Academic"
        com.sapphire.domain.model.AgentStyle.HOTTAKE -> "Hot take"
        com.sapphire.domain.model.AgentStyle.EXPLAINER -> "Explainer"
    }
    private fun langName(job: AgentJob) = when (job.outputLanguage) {
        com.sapphire.domain.model.OutputLanguage.EN -> "EN"
        com.sapphire.domain.model.OutputLanguage.ZH -> "中文"
        com.sapphire.domain.model.OutputLanguage.MATCH_SOURCE -> "Match source"
    }
}
