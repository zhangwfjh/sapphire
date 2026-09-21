package com.sapphire.data.llm

import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmConfig
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.llm.ToolCall
import com.sapphire.domain.llm.ToolDefinition
import com.sapphire.domain.llm.ToolMessage
import com.sapphire.domain.llm.ToolTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible HTTP implementation of [LlmClient].
 *
 * - Uses JSON mode (`response_format: json_object`) for structured output, then parses the
 *   message content into the requested [T] via kotlinx.serialization. This is the cheapest
 *   structured-output path that works across OpenAI-compatible gateways without
 *   tool-calling support divergence.
 * - Failures are mapped to typed [LlmError]s — never thrown. Timeouts map to [LlmError.Timeout],
 *   HTTP 429 → [LlmError.RateLimited], other non-2xx → [LlmError.Http], JSON parse failure →
 *   [LlmError.InvalidResponse].
 * - Config is resolved from an injected supplier on every call, so runtime edits (Settings)
 *   take effect immediately. [LlmError.NotConfigured] is returned early if the API key or
 *   base URL is blank, so the UI renders the README-config prompt instead of a confusing 401.
 */
class OpenAiCompatibleLlmClient(
    private val configProvider: () -> LlmConfig,
    private val json: Json,
    client: OkHttpClient,
) : LlmClient {

    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // LLMs can be slow on first token; allow up to 90s
        .writeTimeout(20, TimeUnit.SECONDS)
        // Some OpenAI-compatible gateways mishandle HTTP/2 streaming (buffered or de-streamed
        // responses); HTTP/1.1 is the lowest common denominator and streams reliably.
        .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
        .build()

    // Agent tool-calling rounds run with reasoning enabled — first token can take well
    // past 90s — so they get a longer read budget on an otherwise identical client.
    private val toolsClient: OkHttpClient = client.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun <T> completeStructured(
        tier: LlmTier,
        systemPrompt: String,
        userPrompt: String,
        outputSerializer: KSerializer<T>,
    ): LlmOutcome<T> = withContext(Dispatchers.IO) {
        val config = configProvider()
        if (config.apiKey.isBlank() || config.baseUrl.isBlank()) return@withContext LlmOutcome.Err(LlmError.NotConfigured)

        val request = ChatRequest(
            model = config.modelFor(tier),
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt),
            ),
            // Instruct the model to emit JSON; pair with response_format for supported providers.
            responseFormat = ResponseFormat(type = "json_object"),
            chatTemplateKwargs = ChatTemplateKwargs(enableThinking = false),
            thinking = Thinking(type = "disabled"),
        )
        val body = json.encodeToString(ChatRequest.serializer(), request)
        val url = config.baseUrl + config.chatPath

        var last: LlmOutcome<T> = LlmOutcome.Err(LlmError.Network("no attempt"))
        var attempt = 0
        while (attempt <= MAX_RETRIES) {
            val outcome = runOnce(url, body, outputSerializer, config.apiKey)
            // Retry only transient classes; permanent errors return immediately.
            val transient = when (val err = (outcome as? LlmOutcome.Err)?.error) {
                is LlmError.Timeout, is LlmError.RateLimited, is LlmError.Network -> true
                is LlmError.Http -> err.status in 500..599
                else -> false
            }
            if (!transient) return@withContext outcome
            last = outcome
            if (attempt < MAX_RETRIES) delayBackoff(attempt)
            attempt++
        }
        last
    }

    override suspend fun completeWithTools(
        tier: LlmTier,
        systemPrompt: String,
        conversation: List<ToolMessage>,
        tools: List<ToolDefinition>,
    ): LlmOutcome<ToolTurn> = withContext(Dispatchers.IO) {
        val config = configProvider()
        if (config.apiKey.isBlank() || config.baseUrl.isBlank()) return@withContext LlmOutcome.Err(LlmError.NotConfigured)

        val messages = buildList {
            add(ChatMessage(role = "system", content = systemPrompt))
            conversation.forEach { msg ->
                when (msg) {
                    is ToolMessage.User -> add(ChatMessage(role = "user", content = msg.content))
                    is ToolMessage.Assistant -> add(ChatMessage(
                        role = "assistant",
                        content = msg.content,
                        toolCalls = msg.toolCalls.takeIf { it.isNotEmpty() }?.map { tc ->
                            ToolCallDto(id = tc.id, function = ToolCallFunction(name = tc.name, arguments = tc.arguments))
                        },
                    ))
                    is ToolMessage.ToolResult -> add(ChatMessage(
                        role = "tool",
                        content = msg.content,
                        toolCallId = msg.toolCallId,
                    ))
                }
            }
        }
        val request = ChatRequest(
            model = config.modelFor(tier),
            messages = messages,
            tools = tools.map { td ->
                ToolDefDto(function = ToolDefFunction(
                    name = td.name,
                    description = td.description,
                    parameters = json.parseToJsonElement(td.jsonSchema),
                ))
            },
            // Agent rounds keep reasoning on — multi-step tool orchestration needs it.
            chatTemplateKwargs = ChatTemplateKwargs(enableThinking = true),
            thinking = Thinking(type = "enabled"),
        )
        val body = json.encodeToString(ChatRequest.serializer(), request)
        val url = config.baseUrl + config.chatPath

        var last: LlmOutcome<ToolTurn> = LlmOutcome.Err(LlmError.Network("no attempt"))
        var attempt = 0
        while (attempt <= MAX_RETRIES) {
            val outcome = runOnceToolTurn(url, body, config.apiKey)
            val transient = when (val err = (outcome as? LlmOutcome.Err)?.error) {
                is LlmError.Timeout, is LlmError.RateLimited, is LlmError.Network -> true
                is LlmError.Http -> err.status in 500..599
                else -> false
            }
            if (!transient) return@withContext outcome
            last = outcome
            if (attempt < MAX_RETRIES) delayBackoff(attempt)
            attempt++
        }
        last
    }

    private suspend fun runOnceToolTurn(url: String, body: String, apiKey: String): LlmOutcome<ToolTurn> {
        val httpResponse: Response = try {
            toolsClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute()
        } catch (e: IOException) {
            val msg = e.message ?: "network failure"
            return if (isTimeout(e)) LlmOutcome.Err(LlmError.Timeout)
            else LlmOutcome.Err(LlmError.Network(msg))
        }
        val code = httpResponse.code
        val rawBody = httpResponse.body?.string().orEmpty()
        httpResponse.close()

        if (code == 429) return LlmOutcome.Err(LlmError.RateLimited)
        if (code == 408 || code == 504) return LlmOutcome.Err(LlmError.Timeout)
        if (!httpResponse.isSuccessful) return LlmOutcome.Err(LlmError.Http(code))

        val chat = try {
            json.decodeFromString(ChatResponse.serializer(), rawBody)
        } catch (e: Exception) {
            return LlmOutcome.Err(LlmError.InvalidResponse)
        }

        val message = chat.choices.firstOrNull()?.message
            ?: return LlmOutcome.Err(LlmError.InvalidResponse)

        val toolCalls = message.toolCalls?.map { dto ->
            ToolCall(id = dto.id, name = dto.function.name, arguments = dto.function.arguments)
        } ?: emptyList()

        return LlmOutcome.Ok(ToolTurn(content = message.content, toolCalls = toolCalls))
    }

    override fun streamText(
        tier: LlmTier,
        systemPrompt: String,
        userPrompt: String,
    ): Flow<LlmOutcome<String>> = flow {
        val config = configProvider()
        if (config.apiKey.isBlank() || config.baseUrl.isBlank()) {
            emit(LlmOutcome.Err(LlmError.NotConfigured))
            return@flow
        }
        val request = ChatRequest(
            model = config.modelFor(tier),
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt),
            ),
            // Plain-text streaming: no response_format (can't stream JSON readably). The model
            // emits raw content and the caller parses it (e.g. summary bullets line by line).
            chatTemplateKwargs = ChatTemplateKwargs(enableThinking = false),
            thinking = Thinking(type = "disabled"),
        )
        val body = json.encodeToString(ChatRequest.serializer(), request)
        val url = config.baseUrl + config.chatPath

        var attempt = 0
        while (true) {
            var sawContent = false
            var terminal: LlmOutcome.Err? = null
            streamOnce(url, body, config.apiKey).collect { outcome ->
                when (outcome) {
                    is LlmOutcome.Ok -> {
                        sawContent = true
                        emit(outcome)
                    }
                    is LlmOutcome.Err -> terminal = outcome
                }
            }
            val err = terminal
            if (err == null || sawContent || attempt >= MAX_RETRIES) {
                err?.let { emit(it) }
                return@flow
            }
            val transient = when (val e = err.error) {
                is LlmError.Timeout, is LlmError.RateLimited, is LlmError.Network -> true
                is LlmError.Http -> e.status in 500..599
                else -> false
            }
            if (!transient) {
                emit(err)
                return@flow
            }
            // Retry only before any content reached the UI; once a partial has been
            // emitted the stream is terminal so the caller sees a consistent state.
            attempt++
            delayBackoff(attempt - 1)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * One streaming attempt as a cold flow: progressive [LlmOutcome.Ok]s carry the text
     * accumulated so far (streaming reveal), terminated by a final Ok (complete text) or
     * a single Err. Handles both wire shapes: SSE `data:` chunks, and a plain
     * chat-completion JSON body that some gateways return even for `stream:true` requests.
     */
    private fun streamOnce(url: String, body: String, apiKey: String): Flow<LlmOutcome<String>> = flow {
        val response = try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute()
        } catch (e: IOException) {
            emit(if (isTimeout(e)) LlmOutcome.Err(LlmError.Timeout) else LlmOutcome.Err(LlmError.Network(e.message ?: "network failure")))
            return@flow
        }

        val code = response.code
        if (!response.isSuccessful) {
            response.close()
            emit(
                when (code) {
                    429 -> LlmOutcome.Err(LlmError.RateLimited)
                    408, 504 -> LlmOutcome.Err(LlmError.Timeout)
                    else -> LlmOutcome.Err(LlmError.Http(code))
                },
            )
            return@flow
        }

        val source = response.body?.source()
        if (source == null) {
            response.close()
            emit(LlmOutcome.Err(LlmError.InvalidResponse))
            return@flow
        }

        val accumulated = StringBuilder()
        try {
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) {
                    // Fallback: some gateways answer stream:true with a plain (non-SSE)
                    // chat.completion JSON body. Accept it as the full text in one shot.
                    val whole = runCatching { json.decodeFromString(ChatResponse.serializer(), line) }
                        .getOrNull()?.choices?.firstOrNull()?.message?.content
                    if (!whole.isNullOrEmpty()) {
                        accumulated.append(whole)
                        emit(LlmOutcome.Ok(accumulated.toString()))
                        break
                    }
                    continue
                }
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val chunk = try {
                    json.decodeFromString(StreamChunk.serializer(), data)
                } catch (e: Exception) {
                    null
                } ?: continue
                val delta = chunk.choices.firstOrNull()?.delta?.content
                if (!delta.isNullOrEmpty()) {
                    accumulated.append(delta)
                    emit(LlmOutcome.Ok(accumulated.toString()))
                }
            }
        } catch (e: IOException) {
            emit(if (isTimeout(e)) LlmOutcome.Err(LlmError.Timeout) else LlmOutcome.Err(LlmError.Network(e.message ?: "network failure")))
            return@flow
        } finally {
            response.close()
        }
        if (accumulated.isEmpty()) emit(LlmOutcome.Err(LlmError.Empty("The summary came back empty.")))
    }

    private suspend fun <T> runOnce(
        url: String,
        body: String,
        serializer: KSerializer<T>,
        apiKey: String,
    ): LlmOutcome<T> {
        val httpResponse: Response = try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute()
        } catch (e: IOException) {
            val msg = e.message ?: "network failure"
            return if (isTimeout(e)) LlmOutcome.Err(LlmError.Timeout)
            else LlmOutcome.Err(LlmError.Network(msg))
        }
        return handleResponse(httpResponse, serializer)
    }

    private suspend fun delayBackoff(attempt: Int) {
        // Exponential: 1s, 2s, 4s… capped at 8s.
        val millis = (1000L shl attempt).coerceAtMost(8_000L)
        kotlinx.coroutines.delay(millis)
    }

    private fun isTimeout(e: IOException): Boolean {
        // OkHttp wraps SocketTimeoutException; no direct type ref to avoid coupling.
        val msg = e.message.orEmpty()
        return msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true)
    }

    private fun <T> handleResponse(
        response: Response,
        serializer: KSerializer<T>,
    ): LlmOutcome<T> {
        val code = response.code
        val rawBody = response.body?.string().orEmpty()
        response.close()

        if (code == 429) return LlmOutcome.Err(LlmError.RateLimited)
        if (code == 408 || code == 504) return LlmOutcome.Err(LlmError.Timeout)
        if (!response.isSuccessful) return LlmOutcome.Err(LlmError.Http(code))

        val chat = try {
            json.decodeFromString(ChatResponse.serializer(), rawBody)
        } catch (e: Exception) {
            return LlmOutcome.Err(LlmError.InvalidResponse)
        }

        val content = chat.choices.firstOrNull()?.message?.content
            ?: return LlmOutcome.Err(LlmError.InvalidResponse)

        return try {
            LlmOutcome.Ok(json.decodeFromString(serializer, content))
        } catch (e: Exception) {
            LlmOutcome.Err(LlmError.InvalidResponse)
        }
    }

    private companion object {
        const val MAX_RETRIES = 2
    }
}
