package com.sapphire.domain.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * S03 reader-sheet LLM operations (PRD §3.4 / §3.5, architecture §9).
 *
 * Lazy compute contract: no op fires until the reader sheet opens or the user taps a
 * macro. Classification is Tier-1 (fast); summary/translate/macros are Tier-2 (deep)
 * per the routing table in architecture §4. Every result is cached in `LlmCache` keyed
 * by [LlmCacheKey] so re-opening the same item's same op is free (PRD §4.2 idempotent).
 *
 * Each op has a typed structured-output DTO (parsed, never free-text) and a companion
 * system prompt that hands the model the exact JSON shape.
 */

// region Classification (§3.5) — Tier-1, fires on reader open ----------------

/**
 * Content classification (PRD §3.5). The `classification` string is the macro-dispatch
 * key consumed by [com.sapphire.domain.reader.ClassificationMacros]; `confidence` is
 * advisory (0..1) and currently unused by the UI.
 */
@Serializable
data class ClassificationResponse(
    val classification: String = "",
    val confidence: Double = 0.0,
) {
    companion object {
        /** System prompt for the classification call. */
        internal val SYSTEM_PROMPT = """
You are Sapphire's content classifier. Read the article body and assign exactly one
classification label.

Labels (use the exact token):
- "News Article"
- "Academic Paper"
- "Tech Blog"
- "Opinion / Essay"
- "Tutorial / Guide"
- "Other"

Output STRICT JSON: {"classification": string, "confidence": number}
- confidence is 0.0..1.0
- No prose outside the JSON object.
""".trimIndent()
    }
}

// region Summary (§3.4) — Tier-2, on [✨ Summary] tap -----------------------

/**
 * Three-bullet executive summary (PRD §3.4). Exactly three bullets streamed token by token
 * and pinned beneath the header metadata once complete. The DTO is also the cached payload
 * (PRD §4.2) so a re-open renders instantly without re-streaming.
 */
@Serializable
data class SummaryResponse(
    val bullets: List<String> = emptyList(),
) {
    companion object {
        /**
         * Plain-text (non-JSON) prompt: streaming can't reveal partial JSON readably, so the
         * model emits one bullet per line and [ReaderOpsUseCase] parses the lines. Output is
         * deliberately bare — no numbering/markdown — so deltas render cleanly as they arrive.
         */
        internal val STREAM_PROMPT = """
            You are Sapphire's summarizer. Produce a three-bullet executive summary of the article.

            Rules:
            - Exactly 3 bullets, one per line.
            - Each bullet is one sentence, <= 24 words.
            - Output ONLY the three sentences, each on its own line.
            - No numbering, no markdown, no bullet characters, no preamble, no notes.
        """.trimIndent()
    }
}

/**
 * One progressive frame of a streaming summary: [bullets] are the completed (newline-ended)
 * lines; [partial] is the line still being typed (empty between lines / once complete).
 */
data class SummaryStreamFrame(
    val bullets: List<String> = emptyList(),
    val partial: String = "",
)

// region Translate (§3.4) — Tier-2, on [🌐 Translate] tap -------------------

/**
 * Ordered source paragraphs grouped by reader region, input to paragraph-aligned
 * translate (PRD §3.4). The reader assembles these from the title, the AI summary
 * bullets, the feed brief, and the extracted full article; [flat] yields them in document
 * order so paragraph *i* of the streamed output maps back to a known region + index.
 */
data class TranslateRegions(
    val title: List<String> = emptyList(),
    val summary: List<String> = emptyList(),
    val brief: List<String> = emptyList(),
    val article: List<String> = emptyList(),
) {
    /** All paragraphs in document order: title → summary → brief → article. */
    fun flat(): List<String> = title + summary + brief + article
}

/**
 * Regioned bilingual translation (PRD §3.4). Each list holds the translations for one
 * reader region, paragraph-aligned with the matching [TranslateRegions] input; the UI
 * renders each beneath its original. Defaults are empty so a cache payload written by an
 * older single-body shape decodes harmlessly (all-empty → caller re-translates).
 */
@Serializable
data class TranslateResponse(
    val title: String = "",
    val summary: List<String> = emptyList(),
    val brief: List<String> = emptyList(),
    val article: List<String> = emptyList(),
) {
    companion object {
        /**
         * Sentinel line separating translated paragraphs in the plain-text streaming
         * output. Picked to be unlikely in natural prose so the parser can split safely.
         */
        const val STREAM_DELIMITER = "|||"

        /**
         * Streaming system prompt. The model emits ONLY the [targetLanguageName]
         * translations, one paragraph per segment separated by a [STREAM_DELIMITER] line,
         * in the same order as the input. Paragraphs stay 1:1 aligned; the caller
         * re-partitions the segments into regions for the interleaved render.
         */
        fun streamSystemPrompt(targetLanguageName: String, paragraphCount: Int): String = """
You are Sapphire's translator. Translate each paragraph into $targetLanguageName.

Output ONLY the $targetLanguageName translations — no original text, no numbering, no quotation marks, no labels, no commentary.
Separate each paragraph's translation with a line containing exactly: $STREAM_DELIMITER
Emit exactly $paragraphCount translations, one per input paragraph, in order.
Never merge or split paragraphs.
""".trimIndent()
    }
}

/**
 * One progressive frame of a streaming translation, partitioned by reader region. Each
 * list holds the translated paragraphs resolved so far (finalized segments followed by the
 * segment still being typed); index *i* aligns with text block *i* of that region.
 */
data class TranslateStreamFrame(
    val title: String = "",
    val summary: List<String> = emptyList(),
    val brief: List<String> = emptyList(),
    val article: List<String> = emptyList(),
)
