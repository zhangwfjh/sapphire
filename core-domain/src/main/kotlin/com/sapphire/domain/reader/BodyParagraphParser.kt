package com.sapphire.domain.reader

/**
 * Splits the reader body into paragraphs for the paragraph-aligned translate op
 * (PRD §3.4) and for rendering. `bodyRaw` from ingestion is HTML-ish feed content;
 * this normalizes it to a clean, non-empty paragraph list.
 *
 * The contract:
 * - Split on blank lines and/or block-level tags (`<p>`, `<br>` clusters, `</div>`).
 * - Strip remaining inline HTML so Compose renders plain text.
 * - Drop empty paragraphs so paragraph N in the input maps to paragraph N in the
 *   translate output (the model is instructed not to merge/split, so we must not either).
 *
 * Pure (no Android deps) so it's exhaustively unit-testable.
 */
object BodyParagraphParser {

    private val BLOCK_CLOSE = Regex("(?i)</(p|div|section|article|li|h[1-6])>")
    private val BR_CLUSTER = Regex("(?i)(<br\\s*/?>\\s*){2,}")

    /**
     * @param bodyRaw the raw feed body (HTML or plain text). Null/blank → empty list.
     * @return trimmed, non-empty paragraphs in document order.
     */
    fun parse(bodyRaw: String?): List<String> {
        if (bodyRaw.isNullOrBlank()) return emptyList()

        // Normalize block closers + <br> clusters into a stable paragraph delimiter,
        // then strip residual tags from each segment.
        val normalized = bodyRaw
            .replace(BR_CLUSTER, "\n\n")
            .replace(BLOCK_CLOSE, "\n\n")
            .replace("(?i)<br\\s*/?>".toRegex(), "\n")

        return normalized
            .split("\n\n")
            .map { stripTags(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** Strip HTML tags + decode the common entity set. Mirrors the card summary stripper. */
    private fun stripTags(fragment: String): String {
        val out = StringBuilder(fragment.length)
        var inTag = false
        for (c in fragment) {
            when {
                c == '<' -> inTag = true
                c == '>' -> inTag = false
                !inTag -> out.append(c)
            }
        }
        return out.toString()
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
    }
}
