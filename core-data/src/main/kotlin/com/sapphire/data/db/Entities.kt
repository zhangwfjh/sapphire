package com.sapphire.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sapphire.domain.model.HealthState
import com.sapphire.domain.model.ReadMechanism
import com.sapphire.domain.model.ReadState
import com.sapphire.domain.model.SourceKind

@Entity(
    tableName = "topic",
    indices = [Index(value = ["phrase"])],
)
data class TopicEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "phrase") val phrase: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "category",
    foreignKeys = [
        ForeignKey(
            entity = TopicEntity::class,
            parentColumns = ["id"],
            childColumns = ["topic_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["topic_id"]),
        Index(value = ["parent_id"]),
        Index(value = ["topic_id", "level"]),
    ],
)
data class CategoryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "topic_id") val topicId: String,
    @ColumnInfo(name = "level") val level: Int,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

@Entity(
    tableName = "keyword",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["category_id"])],
)
data class KeywordEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "user_added") val userAdded: Boolean,
)

@Entity(
    tableName = "source",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["category_id"]),
        // Unique per (category_id, url) so re-onboarding the same topic doesn't duplicate.
        Index(value = ["category_id", "url"], unique = true),
    ],
)
data class SourceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "topic_id") val topicId: String,
    @ColumnInfo(name = "kind") val kind: SourceKind,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "config_json") val configJson: String? = null,
    @ColumnInfo(name = "health_state") val healthState: HealthState = HealthState.OK,
    @ColumnInfo(name = "last_fetched_at") val lastFetchedAt: Long? = null,
    @ColumnInfo(name = "last_error_at") val lastErrorAt: Long? = null,
)

@Entity(
    tableName = "feed_item",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["source_id"]),
        // Unified timeline query: ORDER BY publishedAt DESC, scope by category (folder view).
        Index(value = ["category_id", "published_at"]),
        // Retention purge (S07): WHERE readState=READ AND savedLater=0 AND fetchedAt<?.
        Index(value = ["read_state", "fetched_at"]),
    ],
)
data class FeedItemEntity(
    @PrimaryKey @ColumnInfo(name = "hash_uuid") val hashUuid: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "summary") val summary: String? = null,
    @ColumnInfo(name = "body_raw") val bodyRaw: String? = null,
    @ColumnInfo(name = "author_handle") val authorHandle: String? = null,
    @ColumnInfo(name = "published_at") val publishedAt: Long? = null,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "platform_tag") val platformTag: String? = null,
    @ColumnInfo(name = "media_url") val mediaUrl: String? = null,
    @ColumnInfo(name = "read_state") val readState: ReadState = ReadState.UNREAD,
    @ColumnInfo(name = "saved_later") val savedLater: Boolean = false,
    @ColumnInfo(name = "classification") val classification: String? = null,
    @ColumnInfo(name = "density_score") val densityScore: Double? = null,
    @ColumnInfo(name = "agent_tag") val agentTag: String? = null,
    @ColumnInfo(name = "url") val url: String? = null,
)

@Entity(
    tableName = "read_log",
    foreignKeys = [
        ForeignKey(
            entity = FeedItemEntity::class,
            parentColumns = ["hash_uuid"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["item_id", "mechanism"]),
    ],
)
data class ReadLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "marked_at") val markedAt: Long,
    @ColumnInfo(name = "mechanism") val mechanism: ReadMechanism,
)

/**
 * S03 reader-op cache (architecture §3 `LlmCache`). PK = the [com.sapphire.domain.util.LlmCacheKey]
 * SHA-256 of `(itemId, op, modelVersion)`. Makes reader ops idempotent across re-opens —
 * a second open of the same (item, op, model) is a cache hit, never a re-spend (PRD §4.2).
 *
 * `payload_json` holds the raw structured-output JSON; the domain layer owns decoding.
 * Rows cascade-delete with their FeedItem so retention purge (S07) sweeps stale caches.
 */
@Entity(
    tableName = "llm_cache",
    foreignKeys = [
        ForeignKey(
            entity = FeedItemEntity::class,
            parentColumns = ["hash_uuid"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["cache_key"], unique = true), Index(value = ["item_id"])],
)
data class LlmCacheEntity(
    @PrimaryKey @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "op") val op: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * S07 Save Later repository (PRD §3.4 `[📁 Save Later]`, architecture §3/§7). PK = the
 * FeedItem hash — 1:1 with its parent item. Rows survive the 30-day retention purge
 * (architecture §7 exempts saved items); cascade-delete with their FeedItem only.
 *
 * `labels_json` stores the custom key-value labels as a JSON object string; the domain
 * layer owns encoding/decoding. `folder` is a free-form bucket name (PRD §3.4 "structural
 * folders independent of the active feed lifecycle").
 */
@Entity(
    tableName = "saved_item",
    foreignKeys = [
        ForeignKey(
            entity = FeedItemEntity::class,
            parentColumns = ["hash_uuid"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["folder"])],
)
data class SavedItemEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "folder") val folder: String,
    @ColumnInfo(name = "labels_json") val labelsJson: String = "{}",
    @ColumnInfo(name = "saved_at") val savedAt: Long,
)

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
    @ColumnInfo(name = "body_html") val bodyHtml: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
