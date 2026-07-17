package com.sapphire.domain.explore

import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for web-search settings. Implementations live in core-data
 * (SharedPreferences).
 *
 * - [region] is a hot-path sync snapshot used by [CompositeSearchClient] once per search.
 * - [observeRegion] / [observeTavilyKey] are hot flows so the Settings UI reacts to
 *   runtime edits.
 * - Empty Tavily key = use no-key chain (DDG/Baidu). Non-empty = Tavily tried first.
 */
interface SearchConfig {
    fun region(): SearchRegion
    fun observeRegion(): Flow<SearchRegion>
    fun observeTavilyKey(): Flow<String>
    suspend fun setRegion(region: SearchRegion)
    suspend fun setTavilyKey(key: String)
}
