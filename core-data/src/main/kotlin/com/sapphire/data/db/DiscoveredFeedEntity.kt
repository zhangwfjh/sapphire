package com.sapphire.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sapphire.domain.model.SourceKind

/**
 * Explore discovered pool. Local-only catalog of feeds the user has actually subscribed
 * to, surfacing a "Recently discovered" rail. PK is a stable hash of the normalized URL
 * so the same feed discovered via different paths dedupes. subscribe_count increments on
 * re-subscribe into a new category. No foreign keys: a discovered feed survives deletion
 * of the source/category it was first subscribed into — it is a catalog entry, not a
 * dependent row.
 */
@Entity(
    tableName = "discovered_feed",
    indices = [Index(value = ["url"], unique = true)],
)
data class DiscoveredFeedEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "kind") val kind: SourceKind,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "domain_hint") val domainHint: String? = null,
    @ColumnInfo(name = "language") val language: String? = null,
    @ColumnInfo(name = "discovered_at") val discoveredAt: Long,
    @ColumnInfo(name = "subscribe_count") val subscribeCount: Int = 1,
)
