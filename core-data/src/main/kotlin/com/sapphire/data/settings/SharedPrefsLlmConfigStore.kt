package com.sapphire.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sapphire.domain.settings.LlmConfigBuildConfigDefaults
import com.sapphire.domain.settings.LlmConfigSnapshot
import com.sapphire.domain.settings.LlmConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Encrypted-SharedPreferences-backed [LlmConfigStore]. The API key lives in an encrypted
 * file (AES-GCM); non-secret fields live in a plain prefs file. On first read, seeds from
 * [defaults] (BuildConfig) so existing users upgrade without losing their config.
 *
 * The secret [SharedPreferences] is provided by [secretPrefsProvider] so tests can inject
 * a plain instance (Robolectric lacks the Android Keystore that EncryptedSharedPreferences needs).
 */
class SharedPrefsLlmConfigStore private constructor(
    context: Context,
    private val defaults: LlmConfigBuildConfigDefaults,
    private val plainPrefs: SharedPreferences,
    private val secretPrefsProvider: () -> SharedPreferences,
) : LlmConfigStore {

    @Inject
    constructor(context: Context, defaults: LlmConfigBuildConfigDefaults) : this(
        context = context,
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
        context = context,
        defaults = defaults,
        plainPrefs = context.getSharedPreferences(plainPrefsName, Context.MODE_PRIVATE),
        secretPrefsProvider = { secretPrefsProvider(context) },
    )

    private val secretPrefs: SharedPreferences by lazy { secretPrefsProvider() }

    override fun observe(): Flow<LlmConfigSnapshot> = flow {
        emit(LlmConfigSnapshot(baseUrl = readBaseUrl(), tier1Model = readTier1(), tier2Model = readTier2()))
    }.flowOn(Dispatchers.IO)

    override fun observeApiKey(): Flow<String> = flow { emit(readApiKey()) }.flowOn(Dispatchers.IO)

    override suspend fun setApiKey(key: String) = withContext(Dispatchers.IO) { secretPrefs.edit().putString(KEY_API_KEY, key).apply() }
    override suspend fun setBaseUrl(url: String) = withContext(Dispatchers.IO) { plainPrefs.edit().putString(KEY_BASE_URL, ensureTrailingSlash(url)).apply() }
    override suspend fun setTier1Model(model: String) = withContext(Dispatchers.IO) { plainPrefs.edit().putString(KEY_TIER1, model).apply() }
    override suspend fun setTier2Model(model: String) = withContext(Dispatchers.IO) { plainPrefs.edit().putString(KEY_TIER2, model).apply() }

    private fun readApiKey(): String = secretPrefs.getString(KEY_API_KEY, null) ?: defaults.apiKey()
    private fun readBaseUrl(): String = ensureTrailingSlash(plainPrefs.getString(KEY_BASE_URL, null) ?: defaults.baseUrl())
    private fun readTier1(): String = plainPrefs.getString(KEY_TIER1, null) ?: defaults.tier1Model()
    private fun readTier2(): String = plainPrefs.getString(KEY_TIER2, null) ?: defaults.tier2Model()
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
