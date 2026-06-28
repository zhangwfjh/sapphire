package com.sapphire.domain.reader

import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.llm.ClassificationResponse
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.llm.SummaryResponse
import com.sapphire.domain.llm.TranslateResponse
import com.sapphire.domain.llm.TranslatedParagraph
import com.sapphire.domain.util.LlmCacheKey

/**
 * PRD §4.2 lazy-compute cache port. Implementations store LLM op payloads keyed by
 * [LlmCacheKey] so a re-open of the same (item, op, model) is a free cache hit.
 *
 * The port is intentionally stringly-typed (`payloadJson`) so domain stays free of Room —
 * the data layer owns serialization boundaries. Domain owns the key derivation.
 */
interface ReaderOpCache {
    suspend fun get(key: String): String?
    suspend fun put(itemId: String, key: String, op: String, payloadJson: String)
}

/**
 * Read-only access to a single feed item's persisted fields the reader needs (body,
 * current classification). The [FeedRepository] timeline flow already exposes the card
 * fields; this is the focused reader lookup.
 */
interface ReaderItemStore {
    suspend fun item(itemId: String): FeedItem?
    /** Persist the Tier-1 classification back onto the row (PRD §3.5 dynamic macro source). */
    suspend fun setClassification(itemId: String, classification: String)
}

/**
 * S03 reader-sheet lazy LLM orchestration (PRD §3.4 / §3.5, architecture §9).
 *
 * Guarantees the lazy-compute + idempotent-cache contract:
 * - **No op touches an unread item's cost until the reader opens** — methods are only
 *   invoked by the reader ViewModel on open / tap.
 * - **Cache-first:** every op checks [ReaderOpCache] before calling the LLM. A hit returns
 *   immediately; a miss calls the LLM, persists the payload, and returns it. Re-open is free.
 * - **Classification is persisted** onto the feed item row (via [ReaderItemStore]) so the
 *   macro set is stable across re-opens without a second Tier-1 call.
 *
 * All outcomes are typed [LlmOutcome]s — no exceptions cross the boundary, mirroring
 * [com.sapphire.domain.onboarding.CurateTaxonomyUseCase].
 *
 * @param tier1ModelVersion folded into the cache key so a model swap invalidates payloads.
 * @param targetLanguage BCP-47-ish locale tag for translate (e.g. "zh"); the human name is
 *   resolved by the caller via [translateLanguageName].
 */
class ReaderOpsUseCase(
    private val llm: LlmClient,
    private val cache: ReaderOpCache,
    private val items: ReaderItemStore,
    private val json: kotlinx.serialization.json.Json,
    private val tier1ModelVersion: String,
    private val tier2ModelVersion: String,
) {

    /**
     * Tier-1 classification (PRD §3.5). On reader open: if the item already has a
     * persisted classification, return it (no call). Else check the cache, then call.
     * Persists the result onto the item row so subsequent opens skip entirely.
     *
     * @param paragraphs resolved article body to classify. `null` (default) parses the
     *  item's feed body (`bodyRaw ?: summary ?: title`); non-null uses the supplied
     *  paragraphs verbatim (the reader forwards the full extracted body when available).
     */
    suspend fun classify(itemId: String, paragraphs: List<String>? = null): LlmOutcome<ClassificationResponse> {
        val item = items.item(itemId)
            ?: return LlmOutcome.Err(com.sapphire.domain.llm.LlmError.Empty("Item not found."))

        item.classification?.takeIf { it.isNotBlank() }?.let {
            return LlmOutcome.Ok(ClassificationResponse(it, 1.0))
        }

        val key = LlmCacheKey.compute(itemId, OP_CLASSIFY, tier1ModelVersion)
        cache.get(key)?.let { cached ->
            return decode(cached, ClassificationResponse.serializer())
        }

        val body = (paragraphs ?: BodyParagraphParser.parse(item.bodyRaw ?: item.summary ?: item.title))
            .joinToString("\n\n")
            .ifBlank { item.title }

        return when (val outcome = llm.completeStructured(
            tier = LlmTier.TIER1_FAST,
            systemPrompt = ClassificationResponse.SYSTEM_PROMPT,
            userPrompt = body,
            outputSerializer = ClassificationResponse.serializer(),
        )) {
            is LlmOutcome.Err -> outcome
            is LlmOutcome.Ok -> {
                val cls = outcome.value.classification.ifBlank { ClassificationLabels.OTHER }
                val payload = ClassificationResponse(cls, outcome.value.confidence)
                cache.put(itemId, key, OP_CLASSIFY, json.encodeToString(ClassificationResponse.serializer(), payload))
                items.setClassification(itemId, cls)
                LlmOutcome.Ok(payload)
            }
        }
    }

    /**
     * Tier-2 summary (PRD §3.4 [✨ Summary]). Cache-first; exactly three bullets.
     *
     * @param paragraphs resolved article body to summarize. `null` (default) parses the
     *  item's feed body; non-null uses the supplied paragraphs verbatim (the reader
     *  forwards the full extracted body when available).
     */
    suspend fun summarize(itemId: String, paragraphs: List<String>? = null): LlmOutcome<SummaryResponse> {
        val key = LlmCacheKey.compute(itemId, OP_SUMMARY, tier2ModelVersion)
        cache.get(key)?.let { return decode(it, SummaryResponse.serializer()) }

        val body = if (paragraphs != null) {
            paragraphs.joinToString("\n\n")
        } else {
            readerBody(itemId) ?: return missingItem()
        }
        return when (val outcome = llm.completeStructured(
            tier = LlmTier.TIER2_DEEP,
            systemPrompt = SummaryResponse.SYSTEM_PROMPT,
            userPrompt = body,
            outputSerializer = SummaryResponse.serializer(),
        )) {
            is LlmOutcome.Err -> outcome
            is LlmOutcome.Ok -> {
                cache.put(itemId, key, OP_SUMMARY, json.encodeToString(SummaryResponse.serializer(), outcome.value))
                outcome
            }
        }
    }

    /**
     * Tier-2 paragraph-aligned translate (PRD §3.4 [🌐 Translate]). Cache-first, keyed
     * per target language. The body is split into paragraphs and handed to the model with
     * the instruction to preserve boundaries; the returned [TranslateResponse] is the
     * interleaved-original/target stream the UI renders.
     *
     * @param paragraphs resolved article paragraphs to translate. `null` (default) parses
     *  the item's feed body; non-null uses the supplied paragraphs verbatim, preserving
     *  paragraph boundaries for the aligned output (the reader forwards the full extracted
     *  body when available).
     */
    suspend fun translate(itemId: String, targetLanguage: String, paragraphs: List<String>? = null): LlmOutcome<TranslateResponse> {
        val op = "$OP_TRANSLATE:$targetLanguage"
        val key = LlmCacheKey.compute(itemId, op, tier2ModelVersion)
        cache.get(key)?.let { return decode(it, TranslateResponse.serializer()) }

        val item = items.item(itemId) ?: return missingItem()
        val paras = paragraphs ?: BodyParagraphParser.parse(item.bodyRaw ?: item.summary ?: item.title)
        if (paras.isEmpty()) return LlmOutcome.Ok(TranslateResponse(emptyList()))

        val userPrompt = paras.joinToString("\n\n") { it }
        return when (val outcome = llm.completeStructured(
            tier = LlmTier.TIER2_DEEP,
            systemPrompt = TranslatedParagraph.systemPrompt(translateLanguageName(targetLanguage)),
            userPrompt = userPrompt,
            outputSerializer = TranslateResponse.serializer(),
        )) {
            is LlmOutcome.Err -> outcome
            is LlmOutcome.Ok -> {
                cache.put(itemId, key, op, json.encodeToString(TranslateResponse.serializer(), outcome.value))
                outcome
            }
        }
    }

    private suspend fun readerBody(itemId: String): String? {
        val item = items.item(itemId) ?: return null
        return BodyParagraphParser.parse(item.bodyRaw ?: item.summary ?: item.title)
            .joinToString("\n\n")
            .ifBlank { item.title }
    }

    private fun missingItem(): LlmOutcome<Nothing> =
        LlmOutcome.Err(com.sapphire.domain.llm.LlmError.Empty("Item not found."))

    private fun <T> decode(payload: String, serializer: kotlinx.serialization.KSerializer<T>): LlmOutcome<T> =
        try {
            LlmOutcome.Ok(json.decodeFromString(serializer, payload))
        } catch (e: Exception) {
            LlmOutcome.Err(com.sapphire.domain.llm.LlmError.InvalidResponse)
        }

    companion object {
        internal const val OP_CLASSIFY = "classification"
        internal const val OP_SUMMARY = "summary"
        internal const val OP_TRANSLATE = "translate"

        /**
         * Map a locale tag to the human language name handed to the translate prompt.
         * Falls back to the raw tag so an unknown locale still produces a usable prompt.
         */
        fun translateLanguageName(localeTag: String): String = when (localeTag.lowercase()) {
            "zh", "zh-cn", "zh-hans" -> "Chinese (Simplified)"
            "zh-tw", "zh-hant" -> "Chinese (Traditional)"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "ar" -> "Arabic"
            "hi" -> "Hindi"
            "pt", "pt-br" -> "Portuguese"
            "ru" -> "Russian"
            else -> localeTag
        }
    }
}
