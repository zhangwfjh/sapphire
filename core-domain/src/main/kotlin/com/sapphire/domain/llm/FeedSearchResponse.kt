package com.sapphire.domain.llm

import kotlinx.serialization.Serializable

/**
 * Structured output of the Explore search Tier-1 call. A flat list of feed matches
 * for a topic query. Field naming is camelCase to match the JSON schema handed to
 * the model. Defaults are empty so a partial response still deserializes.
 *
 * `kind` is the raw string from the model; [com.sapphire.domain.util.parseSourceKind]
 * normalizes it to a [com.sapphire.domain.model.SourceKind].
 */
@Serializable
data class FeedSearchResponse(
    val results: List<FeedSearchResult> = emptyList(),
)

@Serializable
data class FeedSearchResult(
    val title: String,
    val url: String,
    val kind: String = "rss",
    val description: String? = null,
)
