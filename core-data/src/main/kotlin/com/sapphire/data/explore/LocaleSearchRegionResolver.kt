package com.sapphire.data.explore

import com.sapphire.domain.explore.SearchRegion
import com.sapphire.domain.explore.SearchRegionResolver
import java.util.Locale
import javax.inject.Inject

/**
 * Resolves [SearchRegion.AUTO] by inspecting [Locale.getDefault]. Any `zh*` language
 * (zh-CN, zh-Hans, zh-TW, zh-HK, …) maps to [SearchRegion.CHINA]; everything else maps
 * to [SearchRegion.WEST].
 *
 * Lives in core-data because core-domain must stay JVM-pure (no Locale use beyond
 * `java.util.Locale`, which is fine, but we want a single impl site).
 */
class LocaleSearchRegionResolver @Inject constructor() : SearchRegionResolver {

    override fun resolve(): SearchRegion =
        if (Locale.getDefault().language == "zh") SearchRegion.CHINA else SearchRegion.WEST
}
