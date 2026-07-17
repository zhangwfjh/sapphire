package com.sapphire.domain.explore

/**
 * Resolves [SearchRegion.AUTO] to a concrete [SearchRegion] by inspecting the runtime
 * locale. Pure-domain contract; the Locale-touching impl lives in core-data.
 */
interface SearchRegionResolver {
    fun resolve(): SearchRegion
}
