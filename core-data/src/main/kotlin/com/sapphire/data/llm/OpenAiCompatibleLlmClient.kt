package com.sapphire.data.llm

import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmConfig
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import kotlinx.coroutines.Dispatchers
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
 *   structured-output path that works across OpenAI, OpenRouter, DeepSeek, and similar
 *   gateways without tool-calling support divergence.
 * - Failures are mapped to typed [LlmError]s — never thrown. Timeouts map to [LlmError.Timeout],
 *   HTTP 429 → [LlmError.RateLimited], other non-2xx → [LlmError.Http], JSON parse failure →
 *   [LlmError.InvalidResponse].
 * - [LlmConfig.NotConfigured] is returned early if the API key is blank, so the UI can render
 *   the README-config prompt instead of a confusing 401.
 */
class OpenAiCompatibleLlmClient(
    private val config: LlmConfig,
    private val json: Json,
    client: OkHttpClient,
) : LlmClient {

    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // LLMs can be slow on first token; allow up to 90s
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun <T> completeStructured(
        tier: LlmTier,
        systemPrompt: String,
        userPrompt: String,
        outputSerializer: KSerializer<T>,
    ): LlmOutcome<T> = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) return@withContext LlmOutcome.Err(LlmError.NotConfigured)

        val request = ChatRequest(
            model = config.modelFor(tier),
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt),
            ),
            // Instruct the model to emit JSON; pair with response_format for supported providers.
            responseFormat = ResponseFormat(type = "json_object"),
            // Disable chain-of-thought reasoning (Zhipu GLM emits 90s+ of reasoning_content
            // before the answer otherwise). Unknown to stock OpenAI but ignored harmlessly.
            thinking = Thinking(type = "disabled"),
        )
        val body = json.encodeToString(ChatRequest.serializer(), request)
        val url = config.baseUrl + config.chatPath

        var last: LlmOutcome<T> = LlmOutcome.Err(LlmError.Network("no attempt"))
        var attempt = 0
        while (attempt <= MAX_RETRIES) {
            val outcome = runOnce(url, body, outputSerializer)
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

    private suspend fun <T> runOnce(
        url: String,
        body: String,
        serializer: KSerializer<T>,
    ): LlmOutcome<T> {
        val httpResponse: Response = try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${config.apiKey}")
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
