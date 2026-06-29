package com.sapphire.data.settings

import android.content.Context
import com.sapphire.domain.settings.RetentionConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class SharedPrefsRetentionConfigStore(
    context: Context,
    private val prefsName: String = "settings_retention",
) : RetentionConfigStore {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    override fun observe(): Flow<Int> = flow { emit(prefs.getInt(KEY_DAYS, DEFAULT_DAYS)) }.flowOn(Dispatchers.IO)
    override suspend fun setDays(days: Int) = withContext(Dispatchers.IO) { prefs.edit().putInt(KEY_DAYS, days).apply() }
    private companion object { const val KEY_DAYS = "retention_days"; const val DEFAULT_DAYS = 30 }
}
