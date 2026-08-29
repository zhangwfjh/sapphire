package com.sapphire.domain.agent

import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRunStatus
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * The agent-run module (PRD §3.7): one call that performs a whole [Agent run] — research
 * via [AgentLoopService], then (per mode) filing and history recording. Owns the
 * outcome→status→tokens→message derivation exactly once; the worker and the ViewModels
 * are adapters over [run] and differ only in mode and display mapping.
 *
 * Exclusion wiring: each run passes the agent's recently filed URLs into the loop so the
 * model picks different content than previous runs, and into [AgentRerankService] for the
 * hard cross-run dedup + Tier-2 verdict before filing.
 *
 * Cancellation propagates (structured concurrency); any other failure collapses into a
 * FAILED outcome (and a FAILED history row, except [RunMode.DRY]).
 */
class AgentRunService @Inject constructor(
    private val loop: AgentLoopService,
    private val repository: AgentRepository,
    private val rerank: AgentRerankService,
) {

    /** How a run's results land: filed to the feed, recorded-but-not-filed, or neither. */
    enum class RunMode {
        /** Full run: file items under the agent's source and record history. */
        FILE,
        /** Test run: record history, file nothing. */
        TEST,
        /** Dry run (builder preview): execute only — nothing filed, nothing recorded. */
        DRY,
    }

    /** One completed run, whatever the mode. [error] is non-null only on FAILED. */
    data class AgentRunOutcome(
        val status: AgentRunStatus,
        val message: String?,
        val durationMs: Long,
        val items: List<AgentSynthesisItem>,
        val itemsFiled: Int,
        val tokensUsed: Int,
        val error: LlmError?,
    )

    suspend fun run(
        job: AgentJob,
        mode: RunMode,
        onEvent: ((phase: String, detail: String) -> Unit)? = null,
    ): AgentRunOutcome {
        val start = System.currentTimeMillis()
        return try {
            val previouslyFiled = repository.recentlyFiledUrls(job.id)
            when (val outcome = loop.run(job, previouslyFiled, onEvent)) {
                is LlmOutcome.Ok -> {
                    val kept = rerank.rerank(outcome.value.items, previouslyFiled)
                    complete(job, mode, kept, start)
                }
                is LlmOutcome.Err -> {
                    val message = outcome.error.userMessage()
                    if (mode != RunMode.DRY) {
                        repository.recordRun(job.id, AgentRunStatus.FAILED, 0, 0, message)
                    }
                    AgentRunOutcome(AgentRunStatus.FAILED, message, elapsed(start), emptyList(), 0, 0, outcome.error)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            if (mode != RunMode.DRY) {
                repository.recordRun(job.id, AgentRunStatus.FAILED, 0, 0, message)
            }
            AgentRunOutcome(AgentRunStatus.FAILED, message, elapsed(start), emptyList(), 0, 0, null)
        }
    }

    private suspend fun complete(
        job: AgentJob,
        mode: RunMode,
        items: List<AgentSynthesisItem>,
        start: Long,
    ): AgentRunOutcome {
        val duration = elapsed(start)
        if (items.isEmpty()) {
            if (mode != RunMode.DRY) {
                repository.recordRun(job.id, AgentRunStatus.EMPTY, 0, 0, EMPTY_MESSAGE)
            }
            return AgentRunOutcome(AgentRunStatus.EMPTY, EMPTY_MESSAGE, duration, items, 0, 0, null)
        }
        return when (mode) {
            RunMode.FILE -> {
                val filed = repository.fileAgentItems(job.id, items, job.name)
                val status = if (filed > 0) AgentRunStatus.OK else AgentRunStatus.EMPTY
                val message = if (filed < items.size) "$filed/${items.size} filed (some deduped)" else "$filed items filed"
                val tokens = tokensUsed(items)
                repository.recordRun(job.id, status, filed, tokens, message)
                AgentRunOutcome(status, message, duration, items, filed, tokens, null)
            }
            RunMode.TEST -> {
                val tokens = tokensUsed(items)
                val message = "Test run: ${items.size} items in ${duration}ms"
                repository.recordRun(job.id, AgentRunStatus.OK, items.size, tokens, message)
                AgentRunOutcome(AgentRunStatus.OK, message, duration, items, items.size, tokens, null)
            }
            RunMode.DRY -> AgentRunOutcome(AgentRunStatus.OK, null, duration, items, 0, 0, null)
        }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    /** Rough per-item Tier-1 estimate — the single owner of this constant. */
    private fun tokensUsed(items: List<AgentSynthesisItem>): Int = items.size * TOKENS_PER_ITEM

    private companion object {
        const val TOKENS_PER_ITEM = 1200
        const val EMPTY_MESSAGE = "No items worth filing"
    }
}
