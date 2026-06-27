package com.sapphire.data.db

/**
 * S07: projection of a saved item joined with its feed-item details for the Saved Later
 * screen. A plain Room result POJO (not an @Entity) — the columns come from the JOIN in
 * [SavedItemDao.observeSavedWithDetails]. See that query for the column-to-field mapping.
 */
data class SavedItemWithDetails(
    val itemId: String,
    val folder: String,
    val labelsJson: String,
    val savedAt: Long,
    val title: String,
    val authorHandle: String?,
    val publishedAt: Long?,
    val platformTag: String?,
    val summary: String?,
)
