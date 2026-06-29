package com.sapphire.domain.reader

/**
 * Parses HTML-ish feed/extracted body into an ordered [RichBlock] list for rich rendering.
 *
 * Implementation lives in core-data (Jsoup-backed) because robust HTML tokenising is not
 * feasible in pure Kotlin; this port keeps core-domain free of the parser dependency.
 *
 * Contract: returns blocks in document order; malformed/empty input yields an empty list.
 * A non-null, non-blank input that is pure text yields a single [RichBlock.Paragraph].
 */
interface RichContentParser {
    fun parse(bodyRaw: String?): List<RichBlock>
}
