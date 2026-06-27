package com.sapphire.data.db

/**
 * Row result for the per-category counts query. Room maps the query columns
 * (`categoryId`, `unread`, `total`) onto this POJO by column name, and lifts the
 * result into `Map<String, CategoryCount>` keyed by `categoryId`.
 */
data class CategoryCount(
    val categoryId: String,
    val unread: Int,
    val total: Int,
)

/** Same shape, keyed by `source_id` for per-source counts. */
data class SourceCount(
    val sourceId: String,
    val unread: Int,
    val total: Int,
)
