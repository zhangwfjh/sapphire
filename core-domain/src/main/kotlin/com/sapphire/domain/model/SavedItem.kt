package com.sapphire.domain.model

/**
 * Save Later repository row (`[📁 Save Later]`). A promoted FeedItem that survives the
 * 30-day retention purge. One-to-one with its FeedItem via [itemId] (the hash UUID).
 *
 * `labels` is a free-form key-value map (custom key-value labeling); `folder` is a
 * structural bucket independent of the feed taxonomy (structural folders independent of
 * the active feed lifecycle).
 *
 * The domain model is label-typed; the data layer owns JSON encoding into `labels_json`.
 */
data class SavedItem(
    val itemId: String,
    val folder: String,
    val labels: Map<String, String> = emptyMap(),
    val savedAt: Long,
)
