package com.sapphire.domain.llm

/**
 * Provider-agnostic LLM config. Seeded from BuildConfig in the app module (local.properties)
 * and provided via Hilt. Routing tiering (PRD §4.2) maps each operation to a [LlmTier].
 */
data class LlmConfig(
    /** Base URL must end with '/'. OpenAI-compatible chat completions path is appended. */
    val baseUrl: String,
    val apiKey: String,
    val tier1Model: String,
    val tier2Model: String,
    /** Path appended to [baseUrl]; OpenAI-compatible default. */
    val chatPath: String = "chat/completions",
) {
    init {
        require(baseUrl.endsWith("/")) { "baseUrl must end with '/': $baseUrl" }
    }

    fun modelFor(tier: LlmTier): String = when (tier) {
        LlmTier.TIER1_FAST -> tier1Model
        LlmTier.TIER2_DEEP -> tier2Model
    }
}
