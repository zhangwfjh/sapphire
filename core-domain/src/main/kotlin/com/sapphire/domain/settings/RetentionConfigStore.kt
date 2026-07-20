package com.sapphire.domain.settings

import kotlinx.coroutines.flow.Flow

/** Runtime-editable retention window. Default 30 days. */
interface RetentionConfigStore {
    fun observe(): Flow<Int>
    suspend fun setDays(days: Int)
}
