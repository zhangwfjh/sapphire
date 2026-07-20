package com.sapphire.domain.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer

/** Routing tier. Fast models for taxonomy/classification; deep for context ops. */
enum class LlmTier { TIER1_FAST, TIER2_DEEP }

/**
 * Typed outcome for every LLM call. UI pattern-matches on [LlmError]; no raw exceptions
 * cross the domain boundary. The fallback ("clean Error Popup Modal") maps off
 * [LlmError.Empty] / [LlmError.Timeout] / [LlmError.InvalidResponse].
 */
sealed interface LlmOutcome<out T> {
    data class Ok<T>(val value: T) : LlmOutcome<T>
    data class Err(val error: LlmError) : LlmOutcome<Nothing>
}

/** Stable, UI-facing error taxonomy. Not throwables — pure data. */
sealed interface LlmError {
    /** No high-signal feeds/categories returned — "try a broader topic". */
    data class Empty(val reason: String) : LlmError
    data object Timeout : LlmError
    data object RateLimited : LlmError
    data object InvalidResponse : LlmError
    data object NotConfigured : LlmError
    data class Http(val status: Int) : LlmError
    data class Network(val message: String) : LlmError

    /** Human-facing copy for the fallback modal. */
    fun userMessage(): String = when (this) {
        is Empty -> reason
        Timeout -> "The request took too long. Try again."
        RateLimited -> "The model is busy right now. Try again in a moment."
        InvalidResponse -> "Could not parse the model's response. Try a different topic."
        NotConfigured -> "No model API key configured. See README → local.properties."
        is Http -> "Model service error (HTTP $status)."
        is Network -> "Network error: $message"
    }
}

/**
 * Provider-agnostic structured-completion contract. Implementations translate this to a
 * concrete provider's API (OpenAI-compatible JSON mode for MVP; Anthropic tool-use later).
 */
interface LlmClient {
    suspend fun <T> completeStructured(
        tier: LlmTier,
        systemPrompt: String,
        userPrompt: String,
        outputSerializer: KSerializer<T>,
    ): LlmOutcome<T>
    /**
     * Streams a plain-text completion as progressive partials. Each [LlmOutcome.Ok] carries
     * the full text accumulated so far; the final emission is the complete text. A failure
     * yields a single [LlmOutcome.Err] and the flow completes. Never throws.
     *
     * Unlike [completeStructured] this uses no JSON mode — callers render raw deltas and
     * parse the final text themselves. The reader summary streams bullets token by token
     * (streaming reveal).
     */
    fun streamText(
        tier: LlmTier,
        systemPrompt: String,
        userPrompt: String,
    ): Flow<LlmOutcome<String>>
}
