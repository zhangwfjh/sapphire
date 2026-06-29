package com.sapphire.domain.settings

import kotlinx.coroutines.flow.Flow

/** Theme preference. SYSTEM follows the device setting. Default DARK (dark-first identity). */
interface ThemeConfigStore {
    fun observe(): Flow<ThemePreference>
    suspend fun set(pref: ThemePreference)
}

enum class ThemePreference { SYSTEM, DARK, LIGHT }
