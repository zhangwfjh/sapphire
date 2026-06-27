package com.sapphire.data.feed

import com.sapphire.data.db.FeedItemEntity
import com.sapphire.domain.model.FeedItem
/**
 * Entity ↔ domain mapping for feed items. Domain [FeedItem] is the timeline/UI shape;
 * [FeedItemEntity] carries the extra persistence columns (body_raw, classification, etc.)
 * later slices populate. S02 only reads the timeline columns.
 */
internal fun FeedItemEntity.toDomain(): FeedItem = FeedItem(
    hashUuid = hashUuid,
    sourceId = sourceId,
    categoryId = categoryId,
    title = title,
    summary = summary,
    authorHandle = authorHandle,
    publishedAt = publishedAt,
    bodyRaw = bodyRaw,
    fetchedAt = fetchedAt,
    platformTag = platformTag,
    mediaUrl = mediaUrl,
    readState = readState,
    savedLater = savedLater,
    classification = classification,
    densityScore = densityScore,
    agentTag = agentTag,
)
