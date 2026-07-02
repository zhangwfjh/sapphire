package com.sapphire.data.settings

import android.content.Context
import androidx.core.content.edit
import com.sapphire.domain.settings.TranslateViewMode
import com.sapphire.domain.settings.UiPrefsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SharedPrefsUiPrefsStore(
    context: Context,
    private val prefsName: String,
) : UiPrefsStore {

    @Inject constructor(@ApplicationContext context: Context) : this(context, "settings_ui")

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _density = MutableStateFlow(readDensity())
    private val _translateView = MutableStateFlow(readTranslateView())

    override fun observeDensity(): Flow<UiPrefsStore.FeedDensity> = _density.asStateFlow()
    override fun observeTranslateView(): Flow<TranslateViewMode> = _translateView.asStateFlow()

    override suspend fun setDensity(density: UiPrefsStore.FeedDensity) = withContext(Dispatchers.IO) {
        prefs.edit { putBoolean(KEY_DENSITY, density.isDense) }
        _density.value = density
    }

    override suspend fun setTranslateView(mode: TranslateViewMode) = withContext(Dispatchers.IO) {
        prefs.edit { putString(KEY_TR_VIEW, mode.name) }
        _translateView.value = mode
    }

    private fun readDensity(): UiPrefsStore.FeedDensity =
        UiPrefsStore.FeedDensity(prefs.getBoolean(KEY_DENSITY, UiPrefsStore.FeedDensity.DEFAULT.isDense))

    private fun readTranslateView(): TranslateViewMode {
        val name = prefs.getString(KEY_TR_VIEW, null) ?: TranslateViewMode.BILINGUAL.name
        return runCatching { TranslateViewMode.valueOf(name) }.getOrDefault(TranslateViewMode.BILINGUAL)
    }

    private companion object {
        const val KEY_DENSITY = "feed_density_dense"
        const val KEY_TR_VIEW = "translate_view_mode"
    }
}
