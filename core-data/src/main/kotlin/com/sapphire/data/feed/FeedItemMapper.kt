package com.sapphire.data.feed

import com.sapphire.data.db.FeedItemEntity
import com.sapphire.data.db.FeedItemWithSource
import com.sapphire.domain.model.FeedItem
/**
 * Entity ↔ domain mapping for feed items. Domain [FeedItem] is the timeline/UI shape;
 * [FeedItemEntity] carries the extra persistence columns (body_raw, classification, etc.)
 * used elsewhere; this mapping reads only the timeline columns.
 *
 * [title]/[summary] are re-decoded here as a defensive heal: rows written by older builds
 * went through a `stripHtml` that only handled a handful of named entities, so numeric
 * references like `&#8217;` were persisted raw (e.g. `Anthropic&#8217;s`). Re-decoding on
 * read fixes existing data in place without a schema bump or re-ingest. It is idempotent on
 * already-clean text: a stored `Tom & Jerry` has no `&...;` run and passes through untouched.
 */
internal fun FeedItemEntity.toDomain(): FeedItem = FeedItem(
    hashUuid = hashUuid,
    sourceId = sourceId,
    categoryId = categoryId,
    title = title.decodeHtmlEntities(),
    summary = summary?.decodeHtmlEntities(),
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
    url = url,
)

internal fun FeedItemWithSource.toDomain(): FeedItem =
    item.toDomain().copy(sourceTitle = sourceTitle)
