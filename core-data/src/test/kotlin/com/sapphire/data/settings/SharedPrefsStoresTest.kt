package com.sapphire.data.settings

import androidx.test.core.app.ApplicationProvider
import com.sapphire.domain.settings.LlmConfigBuildConfigDefaults
import com.sapphire.domain.settings.ThemePreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedPrefsStoresTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val defaults = object : LlmConfigBuildConfigDefaults {
        override fun apiKey() = "default-key"
        override fun baseUrl() = "https://default.example.com/v1/"
        override fun tier1Model() = "default-tier1"
        override fun tier2Model() = "default-tier2"
    }
    // Plain secret prefs for testing (avoids the Android Keystore which Robolectric lacks).
    private fun testSecretPrefs(name: String): android.content.SharedPreferences =
        ctx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)

    @Test
    fun `retention defaults to 30 and round-trips`() = runTest {
        val store = SharedPrefsRetentionConfigStore(ctx, prefsName = "test-retention")
        assertEquals(30, store.observe().first())
        store.setDays(60)
        assertEquals(60, store.observe().first())
    }

    @Test
    fun `theme defaults to DARK and round-trips`() = runTest {
        val store = SharedPrefsThemeConfigStore(ctx, prefsName = "test-theme")
        assertEquals(ThemePreference.DARK, store.observe().first())
        store.set(ThemePreference.LIGHT)
        assertEquals(ThemePreference.LIGHT, store.observe().first())
    }

    @Test
    fun `llm store seeds from defaults on first read`() = runTest {
        val store = SharedPrefsLlmConfigStore(
            ctx, defaults,
            secretPrefsProvider = { testSecretPrefs("test-llm-seed-secret") },
            plainPrefsName = "test-llm-seed",
        )
        val snap = store.observe().first()
        assertEquals("https://default.example.com/v1/", snap.baseUrl)
        assertEquals("default-tier1", snap.tier1Model)
        assertEquals("default-tier2", snap.tier2Model)
        assertEquals("default-key", store.observeApiKey().first())
    }

    @Test
    fun `llm store round-trips overrides`() = runTest {
        val store = SharedPrefsLlmConfigStore(
            ctx, defaults,
            secretPrefsProvider = { testSecretPrefs("test-llm-override-secret") },
            plainPrefsName = "test-llm-override",
        )
        store.setApiKey("new-key")
        store.setBaseUrl("https://new.example.com/v1")
        store.setTier1Model("new-tier1")
        store.setTier2Model("new-tier2")
        assertEquals("new-key", store.observeApiKey().first())
        val snap = store.observe().first()
        assertEquals("https://new.example.com/v1/", snap.baseUrl)
        assertEquals("new-tier1", snap.tier1Model)
        assertEquals("new-tier2", snap.tier2Model)
    }

    @Test
    fun `llm store normalizes baseUrl to end with slash`() = runTest {
        val store = SharedPrefsLlmConfigStore(
            ctx, defaults,
            secretPrefsProvider = { testSecretPrefs("test-llm-slash-secret") },
            plainPrefsName = "test-llm-slash",
        )
        store.setBaseUrl("https://no-slash.example.com/v1")
        assertEquals("https://no-slash.example.com/v1/", store.observe().first().baseUrl)
    }
}
