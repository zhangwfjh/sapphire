package com.sapphire.domain.model

/**
 * S07 Save Later repository row (PRD §3.4 `[📁 Save Later]`). A promoted FeedItem that
 * survives the 30-day retention purge (architecture §7). One-to-one with its FeedItem via
 * [itemId] (the hash UUID).
 *
 * `labels` is a free-form key-value map (PRD §3.4 "custom key-value labeling"); `folder`
 * is a structural bucket independent of the feed taxonomy (PRD §3.4 "structural folders
 * independent of the active feed lifecycle").
 *
 * The domain model is label-typed; the data layer owns JSON encoding into `labels_json`.
 */
data class SavedItem(
    val itemId: String,
    val folder: String,
    val labels: Map<String, String> = emptyMap(),
    val savedAt: Long,
)
