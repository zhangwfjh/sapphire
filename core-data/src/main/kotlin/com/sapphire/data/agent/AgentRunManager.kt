package com.sapphire.data.agent

import com.sapphire.data.di.ApplicationScope
import com.sapphire.domain.agent.AgentRunService
import com.sapphire.domain.agent.AgentRunService.RunMode
import com.sapphire.domain.model.AgentJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns manual agent runs so they outlive the screen that started them. Run-now from the
 * hub or the detail screen launches here on the application scope: backing out of the
 * agents screens (ViewModel cleared) no longer cancels an in-flight run — it keeps
 * researching, files its items, and records its history row.
 *
 * [runningIds] is the single source of every RUNNING pill across screens. A job already
 * present is ignored, so repeated taps (or run-now from two screens at once) yield one
 * in-flight run per job. [AgentRunnerWorker] runs outside this manager and does not
 * contribute to the set.
 */
@Singleton
class AgentRunManager @Inject constructor(
    private val runService: AgentRunService,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _runningIds = MutableStateFlow<Set<String>>(emptySet())
    val runningIds: StateFlow<Set<String>> = _runningIds.asStateFlow()

    /**
     * One manual [RunMode.FILE] run. Returns false (and does nothing) when this job
     * already has a run in flight. [onCompletion] receives the outcome — invoked on a
     * background dispatcher; writing to StateFlow properties from it is safe.
     */
    fun runNow(job: AgentJob, onCompletion: ((AgentRunService.AgentRunOutcome) -> Unit)? = null): Boolean {
        var started = false
        _runningIds.update { if (job.id in it) it else { started = true; it + job.id } }
        if (!started) return false
        scope.launch {
            try {
                val outcome = runService.run(job, RunMode.FILE)
                onCompletion?.invoke(outcome)
            } finally {
                _runningIds.update { it - job.id }
            }
        }
        return true
    }

    /** Runs each job sequentially in one coroutine, pill set per job as it progresses. */
    fun runAll(jobs: List<AgentJob>) {
        if (jobs.isEmpty()) return
        scope.launch {
            jobs.forEach { job ->
                var started = false
                _runningIds.update { if (job.id in it) it else { started = true; it + job.id } }
                if (!started) return@forEach
                try {
                    runService.run(job, RunMode.FILE)
                } finally {
                    _runningIds.update { it - job.id }
                }
            }
        }
    }
}
