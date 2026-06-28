package com.sapphire.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Side cache for extracted article bodies (reader readability). PK = the FeedItem hash;
 * 1:1 with its parent item. Cascade-deletes with the FeedItem so retention purge sweeps
 * stale bodies for free. `paragraphs_json` is a JSON array of strings; the data layer
 * owns encoding/decoding.
 */
@Entity(
    tableName = "article_body",
    foreignKeys = [
        ForeignKey(
            entity = FeedItemEntity::class,
            parentColumns = ["hash_uuid"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["item_id"])],
)
data class ArticleBodyEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "paragraphs_json") val paragraphsJson: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
