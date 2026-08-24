package com.sapphire.data.settings

import android.content.Context
import com.sapphire.domain.settings.TranslateViewMode
import com.sapphire.domain.settings.UiPrefsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SharedPrefsUiPrefsStore(
    context: Context,
    prefsName: String,
) : UiPrefsStore {

    @Inject constructor(@ApplicationContext context: Context) : this(context, "settings_ui")

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    // Density persists as its Boolean core (isDense).
    private val density = PrefsEntry.bool(prefs, KEY_DENSITY, UiPrefsStore.FeedDensity.DEFAULT.isDense)
    private val translateView = PrefsEntry.enum(prefs, KEY_TR_VIEW, TranslateViewMode.BILINGUAL)

    override fun observeDensity(): Flow<UiPrefsStore.FeedDensity> =
        density.flow.map { UiPrefsStore.FeedDensity(it) }

    override fun observeTranslateView(): Flow<TranslateViewMode> = translateView.flow

    override suspend fun setDensity(d: UiPrefsStore.FeedDensity) = density.set(d.isDense)

    override suspend fun setTranslateView(mode: TranslateViewMode) = translateView.set(mode)

    private companion object {
        const val KEY_DENSITY = "feed_density_dense"
        const val KEY_TR_VIEW = "translate_view_mode"
    }
}
