package com.sapphire.domain.reader

import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.llm.ClassificationResponse
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.llm.SummaryResponse
import com.sapphire.domain.llm.SummaryStreamFrame
import com.sapphire.domain.llm.TranslateRegions
import com.sapphire.domain.llm.TranslateResponse
import com.sapphire.domain.llm.TranslateStreamFrame
import com.sapphire.domain.util.LlmCacheKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Lazy-compute cache port. Implementations store LLM op payloads keyed by
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
    /** Persist the Tier-1 classification back onto the row (dynamic macro source). */
    suspend fun setClassification(itemId: String, classification: String)
}

/**
 * Reader-sheet lazy LLM orchestration.
 *
 * Guarantees the lazy-compute + idempotent-cache contract:
 * - **No op touches an unread item's cost until the reader opens** — methods are only
 *   invoked by the reader ViewModel on open / tap.
 * - **Cache-first:** every op checks [ReaderOpCache] before calling the LLM. A hit returns
 *   immediately; a miss calls the LLM, persists the payload, and returns it. Re-open is free.
 * - **Classification is persisted** onto the feed item row (via [ReaderItemStore]) so the
 *   macro set is stable across re-opens without a second Tier-1 call.
 *
 * All outcomes are typed [LlmOutcome]s — no exceptions cross the boundary.
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
     * Tier-1 classification. On reader open: if the item already has a
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
     * Tier-2 streaming summary ([✨ Summary], streaming reveal). Cache-first: a hit
     * emits a single complete frame and skips the LLM entirely (re-open is free). On a miss the
     * plain-text stream is parsed line by line — completed bullets versus the line being typed —
     * and each token chunk emits a [SummaryStreamFrame]; the final frame carries the full bullet
     * list and is persisted so the next open is a cache hit.
     *
     * @param paragraphs resolved article body to summarize. `null` (default) parses the item's
     *  feed body; non-null uses the supplied paragraphs verbatim (the reader forwards the full
     *  extracted body when available).
     */
    fun summarizeStreaming(
        itemId: String,
        paragraphs: List<String>? = null,
    ): Flow<LlmOutcome<SummaryStreamFrame>> = flow {
        val key = LlmCacheKey.compute(itemId, OP_SUMMARY, tier2ModelVersion)
        cache.get(key)?.let {
            val cached = decode(it, SummaryResponse.serializer())
            if (cached is LlmOutcome.Ok) emit(LlmOutcome.Ok(SummaryStreamFrame(cached.value.bullets)))
            else emit(castedErr(cached))
            return@flow
        }

        val body = if (paragraphs != null) {
            paragraphs.joinToString("\n\n")
        } else {
            readerBody(itemId) ?: run { emit(missingItem()); return@flow }
        }

        var failed = false
        var fullText: String? = null
        llm.streamText(
            tier = LlmTier.TIER2_DEEP,
            systemPrompt = SummaryResponse.STREAM_PROMPT,
            userPrompt = body,
        ).collect { outcome ->
            when (outcome) {
                is LlmOutcome.Err -> { failed = true; emit(outcome) }
                is LlmOutcome.Ok -> {
                    fullText = outcome.value
                    emit(LlmOutcome.Ok(parseSummaryFrame(outcome.value)))
                }
            }
        }
        if (!failed && fullText != null) {
            val bullets = parseSummaryBullets(fullText)
            cache.put(itemId, key, OP_SUMMARY, json.encodeToString(SummaryResponse.serializer(), SummaryResponse(bullets)))
            emit(LlmOutcome.Ok(SummaryStreamFrame(bullets)))
        }
    }

    private fun castedErr(err: LlmOutcome<*>): LlmOutcome<Nothing> =
        (err as LlmOutcome.Err).let { LlmOutcome.Err(it.error) }

    /**
     * Tier-2 paragraph-aligned streaming translate ([🌐 Translate], streaming
     * reveal), covering every reader region — title, AI summary, brief, and full article.
     * Cache-first, keyed per target language: a hit emits a single complete frame and skips
     * the LLM entirely (re-open is free). On a miss the [TranslateRegions] are flattened in
     * document order and streamed segment by segment; each chunk is re-partitioned by region
     * counts into a [TranslateStreamFrame], and the final regioned frame is persisted so the
     * next open is a cache hit.
     */
    fun translateStreaming(
        itemId: String,
        targetLanguage: String,
        regions: TranslateRegions,
    ): Flow<LlmOutcome<TranslateStreamFrame>> = flow {
        val op = "$OP_TRANSLATE:$targetLanguage"
        val key = LlmCacheKey.compute(itemId, op, tier2ModelVersion)
        cache.get(key)?.let {
            val cached = decode(it, TranslateResponse.serializer())
            if (cached is LlmOutcome.Ok) emit(LlmOutcome.Ok(cached.value.toFrame()))
            else emit(castedErr(cached))
            return@flow
        }

        val originals = regions.flat()
        if (originals.isEmpty()) {
            val response = TranslateResponse()
            cache.put(itemId, key, op, json.encodeToString(TranslateResponse.serializer(), response))
            emit(LlmOutcome.Ok(response.toFrame()))
            return@flow
        }

        val counts = intArrayOf(regions.title.size, regions.summary.size, regions.brief.size, regions.article.size)
        val userPrompt = originals.joinToString("\n\n") { it }
        var failed = false
        var fullText: String? = null
        llm.streamText(
            tier = LlmTier.TIER2_DEEP,
            systemPrompt = TranslateResponse.streamSystemPrompt(translateLanguageName(targetLanguage), originals.size),
            userPrompt = userPrompt,
        ).collect { outcome ->
            when (outcome) {
                is LlmOutcome.Err -> { failed = true; emit(outcome) }
                is LlmOutcome.Ok -> {
                    fullText = outcome.value
                    emit(LlmOutcome.Ok(parseTranslateFrame(outcome.value, counts)))
                }
            }
        }
        if (!failed && fullText != null) {
            val response = parseTranslateResponse(fullText, counts)
            cache.put(itemId, key, op, json.encodeToString(TranslateResponse.serializer(), response))
            emit(LlmOutcome.Ok(response.toFrame()))
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

    /**
     * Splits the streaming summary text into a [SummaryStreamFrame]: lines that a newline
     * has already terminated are completed [SummaryStreamFrame.bullets]; the trailing line
     * (no newline yet) is the in-progress [SummaryStreamFrame.partial]. Blank/partial lines
     * between bullets are ignored.
     */
    private fun parseSummaryFrame(text: String): SummaryStreamFrame {
        val endsNewline = text.endsWith('\n')
        val lines = text.split('\n').let { if (endsNewline) it.dropLast(1) else it }
        val completed = lines
            .dropLast(if (endsNewline || lines.isEmpty()) 0 else 1)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::cleanSummaryBullet)
        val partial = if (endsNewline) "" else lines.lastOrNull()?.trim().orEmpty()
        return SummaryStreamFrame(completed, partial)
    }

    /** Final parse of the complete summary text into the cached bullet list. */
    private fun parseSummaryBullets(text: String): List<String> =
        text.split('\n').map(String::trim).filter(String::isNotEmpty).map(::cleanSummaryBullet)

    /**
     * Splits the streamed translate text into per-paragraph segments. Primary separator is
     * [TranslateResponse.STREAM_DELIMITER] (`|||`), but some models ignore that obscure
     * sentinel on longer articles and instead separate paragraphs with blank lines or single
     * newlines — without a fallback the whole response collapses into ONE segment and lands
     * entirely in the title slot ("all paragraphs just below the title").
     *
     * Recovery is gated on [expected]: we only accept a fallback split when it yields MORE
     * non-empty segments than the primary (still short of, or equal to, [expected]). A clean
     * `|||` stream is never re-split. Single-newline fallback is last resort — it can over-
     * split a translation that wraps, so it's only used when nothing else reaches expected.
     */
    private fun splitTranslateSegments(text: String, expected: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val primary = text.split(TranslateResponse.STREAM_DELIMITER)
            .map(String::trim)
            .map { it.trimEnd('|').trim() }
            .filter(String::isNotEmpty)
        if (expected <= 1 || primary.size >= expected) return primary

        // Blank-line separated paragraphs — the most common natural fallback.
        val byBlankLine = text.split(Regex("\\n[\\t ]*\\n"))
            .map(String::trim)
            .map { it.trimEnd('|').trim() }
            .filter(String::isNotEmpty)
        if (byBlankLine.size in (primary.size + 1)..expected) return byBlankLine

        // Single-newline separated — riskier (may split a wrapped translation) but better
        // than dumping every paragraph under the title.
        val byNewline = text.split('\n')
            .map(String::trim)
            .map { it.trimEnd('|').trim() }
            .filter(String::isNotEmpty)
        if (byNewline.size in (primary.size + 1)..expected) return byNewline

        return primary
    }

    /**
     * Streaming translate segments via [splitTranslateSegments]; the flat list is then
     * re-partitioned into regions by [partitionTargets]. Mid-stream the trailing paragraph
     * being typed is whatever the last segment is — empties are already filtered out, so we
     * never reserve an empty translate slot.
     */
    private fun parseTranslateFrame(text: String, counts: IntArray): TranslateStreamFrame {
        val segments = splitTranslateSegments(text, counts.sum())
        return partitionTargets(segments, counts)
    }

    /** Final parse of the complete translate text into the cached regioned response. */
    private fun parseTranslateResponse(text: String, counts: IntArray): TranslateResponse {
        val targets = splitTranslateSegments(text, counts.sum())
        return partitionTargets(targets, counts).let { TranslateResponse(it.title, it.summary, it.brief, it.article) }
    }

    /**
     * Re-partitions a flat translated-paragraph list (in document order) back into reader
     * regions using the original region sizes [counts] = [title, summary, brief, article].
     * Short streams (still arriving) simply yield shorter/empty later regions.
     */
    private fun partitionTargets(targets: List<String>, counts: IntArray): TranslateStreamFrame {
        var idx = 0
        fun take(n: Int): List<String> {
            val s = targets.drop(idx).take(n).map(String::trim); idx += n; return s
        }
        val titleList = take(counts[0])
        val title = titleList.firstOrNull().orEmpty()
        val summary = take(counts[1])
        val brief = take(counts[2])
        val article = take(counts[3])
        return TranslateStreamFrame(title, summary, brief, article)
    }

    private fun TranslateResponse.toFrame(): TranslateStreamFrame =
        TranslateStreamFrame(title, summary, brief, article)

    /**
     * Strips list decoration the model may emit despite instructions (bullets, dashes,
     * `1.`/`1)` numbering) so cached + rendered bullets are uniform.
     */
    private fun cleanSummaryBullet(line: String): String {
        var s = line.trim()
        val markers = listOf("•", "●", "▪", "‣", "→", "-", "*")
        while (s.isNotEmpty()) {
            val marker = markers.firstOrNull { s.startsWith(it) }
            if (marker != null) { s = s.removePrefix(marker).trim(); continue }
            val number = Regex("^\\d+[.)]\\s*").find(s)
            if (number != null) { s = s.substring(number.range.last + 1); continue }
            break
        }
        return s
    }

    companion object {
        internal const val OP_CLASSIFY = "classification"
        internal const val OP_SUMMARY = "summary"
        internal const val OP_TRANSLATE = "translate:v2"

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
