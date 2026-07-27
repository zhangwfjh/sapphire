package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.agent.cadenceLabel
import com.sapphire.domain.agent.nextRunText
import com.sapphire.domain.model.AgentJob
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One agent row in the list, with its cadence + next-run pre-rendered (the design's
 * `.agcard` meta row). `nextRun` reads "paused" when disabled so the card reflects state.
 */
data class AgentCardUi(
    val job: AgentJob,
    val cadenceLabel: String,
    val nextRun: String,
)

/** List-screen summary stats (the hero row). */
data class AgentListStats(val active: Int, val total: Int, val itemsFiled: Int, val totalRuns: Int)

/**
 * Agents list. Backed by [AgentRepository.observeJobs]; toggle/delete fire-and-forget.
 * Stats are derived from the same flow. Mirrors the [SavedItemsViewModel] pattern.
 */
@HiltViewModel
class AgentListViewModel @Inject constructor(
    private val repository: AgentRepository,
    private val scheduler: com.sapphire.data.agent.AgentScheduler,
) : ViewModel() {

    val agents: StateFlow<List<AgentCardUi>> = repository.observeJobs()
        .map { jobs -> jobs.map { it.toCard() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stats: StateFlow<AgentListStats> = repository.observeJobs()
        .map { jobs ->
            AgentListStats(
                active = jobs.count { it.enabled },
                total = jobs.size,
                // items-filed and total-runs come from run history (Slice A: 0 until Slice B runs).
                itemsFiled = 0,
                totalRuns = 0,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgentListStats(0, 0, 0, 0))

    fun toggle(id: String, enabled: Boolean, frequency: com.sapphire.domain.model.AgentFrequency, triggerTime: String) {
        viewModelScope.launch {
            repository.setEnabled(id, enabled)
            if (enabled) scheduler.schedule(id, frequency, triggerTime) else scheduler.cancel(id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            scheduler.cancel(id)
            repository.delete(id)
        }
    }

    private fun AgentJob.toCard(): AgentCardUi = AgentCardUi(
        job = this,
        cadenceLabel = cadenceLabel(frequency, triggerTime),
        nextRun = if (enabled) nextRunText(frequency, triggerTime, System.currentTimeMillis()) else "paused",
    )
}
