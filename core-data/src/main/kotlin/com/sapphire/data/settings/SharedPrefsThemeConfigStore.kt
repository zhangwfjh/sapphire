package com.sapphire.data.settings
import android.content.Context
import com.sapphire.domain.settings.ThemeConfigStore
import com.sapphire.domain.settings.ThemePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SharedPrefsThemeConfigStore(
    context: Context,
    private val prefsName: String,
) : ThemeConfigStore {
    @Inject constructor(@ApplicationContext context: Context) : this(context, "settings_theme")
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    override fun observe(): Flow<ThemePreference> = flow {
        val name = prefs.getString(KEY, null) ?: ThemePreference.DARK.name
        emit(runCatching { ThemePreference.valueOf(name) }.getOrDefault(ThemePreference.DARK))
    }.flowOn(Dispatchers.IO)
    override suspend fun set(pref: ThemePreference) = withContext(Dispatchers.IO) { prefs.edit().putString(KEY, pref.name).apply() }
    private companion object { const val KEY = "theme_pref" }
}
