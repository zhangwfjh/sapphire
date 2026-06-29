package com.sapphire.domain.reader

/**
 * Extracts the readable article body from a full HTML page. Lazy on first reader-open
 * (PRD §4.2 — no work on unread items). Failure is always non-fatal: callers fall back
 * to the feed body via [RichContentParser].
 *
 * [Ok.html] is the Readability-cleaned article HTML (block tags preserved); the caller
 * parses it to [com.sapphire.domain.reader.RichBlock]s for rendering and to a plain-text
 * paragraph view for the Tier-2 LLM ops.
 */
interface ArticleExtractor {
    suspend fun extract(url: String): ExtractionOutcome
}

sealed interface ExtractionOutcome {
    data class Ok(
        val title: String?,
        val html: String,
        val byline: String?,
    ) : ExtractionOutcome

    sealed interface Err : ExtractionOutcome {
        data object Unreachable : Err   // network / unknown host / timeout
        data object Empty : Err         // fetched but extracted nothing usable
        data object Blocked : Err       // 4xx / paywall / robots-style block
    }
}
