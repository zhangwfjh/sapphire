package com.sapphire.domain.reader

/**
 * Extracts the readable article body from a full HTML page. Lazy on first reader-open
 * (PRD §4.2 — no work on unread items). Failure is always non-fatal: callers fall back
 * to [BodyParagraphParser] on the feed body.
 *
 * The [Ok.paragraphs] follow the same non-empty, document-order contract as
 * [BodyParagraphParser.parse] so paragraph-aligned translate keeps working.
 */
interface ArticleExtractor {
    suspend fun extract(url: String): ExtractionOutcome
}

sealed interface ExtractionOutcome {
    data class Ok(
        val title: String?,
        val paragraphs: List<String>,
        val byline: String?,
    ) : ExtractionOutcome

    sealed interface Err : ExtractionOutcome {
        data object Unreachable : Err   // network / unknown host / timeout
        data object Empty : Err         // fetched but extracted nothing usable
        data object Blocked : Err       // 4xx / paywall / robots-style block
    }
}
