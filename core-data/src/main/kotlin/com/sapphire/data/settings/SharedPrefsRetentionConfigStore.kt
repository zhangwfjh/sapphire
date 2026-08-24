package com.sapphire.data.settings

import android.content.Context
import com.sapphire.domain.settings.RetentionConfigStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SharedPrefsRetentionConfigStore(
    context: Context,
    prefsName: String,
) : RetentionConfigStore {

    @Inject constructor(@ApplicationContext context: Context) : this(context, "settings_retention")

    private val entry = PrefsEntry.int(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), KEY_DAYS, DEFAULT_DAYS)

    override fun observe(): Flow<Int> = entry.flow

    override suspend fun setDays(days: Int) = entry.set(days)

    private companion object {
        const val KEY_DAYS = "retention_days"
        const val DEFAULT_DAYS = 30
    }
}
