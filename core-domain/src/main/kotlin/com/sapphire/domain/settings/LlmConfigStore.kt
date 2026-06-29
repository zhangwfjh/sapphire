package com.sapphire.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * Runtime-editable LLM config (PRD §3.1 — provider swapping without rebuild). The API key
 * is exposed separately from the rest of the snapshot so the UI can mask it.
 *
 * Persistence is backed by SharedPreferences in core-data; the key is encrypted at rest.
 * On first boot, an unset store seeds from [LlmConfigBuildConfigDefaults].
 */
interface LlmConfigStore {
    fun observe(): Flow<LlmConfigSnapshot>
    fun observeApiKey(): Flow<String>
    suspend fun setApiKey(key: String)
    suspend fun setBaseUrl(url: String)
    suspend fun setTier1Model(model: String)
    suspend fun setTier2Model(model: String)
}

/** The non-secret LLM fields. The API key is NOT included. */
data class LlmConfigSnapshot(
    val baseUrl: String,
    val tier1Model: String,
    val tier2Model: String,
)

/**
 * First-boot defaults sourced from BuildConfig/local.properties. Implemented in the app
 * module (the only BuildConfig touchpoint) so core-data stays BuildConfig-free.
 */
interface LlmConfigBuildConfigDefaults {
    fun apiKey(): String
    fun baseUrl(): String
    fun tier1Model(): String
    fun tier2Model(): String
}
