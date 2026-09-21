package com.sapphire.data.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format contract for the reasoning ("thinking") controls. Sapphire disables reasoning
 * on every call except agent tool-calling rounds. Two dialects carry the same intent —
 * `chat_template_kwargs.enable_thinking` (vLLM convention) and `thinking.type` (GLM) — and
 * each is read by exactly one provider family. A @SerialName typo silently drops a field,
 * the model reverts to its default reasoning mode, and calls blow past read timeouts —
 * so both dialects' wire shapes are pinned here.
 */
class LlmRequestWireFormatTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private fun wire(request: ChatRequest) =
        json.encodeToString(ChatRequest.serializer(), request).let(json::parseToJsonElement).jsonObject

    @Test
    fun `reasoning-disabled request carries both dialects off`() {
        val wire = wire(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage(role = "user", content = "hi")),
                chatTemplateKwargs = ChatTemplateKwargs(enableThinking = false),
                thinking = Thinking(type = "disabled"),
            ),
        )
        assertFalse(wire.getValue("chat_template_kwargs").jsonObject.getValue("enable_thinking").jsonPrimitive.boolean)
        assertEquals("disabled", wire.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `reasoning-enabled request carries both dialects on`() {
        val wire = wire(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage(role = "user", content = "hi")),
                chatTemplateKwargs = ChatTemplateKwargs(enableThinking = true),
                thinking = Thinking(type = "enabled"),
            ),
        )
        assertTrue(wire.getValue("chat_template_kwargs").jsonObject.getValue("enable_thinking").jsonPrimitive.boolean)
        assertEquals("enabled", wire.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `null reasoning controls are omitted from the wire`() {
        val wire = wire(ChatRequest(model = "m", messages = listOf(ChatMessage(role = "user", content = "hi"))))
        assertFalse(wire.containsKey("chat_template_kwargs"))
        assertFalse(wire.containsKey("thinking"))
    }
}
