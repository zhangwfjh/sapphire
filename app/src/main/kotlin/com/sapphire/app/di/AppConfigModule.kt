package com.sapphire.app.di

import com.sapphire.app.BuildConfig
import com.sapphire.data.di.LlmConfigProvider
import com.sapphire.domain.llm.LlmConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * App-local provider that resolves [LlmConfig] from BuildConfig fields seeded by
 * local.properties at build time (see app/build.gradle.kts). core-data depends on the
 * [LlmConfigProvider] interface; this is the only place that touches BuildConfig.
 *
 * Marked @[dagger.Binds]-equivalent via @Provides so Hilt can construct it without a
 * public constructor annotation.
 */
class BuildConfigLlmConfigProvider : LlmConfigProvider {
    override fun config(): LlmConfig {
        // LlmConfig requires baseUrl to end with '/'; tolerate missing slash from local.properties.
        val rawUrl = BuildConfig.LLM_BASE_URL.trim()
        val baseUrl = if (rawUrl.endsWith("/")) rawUrl else "$rawUrl/"
        return LlmConfig(
            baseUrl = baseUrl,
            apiKey = BuildConfig.LLM_API_KEY,
            tier1Model = BuildConfig.LLM_TIER1_MODEL,
            tier2Model = BuildConfig.LLM_TIER2_MODEL,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {

    @Provides @Singleton
    fun provideLlmConfigProvider(): LlmConfigProvider = BuildConfigLlmConfigProvider()
}
