package com.sapphire.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.sapphire.domain.browser.BrowserConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * SharedPreferences-backed [BrowserConfig]. The optional browser-service base URL lives in
 * plain prefs (non-secret; the render service is the user's own server or a metered API).
 *
 * Hot flow so Settings reacts to runtime edits and [com.sapphire.data.browser.HttpBrowserClient]
 * reads a consistent snapshot. Key definitions over [PrefsEntry].
 */
class SharedPrefsBrowserConfig private constructor(
    prefs: SharedPreferences,
) : BrowserConfig {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(DEFAULT_PREFS_NAME, Context.MODE_PRIVATE),
    )

    // Test constructor: isolated prefs file per test.
    constructor(context: Context, prefsName: String) : this(
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
    )

    private val entry = PrefsEntry.string(prefs, KEY_BASE_URL, default = "", canonicalize = ::normalize)

    override fun baseUrl(): String = entry.current

    override fun observeBaseUrl(): Flow<String> = entry.flow

    override suspend fun setBaseUrl(baseUrl: String) = entry.set(baseUrl)

    private fun normalize(url: String): String = url.trim().let {
        if (it.isNotEmpty() && !it.endsWith("/")) "$it/" else it
    }

    private companion object {
        const val DEFAULT_PREFS_NAME = "settings_browser"
        const val KEY_BASE_URL = "base_url"
    }
}
