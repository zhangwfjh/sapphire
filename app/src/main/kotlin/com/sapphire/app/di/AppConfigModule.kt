package com.sapphire.app.di

import com.sapphire.app.BuildConfig
import com.sapphire.data.di.LlmConfigProvider
import com.sapphire.domain.llm.LlmConfig
import com.sapphire.domain.settings.LlmConfigBuildConfigDefaults
import com.sapphire.domain.settings.LlmConfigSnapshot
import com.sapphire.domain.settings.LlmConfigStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

/** The only BuildConfig touchpoint for LLM defaults. */
class BuildConfigLlmDefaults : LlmConfigBuildConfigDefaults {
    override fun apiKey(): String = BuildConfig.LLM_API_KEY
    override fun baseUrl(): String = BuildConfig.LLM_BASE_URL
    override fun tier1Model(): String = BuildConfig.LLM_TIER1_MODEL
    override fun tier2Model(): String = BuildConfig.LLM_TIER2_MODEL
}

/**
 * Resolves [LlmConfig] from the runtime-editable [LlmConfigStore], falling back to
 * BuildConfig defaults for any unset field. Re-reads on every [config] call so a settings
 * edit takes effect immediately for the next LLM call (no invalidation machinery).
 *
 * The store's flows emit a single value from a cached SharedPreferences read, so the
 * [runBlocking] + [first] here is a synchronous pref lookup, not a suspension hazard.
 */
class StoreBackedLlmConfigProvider(
    private val store: LlmConfigStore,
    private val defaults: LlmConfigBuildConfigDefaults,
) : LlmConfigProvider {

    override fun config(): LlmConfig {
        val apiKey = runCatching { runBlocking { store.observeApiKey().first() } }
            .getOrDefault(defaults.apiKey())
        val snap = runCatching { runBlocking { store.observe().first() } }
            .getOrDefault(
                LlmConfigSnapshot(
                    baseUrl = ensureTrailingSlash(defaults.baseUrl()),
                    tier1Model = defaults.tier1Model(),
                    tier2Model = defaults.tier2Model(),
                ),
            )
        return LlmConfig(
            baseUrl = ensureTrailingSlash(snap.baseUrl),
            apiKey = apiKey,
            tier1Model = snap.tier1Model,
            tier2Model = snap.tier2Model,
        )
    }

    private fun ensureTrailingSlash(url: String) = if (url.endsWith("/")) url else "$url/"
}

@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {

    @Provides @Singleton
    fun provideLlmConfigDefaults(): LlmConfigBuildConfigDefaults = BuildConfigLlmDefaults()

    @Provides @Singleton
    fun provideLlmConfigProvider(
        store: LlmConfigStore,
        defaults: LlmConfigBuildConfigDefaults,
    ): LlmConfigProvider = StoreBackedLlmConfigProvider(store, defaults)
}
