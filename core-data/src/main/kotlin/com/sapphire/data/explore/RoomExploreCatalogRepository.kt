package com.sapphire.data.explore

import android.content.Context
import com.sapphire.data.db.DiscoveredFeedDao
import com.sapphire.domain.explore.ExploreCatalogRepository
import com.sapphire.domain.explore.ExploreFeed
import com.sapphire.domain.explore.ExploreSection
import com.sapphire.domain.util.normalizeSourceUrl
import com.sapphire.domain.util.parseSourceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Room + asset-backed ExploreCatalogRepository. Parses the bundled catalog asset once
 * (lazily, cached) and combines with the discovered DAO: curated rails first,
 * "Recently discovered" appended last and only when non-empty. If the asset is
 * missing/corrupt, the asset open fails, or the parser returns an empty DTO, Explore
 * degrades to the discovered rail only.
 */
class RoomExploreCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: CatalogAssetParser,
    private val discoveredDao: DiscoveredFeedDao,
) : ExploreCatalogRepository {

    @Volatile private var cachedCurated: List<ExploreSection.Curated>? = null

    override fun observeCatalog(): Flow<List<ExploreSection>> =
        discoveredDao.observeRecent(DISCOVERED_LIMIT).map { discovered ->
            val curated = curatedRails()
            val discoveredRail = discovered.takeIf { it.isNotEmpty() }?.let { pool ->
                ExploreSection.RecentlyDiscovered(
                    title = "Recently discovered",
                    feeds = pool.map {
                        ExploreFeed(
                            title = it.title,
                            url = it.url,
                            kind = it.kind,
                            description = it.description,
                            language = it.language,
                        )
                    },
                )
            }
            if (discoveredRail != null) curated + discoveredRail else curated
        }.flowOn(Dispatchers.IO)

    private fun curatedRails(): List<ExploreSection.Curated> {
        cachedCurated?.let { return it }
        val asset = runCatching {
            context.assets.open(ASSET_NAME).use { parser.parse(it) }
        }.getOrDefault(CatalogAssetDto())
        val rails = asset.domains.map { domain ->
            ExploreSection.Curated(
                title = domain.name,
                feeds = domain.feeds
                    .filter { it.url.isNotBlank() && it.title.isNotBlank() }
                    .distinctBy { normalizeSourceUrl(it.url) }
                    .map { feed ->
                        ExploreFeed(
                            title = feed.title.trim(),
                            url = feed.url.trim(),
                            kind = parseSourceKind(feed.kind),
                            description = feed.description,
                            language = feed.language,
                        )
                    },
            )
        }
        cachedCurated = rails
        return rails
    }

    companion object {
        private const val ASSET_NAME = "explore-catalog.json"
        private const val DISCOVERED_LIMIT = 20
    }
}
