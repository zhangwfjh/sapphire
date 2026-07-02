package com.sapphire.domain.settings

import kotlinx.coroutines.flow.Flow

/** Persists UI-only prefs: timeline density + reader translate-view mode. */
interface UiPrefsStore {
    data class FeedDensity(val isDense: Boolean) {
        companion object { val DEFAULT = FeedDensity(true) }
    }

    fun observeDensity(): Flow<FeedDensity>
    suspend fun setDensity(density: FeedDensity)

    fun observeTranslateView(): Flow<TranslateViewMode>
    suspend fun setTranslateView(mode: TranslateViewMode)
}
