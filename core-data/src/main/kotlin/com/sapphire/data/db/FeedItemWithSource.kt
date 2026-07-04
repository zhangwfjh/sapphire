package com.sapphire.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * JOIN result: feed_item row + the source title from the source table.
 * Used by timeline queries that surface the feed source name in dense cards.
 */
data class FeedItemWithSource(
    @Embedded val item: FeedItemEntity,
    @ColumnInfo(name = "source_title") val sourceTitle: String?,
)
