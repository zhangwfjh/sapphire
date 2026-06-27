package com.sapphire.domain.feed

import com.sapphire.domain.model.FeedItem

/**
 * In-feed free-text search (design: docs/superpowers/specs/2026-06-24-in-feed-text-search-design.md).
 *
 * A pure, in-memory substring filter over the already-ingested timeline. Blank query is the
 * identity (full timeline) — matches today's behavior. Matching is case-insensitive across
 * [FeedItem.title], [FeedItem.summary], and [FeedItem.authorHandle]. `bodyRaw`,
 * `classification`, and `platformTag` are intentionally NOT searched: body is large/noisy
 * (the reader sheet is the place to dig into it), and the others don't reflect "does this
 * item match what I'm looking for."
 *
 * The query is treated as a single substring, not tokenized — `"ai infra"` matches the literal
 * `"ai infra"`, not `"ai"` AND `"infra"`. This is deliberate for v1: tokenization invites
 * ranking/scoring decisions that belong to a future search-as-a-feature slice.
 */
fun FeedItem.matchesQuery(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val needle = q.lowercase()
    if (title.lowercase().contains(needle)) return true
    summary?.lowercase()?.contains(needle)?.let { if (it) return true }
    authorHandle?.lowercase()?.contains(needle)?.let { if (it) return true }
    return false
}

/** Filter a timeline list by [matchesQuery]. Preserves order. */
fun List<FeedItem>.filterByQuery(query: String): List<FeedItem> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.matchesQuery(q) }
}
