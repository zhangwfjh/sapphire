package com.sapphire.domain.agent

import kotlinx.serialization.Serializable

/**
 * One synthesized feed item produced by an agent run. The LLM returns these as
 * structured JSON; the worker maps each into a [com.sapphire.data.db.FeedItemEntity].
 *
 * [url] is optional — the LLM may or may not cite a source. When absent, the worker
 * synthesizes a stable dedup URL (`agent://<jobId>#<runEpoch>`) so re-runs don't
 * double-insert. [bodyRaw] is the full synthesized text (the "article" the reader shows).
 */
@Serializable
data class AgentSourceRef(
    val title: String,
    val url: String,
)

@Serializable
data class AgentSynthesisItem(
    val title: String,
    val summary: String? = null,
    val body: String? = null,
    val url: String? = null,
    val sources: List<AgentSourceRef> = emptyList(),
    /**
     * Cover image for the timeline card (`FeedItemEntity.mediaUrl`). Set by the loop at
     * acceptance from images actually present on the item's cited pages — never by the
     * model, so a cover can't be fabricated.
     */
    val coverUrl: String? = null,
)

/**
 * Structured-output envelope for the synth LLM call. The LLM returns
 * `{ "items": [ { "title": "...", "summary": "...", "body": "...", "url": "..." } ] }`.
 * Empty `items` is valid (agent found nothing worth filing).
 */
@Serializable
data class AgentSynthesisResult(
    val items: List<AgentSynthesisItem> = emptyList(),
)
