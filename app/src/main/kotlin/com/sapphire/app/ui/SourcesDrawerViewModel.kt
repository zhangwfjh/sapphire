package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.data.agent.AgentScheduler
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.agent.AgentRunService
import com.sapphire.domain.agent.AgentRunService.RunMode
import com.sapphire.domain.agent.cadenceLabel
import com.sapphire.domain.agent.nextRunText
import com.sapphire.domain.feed.FeedRepository
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.model.SourceKind
import com.sapphire.domain.source.SourceFolderNode
import com.sapphire.domain.source.SourceRepository
import com.sapphire.domain.source.SourceRepository.Outcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Emitted after a "mark all as read" sweep so the UI can surface an Undo snackbar.
 * [itemIds] are the ids that were newly flipped READ — reverting them restores the prior
 * unread state.
 */
data class MarkAllReadEvent(val itemIds: List<String>, val label: String, val count: Int)

/** Drawer "Agents" hub row sublabel inputs: "n active · m need attention". */
data class AgentHubBadge(val active: Int, val attention: Int)

/** Agent row extras for the drawer: live status dot + cadence, keyed by sourceId. */
data class AgentDrawerRowUi(
    val jobId: String,
    val status: AgentStatusUi,
    val cadenceShort: String,
)

/** One filed-item preview row in the quick panel (title + relative time). */
data class AgentFiledPreview(val title: String, val whenLabel: String)

/** Full quick-panel payload for one agent. */
data class AgentPanelUi(
    val job: AgentJob,
    val status: AgentStatusUi,
    val folderLabel: String,
    val cadenceShort: String,
    val lastRunLabel: String,
    val nextRun: String,
    val itemsFiled: Int,
    val tokensLabel: String,
    /** Two most-recent filed item titles; falls back to last run messages when empty. */
    val recentFiled: List<AgentFiledPreview>,
)

/**
 * State for the Sources drawer. The tree is a cold [Flow] hoisted into a [StateFlow];
 * every mutation re-fetches through the repository, so the tree recomposes live.
 *
 * Agent tier (redesign): agent source rows carry a live status dot + cadence label and a
 * chevron that opens the quick panel — [agentRows] (keyed by sourceId) drives the rows,
 * [panels] (keyed by jobId) drives the panel, and [hubBadge] drives the drawer's
 * "Agents" hub entry. Runs/pauses from the panel go through [AgentRunService] and
 * [AgentScheduler] exactly like the hub.
 *
 * Conflict snackbar: add/move/update that collide with the `(category_id, url)` unique index
 * surface a short message instead of silently dropping.
 *
 * Mark-all-read: swiping a source (or folder) left sweeps its unread items to READ and
 * emits a [MarkAllReadEvent] on [markReadEvents] so the drawer can offer an Undo snackbar
 * (the Undo safety net). Reverting forwards through [undoMarkRead].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SourcesDrawerViewModel @Inject constructor(
    private val repository: SourceRepository,
    private val feedRepository: FeedRepository,
    private val agentRepository: AgentRepository,
    private val agentScheduler: AgentScheduler,
    private val agentRunService: AgentRunService,
) : ViewModel() {

    val tree: StateFlow<List<SourceFolderNode>> = repository.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _conflict = MutableStateFlow<String?>(null)
    val conflict: StateFlow<String?> = _conflict.asStateFlow()

    private val _markReadEvents = Channel<MarkAllReadEvent>(capacity = Channel.BUFFERED)
    /** One-shot mark-all-read events for the Undo snackbar. */
    val markReadEvents = _markReadEvents.receiveAsFlow()

    // ---- Agent tier ----

    private val _runningIds = MutableStateFlow<Set<String>>(emptySet())
    val runningIds: StateFlow<Set<String>> = _runningIds.asStateFlow()

    /** Two most-recent filed items per agent job, live (titles for the quick panel). */
    private val filedPreviews = agentRepository.observeJobs()
        .distinctUntilChanged()
        .flatMapLatest { jobs ->
            if (jobs.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val flows = jobs.map { job ->
                    feedRepository.observeBySource("agent:${job.id}").map { items ->
                        job.id to items.take(2).map { AgentFiledPreview(it.title, agentRelativeTime(it.publishedAt ?: it.fetchedAt)) }
                    }
                }.toTypedArray()
                combine(*flows) { pairs: Array<Pair<String, List<AgentFiledPreview>>> -> pairs.toMap() }
            }
        }

    /** Quick-panel payloads keyed by jobId. */
    val panels: StateFlow<Map<String, AgentPanelUi>> = combine(
        agentRepository.observeJobs(),
        agentRepository.observeJobStats(),
        agentRepository.observeLastRuns(),
        repository.observeTree(),
        combine(filedPreviews, runningIds) { previews, running -> previews to running },
    ) { jobs, stats, lastRuns, tree, (previews, running) ->
        jobs.associate { job ->
            val last = lastRuns[job.id]
            val s = stats[job.id]
            job.id to AgentPanelUi(
                job = job,
                status = agentStatusOf(job, last, job.id in running),
                folderLabel = agentFolderLabel(tree, job.categoryId),
                cadenceShort = shortCadenceLabel(cadenceLabel(job.frequency, job.triggerTime)),
                lastRunLabel = last?.let(::lastRunSummary) ?: "never run",
                nextRun = if (job.enabled) nextRunText(job.frequency, job.triggerTime, System.currentTimeMillis()) else "—",
                itemsFiled = s?.itemsFiled ?: 0,
                tokensLabel = formatAgentTokens(s?.tokensUsed ?: 0),
                recentFiled = previews[job.id].orEmpty().ifEmpty {
                    // No filed items yet — surface the last run's message so the panel still shows signal.
                    last?.message?.takeIf { it.isNotBlank() }?.let { listOf(AgentFiledPreview(it, agentRelativeTime(last.ranAt))) }.orEmpty()
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Row extras keyed by agent SOURCE id (`agent:<jobId>`). */
    val agentRows: StateFlow<Map<String, AgentDrawerRowUi>> = combine(
        agentRepository.observeJobs(),
        agentRepository.observeLastRuns(),
        runningIds,
    ) { jobs, lastRuns, running ->
        jobs.associate { job ->
            "agent:${job.id}" to AgentDrawerRowUi(
                jobId = job.id,
                status = agentStatusOf(job, lastRuns[job.id], job.id in running),
                cadenceShort = shortCadenceLabel(cadenceLabel(job.frequency, job.triggerTime)),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** "n active · m need attention" for the drawer's Agents hub row. */
    val hubBadge: StateFlow<AgentHubBadge> = combine(
        agentRepository.observeJobs(),
        agentRepository.observeLastRuns(),
    ) { jobs, lastRuns ->
        AgentHubBadge(
            active = jobs.count { it.enabled },
            attention = lastRuns.values.count { it.status == AgentRunStatus.FAILED },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgentHubBadge(0, 0))

    /** Run one agent from the quick panel — files to the feed, RUNNING until it lands. */
    fun runAgentNow(jobId: String) {
        if (jobId in _runningIds.value) return
        _runningIds.value = _runningIds.value + jobId
        viewModelScope.launch {
            try {
                agentRepository.observeJob(jobId).first()?.let { agentRunService.run(it, RunMode.FILE) }
            } finally {
                _runningIds.value = _runningIds.value - jobId
            }
        }
    }

    /** Pause (cancel schedule) or resume (re-schedule) an agent from the panel. */
    fun setAgentPaused(jobId: String, paused: Boolean) {
        viewModelScope.launch {
            val job = agentRepository.observeJob(jobId).first() ?: return@launch
            agentRepository.setEnabled(jobId, !paused)
            if (paused) {
                agentScheduler.cancel(jobId)
            } else {
                agentScheduler.schedule(jobId, job.frequency, job.triggerTime)
            }
        }
    }

    // ---- Sources tree ----

    fun addSource(categoryId: String, title: String, url: String, kind: SourceKind) {
        viewModelScope.launch {
            when (val r = repository.addSource(categoryId, url.trim(), title.trim(), kind)) {
                is Outcome.Ok -> Unit
                is Outcome.Conflict -> _conflict.value = "A source with that URL already exists in the target folder."
            }
        }
    }

    fun updateSource(id: String, title: String, url: String, kind: SourceKind) {
        viewModelScope.launch {
            when (val r = repository.updateSource(id, title.trim(), url.trim(), kind)) {
                is Outcome.Ok -> Unit
                is Outcome.Conflict -> _conflict.value = "Another source in this folder already uses that URL."
            }
        }
    }

    fun moveSource(id: String, toCategoryId: String) {
        viewModelScope.launch {
            when (val r = repository.moveSource(id, toCategoryId)) {
                is Outcome.Ok -> Unit
                is Outcome.Conflict -> _conflict.value = "That URL already exists in the target folder."
            }
        }
    }

    /** Batch-move a set of sources into [toCategoryId] in one transaction. Conflicting
     *  URLs are skipped; the count is surfaced via the conflict snackbar. */
    fun moveSources(ids: Set<String>, toCategoryId: String) {
        viewModelScope.launch {
            val conflicts = repository.moveSources(ids, toCategoryId)
            if (conflicts > 0) {
                _conflict.value = "$conflicts source(s) skipped — URL already in target folder."
            }
        }
    }

    fun deleteSource(id: String) {
        viewModelScope.launch { repository.deleteSource(id) }
    }

    /** Batch-delete a set of sources in one pass. */
    fun deleteSources(ids: Set<String>) {
        viewModelScope.launch { repository.deleteSources(ids) }
    }

    fun addCategory(topicId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { repository.addCategory(topicId, trimmed) }
    }

    /**
     * Create a new folder under the current topic. The topic id is read from the topic
     * table (not inferred from the first category) so this works even when the user has
     * deleted every folder — the drawer's "New folder" button stays usable.
     */
    fun addTopLevelCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val topicId = repository.currentTopicId()
            if (topicId == null) {
                _conflict.value = "Curate a topic first — folders live under a topic."
                return@launch
            }
            repository.addCategory(topicId, trimmed)
        }
    }

    fun renameCategory(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { repository.renameCategory(id, trimmed) }
    }


    /** Merge all sources from [fromId] into [toId], then delete [fromId]. Dedup by URL. */
    fun mergeCategory(fromId: String, toId: String) {
        viewModelScope.launch {
            runCatching { repository.mergeCategory(fromId, toId) }
                .onFailure { _conflict.value = "Merge failed: ${it.message}" }
        }
    }
    fun deleteCategory(id: String) {
        viewModelScope.launch { repository.deleteCategory(id) }
    }

    fun dismissConflict() { _conflict.value = null }

    /**
     * Mark every unread item from any of [sourceIds] READ (virtual domain-group sweep).
     * Same Undo contract as [markAllReadInSource].
     */
    fun markAllReadInSourceGroup(sourceIds: Set<String>, label: String) {
        viewModelScope.launch {
            val ids = feedRepository.markReadBySources(sourceIds)
            if (ids.isNotEmpty()) {
                _markReadEvents.trySend(MarkAllReadEvent(ids, label, ids.size))
            }
        }
    }

    /**
     * Mark every unread item from [sourceId] READ. Emits a [MarkAllReadEvent] with the
     * newly-read ids so the drawer can show "Marked N as read" with an Undo action.
     * No-op (no event) when there were zero unread items.
     */
    fun markAllReadInSource(sourceId: String, label: String) {
        viewModelScope.launch {
            val ids = feedRepository.markReadBySource(sourceId)
            if (ids.isNotEmpty()) {
                _markReadEvents.trySend(MarkAllReadEvent(ids, label, ids.size))
            }
        }
    }

    /**
     * Mark every unread item in [categoryId] READ (folder-level sweep). Same Undo contract
     * as [markAllReadInSource].
     */
    fun markAllReadInCategory(categoryId: String, label: String) {
        viewModelScope.launch {
            val ids = feedRepository.markReadByCategory(categoryId)
            if (ids.isNotEmpty()) {
                _markReadEvents.trySend(MarkAllReadEvent(ids, label, ids.size))
            }
        }
    }

    /** Revert a mark-all-read sweep (Undo snackbar). Idempotent. */
    fun undoMarkRead(itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        viewModelScope.launch { feedRepository.undoMarkRead(itemIds) }
    }
}

sealed interface ImportState {
    data object Idle : ImportState
    data object Importing : ImportState
    data class Done(val sourcesImported: Int) : ImportState
    data class Error(val message: String) : ImportState
}
