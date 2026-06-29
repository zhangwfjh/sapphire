package com.sapphire.data.settings

import android.content.Context
import androidx.core.content.edit
import com.sapphire.domain.settings.ThemeConfigStore
import com.sapphire.domain.settings.ThemePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SharedPrefsThemeConfigStore(
    context: Context,
    private val prefsName: String,
) : ThemeConfigStore {

    @Inject constructor(@ApplicationContext context: Context) : this(context, "settings_theme")

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(readPref())

    override fun observe(): Flow<ThemePreference> = _state.asStateFlow()

    override suspend fun set(pref: ThemePreference) = withContext(Dispatchers.IO) {
        prefs.edit { putString(KEY, pref.name) }
        _state.value = pref
    }

    private fun readPref(): ThemePreference {
        val name = prefs.getString(KEY, null) ?: ThemePreference.DARK.name
        return runCatching { ThemePreference.valueOf(name) }.getOrDefault(ThemePreference.DARK)
    }

    private companion object { const val KEY = "theme_pref" }
}
