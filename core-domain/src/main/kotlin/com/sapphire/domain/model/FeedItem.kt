package com.sapphire.domain.model

/**
 * A single content item in the unified timeline. Identity is [hashUuid] — the global
 * SHA-256 of normalized `(sourceId, canonicalUrl | title+publishedAt)` per PRD §3.2 /
 * architecture §3. It is the primary key downstream slices key off (dedup, reader ops,
 * retention, save-later).
 *
 * Classification / bodyParsed / densityScore are populated by later slices (S03/S04);
 * the timeline only needs title/summary/metadata/readState.
 */
data class FeedItem(
    val hashUuid: String,
    val sourceId: String,
    val categoryId: String,
    val title: String,
    val summary: String?,
    val bodyRaw: String? = null,
    val authorHandle: String?,
    val publishedAt: Long?,
    val fetchedAt: Long,
    val platformTag: String?,
    val mediaUrl: String?,
    val readState: ReadState = ReadState.UNREAD,
    val savedLater: Boolean = false,
    val classification: String? = null,
    val densityScore: Double? = null,
    /** Agent/synth items only — surfaces the `[✨ AI Search Agent]` badge (S04/S05). */
    val agentTag: String? = null,
    /** Canonical URL of the original article; null for synth items. Tapped in the reader
     *  to open the source externally (Intent.ACTION_VIEW). */
    val url: String? = null,
)
