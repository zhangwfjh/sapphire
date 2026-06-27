package com.sapphire.domain.model

/**
 * A content feed/source attached to an L2 category. [hashUuid] identity for feed *items*
 * is S02's concern (FeedItem); here we only need stable source rows.
 * Unique per (categoryId, url) — see the Room index in SourceEntity.
 */
data class Source(
    val id: String,
    val categoryId: String,
    val topicId: String,
    val kind: SourceKind,
    val url: String,
    val title: String?,
    val configJson: String? = null,
    val enabled: Boolean = true,
    val healthState: HealthState = HealthState.OK,
    val lastFetchedAt: Long? = null,
    val lastErrorAt: Long? = null,
)
