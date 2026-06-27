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
 * Three-bullet executive summary (PRD §3.4). Exactly three bullets; the UI pins them
 * beneath the header metadata.
 */
@Serializable
data class SummaryResponse(
    val bullets: List<String> = emptyList(),
) {
    companion object {
        internal val SYSTEM_PROMPT = """
You are Sapphire's summarizer. Produce a three-bullet executive summary of the article.

Rules:
- Exactly 3 bullets.
- Each bullet is one sentence, <= 24 words.
- Output STRICT JSON: {"bullets": [string, string, string]}
- No prose outside the JSON object.
""".trimIndent()
    }
}

// region Translate (§3.4) — Tier-2, on [🌐 Translate] tap -------------------

/**
 * Paragraph-aligned bilingual translation (PRD §3.4). The reader interleaves
 * `[original, target]` per paragraph; the target language is the device locale resolved
 * by the caller (not the model).
 */
@Serializable
data class TranslateResponse(
    val paragraphs: List<TranslatedParagraph> = emptyList(),
)

@Serializable
data class TranslatedParagraph(
    val original: String = "",
    val target: String = "",
) {
    companion object {
        /**
         * System prompt for paragraph-aligned translation. [targetLanguageName] is injected
         * by the caller (e.g. "Chinese (Simplified)").
         */
        fun systemPrompt(targetLanguageName: String): String = """
You are Sapphire's translator. Translate each paragraph into $targetLanguageName.

Rules:
- Preserve paragraph boundaries exactly: paragraph N in the input maps to paragraph N in the output.
- Keep the original text verbatim in "original"; put the translation in "target".
- Do not merge or split paragraphs.
- Output STRICT JSON: {"paragraphs": [{"original": string, "target": string}, ...]}
- No prose outside the JSON object.
""".trimIndent()
    }
}
