package com.sapphire.domain.explore

import com.sapphire.domain.model.SourceKind

/**
 * Write/read surface for the on-device discovered pool. Called by
 * [com.sapphire.domain.source.SourceRepository] implementations on every successful
 * `addSource` so the catalog grows from real subscribes.
 *
 * `record` is idempotent on URL: a re-subscribe into a new category increments the
 * existing row's `subscribe_count` rather than creating a duplicate.
 */
interface DiscoveredFeedRepository {

    /**
     * Record (or bump) a discovered feed. Safe to call for every subscribe regardless
     * of provenance (search, catalog, manual paste).
     */
    suspend fun record(
        title: String,
        url: String,
        kind: SourceKind,
        description: String?,
        domainHint: String?,
        language: String?,
    )
}
