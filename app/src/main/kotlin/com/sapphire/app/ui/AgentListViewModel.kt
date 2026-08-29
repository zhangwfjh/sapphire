package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.data.agent.AgentScheduler
import com.sapphire.domain.agent.AgentJobStats
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.agent.AgentRunService
import com.sapphire.domain.agent.AgentRunService.RunMode
import com.sapphire.domain.agent.cadenceLabel
import com.sapphire.domain.agent.nextRunText
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRun
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.source.SourceFolderNode
import com.sapphire.domain.source.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Derived agent status — the shared pill/dot vocabulary across the hub cards, the quick
 * panel, and the drawer rows. `RUNNING` exists only transiently: it is set locally when a
 * run is kicked off from this UI and clears when the run (and the history refresh) lands.
 */
enum class AgentStatusUi { RUNNING, OK, EMPTY, FAILED, PAUSED, IDLE }

/** Status derivation: transient run wins, then paused, then the last run's outcome. */
fun agentStatusOf(job: AgentJob, lastRun: AgentRun?, running: Boolean): AgentStatusUi = when {
    running -> AgentStatusUi.RUNNING
    !job.enabled -> AgentStatusUi.PAUSED
    lastRun == null -> AgentStatusUi.IDLE
    lastRun.status == AgentRunStatus.FAILED -> AgentStatusUi.FAILED
    lastRun.status == AgentRunStatus.EMPTY -> AgentStatusUi.EMPTY
    else -> AgentStatusUi.OK
}

/** "2h ago"-style relative time, shared by hub cards, detail rows, and the quick panel. */
fun agentRelativeTime(epochMs: Long): String {
    val min = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 1440 -> "${min / 60}h ago"
        else -> "${min / 1440}d ago"
    }
}

/** One-line last-run summary for card/panel cells: "OK · 3 items · 2h ago". */
fun lastRunSummary(run: AgentRun): String = when (run.status) {
    AgentRunStatus.OK -> "OK · ${run.itemsFiled} item${if (run.itemsFiled == 1) "" else "s"} · ${agentRelativeTime(run.ranAt)}"
    AgentRunStatus.EMPTY -> "EMPTY · ${agentRelativeTime(run.ranAt)}"
    AgentRunStatus.FAILED -> "FAILED · ${run.message?.take(28)?.trim() ?: agentRelativeTime(run.ranAt)}"
}

/** Compact token count: 9_412 → "9.4k", 1_250_000 → "1.3M". */
fun formatAgentTokens(n: Int): String = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
    n >= 1_000 -> "${"%.1f".format(n / 1_000.0)}k"
    else -> n.toString()
}

/** Drawer folder names (L1 + L2) keyed by categoryId, for folder chips. */
internal fun agentFolderNames(tree: List<SourceFolderNode>): Map<String, String> = buildMap {
    tree.forEach { node ->
        put(node.category.id, node.category.name)
        node.children.forEach { put(it.category.id, it.category.name) }
    }
}

/** Folder label for an agent; null categoryId (or a vanished folder) = the ✦ Agents default. */
internal fun agentFolderLabel(tree: List<SourceFolderNode>, categoryId: String?): String =
    categoryId?.let { agentFolderNames(tree)[it] } ?: "✦ Agents"

/** jobId from an agent source's deterministic id (`agent:<jobId>`), or null for normal sources. */
internal fun agentJobIdOf(sourceId: String): String? =
    sourceId.takeIf { it.startsWith("agent:") }?.removePrefix("agent:")

/** One agent card in the hub: pre-rendered labels + real per-job stats. */
data class AgentCardUi(
    val job: AgentJob,
    val cadenceLabel: String,
    val nextRun: String,
    val folderLabel: String,
    val status: AgentStatusUi,
    val lastRunLabel: String,
    val itemsFiled: Int,
    /** Last 8 runs' itemsFiled, oldest → newest (the card sparkline). */
    val spark: List<Int>,
)

/** Hub hero strip: cross-agent totals. `filed24h` sums last runs fired within 24h. */
data class AgentListStats(
    val active: Int,
    val total: Int,
    val filed24h: Int,
    val totalRuns: Int,
    val attention: Int,
)

/**
 * Agents hub. Combines jobs + per-job stats + last runs + the source tree (folder names)
 * + full run histories (sparklines) into [agents]; [stats] merges totals for the hero
 * strip. Run-now / Run-all execute [AgentRunService] with [RunMode.FILE] sequentially in
 * [viewModelScope]; the transient [AgentStatusUi.RUNNING] state lives in [runningIds]
 * and clears as each run completes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgentListViewModel @Inject constructor(
    private val repository: AgentRepository,
    private val scheduler: AgentScheduler,
    private val runService: AgentRunService,
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    private val _runningIds = MutableStateFlow<Set<String>>(emptySet())
    val runningIds: StateFlow<Set<String>> = _runningIds.asStateFlow()

    /** Full run history per jobId (keyed, so a jobs/history emission race can't misalign). */
    private val runHistories = repository.observeJobs()
        .distinctUntilChanged()
        .flatMapLatest { jobs ->
            if (jobs.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val flows = jobs.map { repository.observeRuns(it.id) }.toTypedArray()
                combine(*flows) { lists: Array<List<AgentRun>> ->
                    jobs.mapIndexed { i, job -> job.id to lists[i] }.toMap()
                }
            }
        }

    val agents: StateFlow<List<AgentCardUi>> = combine(
        repository.observeJobs(),
        repository.observeJobStats(),
        repository.observeLastRuns(),
        sourceRepository.observeTree(),
        combine(runHistories, runningIds) { histories, running -> histories to running },
    ) { jobs, stats, lastRuns, tree, (histories, running) ->
        jobs.map { job ->
            val last = lastRuns[job.id]
            val jobStats: AgentJobStats? = stats[job.id]
            val runs = histories[job.id].orEmpty() // newest first
            AgentCardUi(
                job = job,
                cadenceLabel = cadenceLabel(job.frequency, job.triggerTime),
                nextRun = if (job.enabled) nextRunText(job.frequency, job.triggerTime, System.currentTimeMillis()) else "—",
                folderLabel = agentFolderLabel(tree, job.categoryId),
                status = agentStatusOf(job, last, job.id in running),
                lastRunLabel = last?.let(::lastRunSummary) ?: "never run",
                itemsFiled = jobStats?.itemsFiled ?: 0,
                spark = runs.asReversed().takeLast(8).map { it.itemsFiled },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stats: StateFlow<AgentListStats> = combine(
        repository.observeJobs(),
        repository.observeTotals(),
        repository.observeLastRuns(),
    ) { jobs, totals, lastRuns ->
        val dayAgoMs = System.currentTimeMillis() - 24 * 3_600_000L
        AgentListStats(
            active = jobs.count { it.enabled },
            total = jobs.size,
            filed24h = lastRuns.values.filter { it.ranAt >= dayAgoMs }.sumOf { it.itemsFiled },
            totalRuns = totals.totalRuns,
            attention = lastRuns.values.count { it.status == AgentRunStatus.FAILED },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AgentListStats(active = 0, total = 0, filed24h = 0, totalRuns = 0, attention = 0),
    )

    /** Run one agent now — files items to the feed; RUNNING pill shows until it lands. */
    fun runNow(job: AgentJob) {
        if (job.id in _runningIds.value) return
        _runningIds.value = _runningIds.value + job.id
        viewModelScope.launch {
            try {
                runService.run(job, RunMode.FILE)
            } finally {
                _runningIds.value = _runningIds.value - job.id
            }
        }
    }

    /** Run every enabled agent, sequentially, in one coroutine. */
    fun runAllNow() {
        viewModelScope.launch {
            repository.observeJobs().first().filter { it.enabled }.forEach { job ->
                _runningIds.value = _runningIds.value + job.id
                try {
                    runService.run(job, RunMode.FILE)
                } finally {
                    _runningIds.value = _runningIds.value - job.id
                }
            }
        }
    }

    /** Pause every enabled agent: flag off + schedule cancelled. */
    fun pauseAll() {
        viewModelScope.launch {
            repository.observeJobs().first().filter { it.enabled }.forEach { job ->
                repository.setEnabled(job.id, false)
                scheduler.cancel(job.id)
            }
        }
    }

    fun toggle(id: String, enabled: Boolean, frequency: AgentFrequency, triggerTime: String) {
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
}

/** Cadence chip → compact mono label: "Every 6h · from 07:00" → "6H · 07:00". */
fun shortCadenceLabel(label: String): String {
    val hourly = Regex("Every (\\d+)h").find(label)?.groupValues?.get(1)
    return when {
        hourly != null -> {
            val tail = label.substringAfter("from ").trim()
            if (tail.isBlank()) "${hourly}H" else "${hourly}H · $tail"
        }
        label.startsWith("Daily") -> "DAILY"
        label.startsWith("Weekdays") -> "WKD"
        label.startsWith("Weekly") -> "WEEKLY"
        else -> label.uppercase()
    }
}
