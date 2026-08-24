package com.sapphire.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.sapphire.domain.settings.ThemeConfigStore
import com.sapphire.domain.settings.ThemePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SharedPrefsThemeConfigStore(
    context: Context,
    prefsName: String,
) : ThemeConfigStore {

    @Inject constructor(@ApplicationContext context: Context) : this(context, "settings_theme")

    private val entry: PrefsEntry<ThemePreference> =
        PrefsEntry.enum(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), KEY, ThemePreference.DARK)

    override fun observe(): Flow<ThemePreference> = entry.flow

    override suspend fun set(pref: ThemePreference) = entry.set(pref)

    private companion object { const val KEY = "theme_pref" }
}
