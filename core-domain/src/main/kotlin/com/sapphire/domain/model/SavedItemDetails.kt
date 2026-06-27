package com.sapphire.domain.model

/**
 * S07: a SavedItem joined with its FeedItem display details, for the Saved Later screen.
 * Domain projection — the data layer maps the Room JOIN ([com.sapphire.data.db.SavedItemWithDetails])
 * into this. Only the fields the saved-items list renders.
 */
data class SavedItemDetails(
    val itemId: String,
    val folder: String,
    val labels: Map<String, String>,
    val savedAt: Long,
    val title: String,
    val authorHandle: String?,
    val publishedAt: Long?,
    val platformTag: String?,
    val summary: String?,
)
