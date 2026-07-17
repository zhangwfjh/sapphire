package com.sapphire.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sapphire.domain.explore.SearchConfig
import com.sapphire.domain.explore.SearchRegion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * SharedPreferences-backed [SearchConfig]. Region lives in plain prefs (non-secret); the
 * optional Tavily key also lives in plain prefs for v1 simplicity (the required LLM key
 * uses EncryptedSharedPreferences; migrating this optional key is mechanical and deferred).
 *
 * Both flows are hot ([MutableStateFlow]) so Settings reacts to runtime edits and the
 * [com.sapphire.data.explore.CompositeSearchClient] reads a consistent snapshot.
 *
 * Test constructor accepts a [prefsName] so tests use an isolated file.
 */
class PrefsSearchConfig private constructor(
    private val prefs: SharedPreferences,
) : SearchConfig {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(DEFAULT_PREFS_NAME, Context.MODE_PRIVATE),
    )

    // Test constructor: isolated prefs file per test.
    constructor(context: Context, prefsName: String) : this(
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
    )

    private val _region = MutableStateFlow(readRegion())
    private val _tavilyKey = MutableStateFlow(readTavilyKey())

    override fun region(): SearchRegion = _region.value
    override fun observeRegion(): Flow<SearchRegion> = _region.asStateFlow()
    override fun observeTavilyKey(): Flow<String> = _tavilyKey.asStateFlow()

    override suspend fun setRegion(region: SearchRegion) = withContext(Dispatchers.IO) {
        prefs.edit { putString(KEY_REGION, region.name) }
        _region.value = region
    }

    override suspend fun setTavilyKey(key: String) = withContext(Dispatchers.IO) {
        prefs.edit { putString(KEY_TAVILY, key) }
        _tavilyKey.value = key
    }

    private fun readRegion(): SearchRegion =
        prefs.getString(KEY_REGION, null)?.let { runCatching { SearchRegion.valueOf(it) }.getOrNull() }
            ?: SearchRegion.AUTO

    private fun readTavilyKey(): String = prefs.getString(KEY_TAVILY, null) ?: ""

    private companion object {
        const val DEFAULT_PREFS_NAME = "settings_search"
        const val KEY_REGION = "region"
        const val KEY_TAVILY = "tavily_key"
    }
}
