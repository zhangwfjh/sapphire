package com.sapphire.domain.explore

/**
 * One subscribable feed surfaced by Explore search — from the URL shortcut or the
 * deterministic topic harvest. Plain domain data; never serialized.
 *
 * `kind` is a raw string ("rss" | "atom" | "json") sniffed from the feed itself;
 * [com.sapphire.domain.util.parseSourceKind] normalizes it to a
 * [com.sapphire.domain.model.SourceKind].
 */
data class FeedSearchResult(
    val title: String,
    val url: String,
    val kind: String = "rss",
    val description: String? = null,
)
