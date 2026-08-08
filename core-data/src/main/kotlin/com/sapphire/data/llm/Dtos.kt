package com.sapphire.data.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI-compatible Chat Completions request/response wire types.
 *
 * Sapphire routes through this single shape for MVP. Both OpenAI and Anthropic-compatible
 * gateways (and most third-party routers) speak this format; an Anthropic-native client
 * is a future swap behind [com.sapphire.domain.llm.LlmClient].
 */

@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    @SerialName("response_format")
    val responseFormat: ResponseFormat? = null,
    val stream: Boolean = false,
    /**
     * Provider-specific reasoning/CoT control. Zhipu GLM reasoning models emit a long
     * `reasoning_content` before the answer (90s+ for taxonomy curation), which blows
     * past HTTP read timeouts. Setting `type = "disabled"` skips CoT and returns only
     * the final answer (5x faster). Null = provider default; omitted from JSON when null
     * (Json.explicitNulls = false), so non-Zhipu providers are unaffected.
     */
    val thinking: Thinking? = null,
    val tools: List<ToolDefDto>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
)

/** Wire shape for Zhipu's `thinking` request field. */
@Serializable
internal data class Thinking(
    val type: String, // "disabled" | "enabled"
)

@Serializable
internal data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
internal data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction,
)

@Serializable
internal data class ToolCallFunction(
    val name: String,
    val arguments: String,
)

@Serializable
internal data class ToolDefDto(
    val type: String = "function",
    val function: ToolDefFunction,
)

@Serializable
internal data class ToolDefFunction(
    val name: String,
    val description: String,
    val parameters: kotlinx.serialization.json.JsonElement,
)

@Serializable
internal data class ResponseFormat(
    val type: String, // "json_object"
)

@Serializable
internal data class ChatResponse(
    val choices: List<Choice> = emptyList(),
)

@Serializable
internal data class Choice(
    val message: ChoiceMessage? = null,
)

@Serializable
internal data class ChoiceMessage(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
)

/**
 * One Server-Sent-Events chunk from a streaming Chat Completions response. Only the
 * incremental content delta is read; the rest is ignored for forward-compat.
 */
@Serializable
internal data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
)

@Serializable
internal data class StreamChoice(
    val delta: StreamDelta? = null,
)

@Serializable
internal data class StreamDelta(
    val content: String? = null,
)

