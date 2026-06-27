package com.sapphire.domain.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured output of the onboarding Tier-1 call (PRD §3.1): a two-level hierarchy,
 * secondary context keywords, and recommended high-signal feeds.
 *
 * Field naming is camelCase to match the JSON schema handed to the model. Defaults are
 * empty so a partial model response still deserializes — [CurateTaxonomyUseCase] rejects
 * an empty top-level list as [LlmError.Empty].
 */
@Serializable
data class TaxonomyResponse(
    val categories: List<TaxonomyL1> = emptyList(),
)

@Serializable
data class TaxonomyL1(
    val name: String,
    val level2: List<TaxonomyL2> = emptyList(),
)

@Serializable
data class TaxonomyL2(
    val name: String,
    val keywords: List<String> = emptyList(),
    val feeds: List<TaxonomyFeed> = emptyList(),
)

@Serializable
data class TaxonomyFeed(
    val title: String,
    val url: String,
    /** rss | atom | json | rsshub. Unknown values fall back to RSS in [ReviewBuilder]. */
    val kind: String = "rss",
)
