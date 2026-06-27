package com.sapphire.domain.explore

import com.sapphire.domain.model.SourceKind
import kotlinx.coroutines.flow.Flow

/**
 * A feed surfaced in Explore (catalog or discovered). Carries the data a card needs:
 * title, url, kind, an optional description, and optional language.
 */
data class ExploreFeed(
    val title: String,
    val url: String,
    val kind: SourceKind,
    val description: String?,
    val language: String?,
)

/**
 * A horizontal rail in the Explore browse view. Curated rails come from the bundled
 * catalog; the "Recently discovered" rail is appended last and only when non-empty.
 */
sealed interface ExploreSection {
    val title: String
    val feeds: List<ExploreFeed>

    data class Curated(
        override val title: String,
        override val feeds: List<ExploreFeed>,
    ) : ExploreSection

    data class RecentlyDiscovered(
        override val title: String,
        override val feeds: List<ExploreFeed>,
    ) : ExploreSection
}

/**
 * Merges the bundled curated catalog with the on-device discovered pool into one
 * observable list of rails. Curated rails first; "Recently discovered" appended last
 * and omitted entirely when the pool is empty (a brand-new user sees only curated rails).
 */
interface ExploreCatalogRepository {
    fun observeCatalog(): Flow<List<ExploreSection>>
}
