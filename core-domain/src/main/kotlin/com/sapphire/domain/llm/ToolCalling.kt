package com.sapphire.domain.llm

/**
 * Types for the tool-calling (function-calling) path of [LlmClient]. The caller
 * ([com.sapphire.domain.agent.AgentLoopService]) drives the multi-turn loop; the LLM
 * client does one round-trip per [LlmClient.completeWithTools] call.
 *
 * Provider-agnostic: the OpenAI-compatible wire mapping lives inside
 * [com.sapphire.data.llm.OpenAiCompatibleLlmClient]. No provider types leak here.
 */

/** A function the model may call during a tool-calling round. */
data class ToolDefinition(
    val name: String,
    val description: String,
    /** OpenAI tool parameter JSON schema, as a raw JSON string. */
    val jsonSchema: String,
)

/** One message in the tool-calling conversation history. */
sealed interface ToolMessage {
    /** The initial user directive / any injected nudges. */
    data class User(val content: String) : ToolMessage

    /** The model's prior turn — plain content and/or tool calls. */
    data class Assistant(val content: String?, val toolCalls: List<ToolCall> = emptyList()) : ToolMessage

    /** The result of executing a [ToolCall], fed back to the model. */
    data class ToolResult(val toolCallId: String, val content: String) : ToolMessage
}

/** A tool call the model issued. [arguments] is the raw JSON arguments string. */
data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * The model's turn from a single [LlmClient.completeWithTools] call. Either field may be
 * present: the model may emit plain content (its reasoning), tool calls, or both.
 * The caller decides how to proceed (execute tools, parse content, or end the loop).
 */
data class ToolTurn(val content: String?, val toolCalls: List<ToolCall>)
