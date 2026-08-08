package com.sapphire.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sapphire.domain.browser.BrowserConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * SharedPreferences-backed [BrowserConfig]. The optional browser-service base URL lives in
 * plain prefs (non-secret; the render service is the user's own server or a metered API).
 *
 * Hot [MutableStateFlow] so Settings reacts to runtime edits and [com.sapphire.data.browser.HttpBrowserClient]
 * reads a consistent snapshot.
 *
 * Mirrors [PrefsSearchConfig].
 */
class SharedPrefsBrowserConfig private constructor(
    private val prefs: SharedPreferences,
) : BrowserConfig {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(DEFAULT_PREFS_NAME, Context.MODE_PRIVATE),
    )

    // Test constructor: isolated prefs file per test.
    constructor(context: Context, prefsName: String) : this(
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
    )

    private val _baseUrl = MutableStateFlow(readBaseUrl())

    override fun baseUrl(): String = _baseUrl.value
    override fun observeBaseUrl(): Flow<String> = _baseUrl.asStateFlow()

    override suspend fun setBaseUrl(baseUrl: String) = withContext(Dispatchers.IO) {
        val normalized = baseUrl.trim().let { if (it.isNotEmpty() && !it.endsWith("/")) "$it/" else it }
        prefs.edit { putString(KEY_BASE_URL, normalized) }
        _baseUrl.value = normalized
    }

    private fun readBaseUrl(): String = prefs.getString(KEY_BASE_URL, null)?.trim()?.let {
        if (it.isNotEmpty() && !it.endsWith("/")) "$it/" else it
    } ?: ""

    private companion object {
        const val DEFAULT_PREFS_NAME = "settings_browser"
        const val KEY_BASE_URL = "base_url"
    }
}
