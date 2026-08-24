package com.sapphire.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sapphire.domain.settings.LlmConfigBuildConfigDefaults
import com.sapphire.domain.settings.LlmConfigSnapshot
import com.sapphire.domain.settings.LlmConfigStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Encrypted-SharedPreferences-backed [LlmConfigStore]. The API key lives in an encrypted
 * file (AES-GCM); non-secret fields live in a plain prefs file. On first read, seeds from
 * [defaults] (BuildConfig) so existing users upgrade without losing their config.
 *
 * The secret [SharedPreferences] is provided by [secretPrefsProvider] so tests can inject
 * a plain instance (Robolectric lacks the Android Keystore that EncryptedSharedPreferences needs).
 *
 * Hot flows so cross-component consumers like [com.sapphire.app.di.StoreBackedLlmConfigProvider]
 * and the Settings UI react to runtime edits. Non-secret fields are [PrefsEntry] definitions;
 * the encrypted key and the snapshot merge are this store's own weight.
 */
class SharedPrefsLlmConfigStore private constructor(
    private val defaults: LlmConfigBuildConfigDefaults,
    plainPrefs: SharedPreferences,
    secretPrefsProvider: () -> SharedPreferences,
) : LlmConfigStore {

    @Inject
    constructor(@ApplicationContext context: Context, defaults: LlmConfigBuildConfigDefaults) : this(
        defaults = defaults,
        plainPrefs = context.getSharedPreferences("settings_llm", Context.MODE_PRIVATE),
        secretPrefsProvider = { createEncryptedPrefs(context) },
    )

    // Test constructor: explicit prefs names + secret provider.
    constructor(
        context: Context,
        defaults: LlmConfigBuildConfigDefaults,
        secretPrefsProvider: (Context) -> SharedPreferences,
        plainPrefsName: String,
    ) : this(
        defaults = defaults,
        plainPrefs = context.getSharedPreferences(plainPrefsName, Context.MODE_PRIVATE),
        secretPrefsProvider = { secretPrefsProvider(context) },
    )

    private val secretPrefs: SharedPreferences by lazy { secretPrefsProvider() }

    private val baseUrl = PrefsEntry.string(
        plainPrefs, KEY_BASE_URL,
        default = ensureTrailingSlash(defaults.baseUrl()),
        canonicalize = ::ensureTrailingSlash,
    )
    private val tier1 = PrefsEntry.string(plainPrefs, KEY_TIER1, defaults.tier1Model())
    private val tier2 = PrefsEntry.string(plainPrefs, KEY_TIER2, defaults.tier2Model())
    private val apiKey = PrefsEntry.string(secretPrefs, KEY_API_KEY, defaults.apiKey())

    override fun observe(): Flow<LlmConfigSnapshot> =
        combine(baseUrl.flow, tier1.flow, tier2.flow) { url, t1, t2 ->
            LlmConfigSnapshot(baseUrl = url, tier1Model = t1, tier2Model = t2)
        }

    override fun observeApiKey(): Flow<String> = apiKey.flow

    override suspend fun setApiKey(key: String) = apiKey.set(key)

    override suspend fun setBaseUrl(url: String) = baseUrl.set(url)

    override suspend fun setTier1Model(model: String) = tier1.set(model)

    override suspend fun setTier2Model(model: String) = tier2.set(model)

    private fun ensureTrailingSlash(url: String) = if (url.endsWith("/")) url else "$url/"

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TIER1 = "tier1_model"
        const val KEY_TIER2 = "tier2_model"
        fun createEncryptedPrefs(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
            context,
            "settings_llm_secret",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
