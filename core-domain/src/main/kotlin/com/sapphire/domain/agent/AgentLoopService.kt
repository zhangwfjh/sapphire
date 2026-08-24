package com.sapphire.domain.agent

import com.sapphire.domain.browser.BrowserClient
import com.sapphire.domain.browser.RenderRequest
import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.llm.ToolCall
import com.sapphire.domain.llm.ToolDefinition
import com.sapphire.domain.llm.ToolMessage
import com.sapphire.domain.llm.ToolTurn
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.reader.ArticleExtractor
import com.sapphire.domain.reader.ExtractionOutcome
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The bounded tool-calling agent loop (PRD §3.7 autonomous / AGENTIC mode). Pure domain —
 * takes injectable [LlmClient] + [WebSearchClient] + [ArticleExtractor] + [BrowserClient],
 * returns [LlmOutcome]<[AgentSynthesisResult]>. Called by [AgentRunService], which owns
 * filing and history recording around it.
 *
 * Pipeline: a multi-round LLM tool-calling loop with a fixed tool palette
 * (`web_search`, `fetch_page`, `fetch_page_section`, `finalize`). The LLM decides which
 * tools to call and in what order; [LoopBudget] enforces hard caps so every path
 * terminates. Tool errors are fed back as tool *results* (never crash the loop); only
 * LLM-call errors propagate.
 *
 * Acceptance discipline (validated against live-run evidence):
 * - A malformed `finalize` payload gets ONE repair round (the parse error is returned as a
 *   tool result); a second failure falls back to [bestEffortResult] — never a silent empty.
 * - Citation scrubbing: URLs the run never saw (not from any search result or fetch) are
 *   removed from `url`/`sources` — items survive with a salvageable citation or none, so
 *   fabrication never masquerades as provenance. Skipped entirely when the run gathered no
 *   evidence (knowledge-only runs keep their citations).
 * - Within-run URL dedup and the job's maxItems cap apply at acceptance.
 */
class AgentLoopService(
    private val llm: LlmClient,
    private val webSearch: WebSearchClient,
    private val extractor: ArticleExtractor,
    private val browser: BrowserClient,
) {
    suspend fun run(
        job: AgentJob,
        previouslyFiledUrls: List<String> = emptyList(),
    ): LlmOutcome<AgentSynthesisResult> {
        val budget = LoopBudget()
        val systemPrompt = buildSystemPrompt(job.maxItems)
        val tools = TOOL_DEFINITIONS
        val conversation = mutableListOf<ToolMessage>(
            ToolMessage.User(buildUserPrompt(job, previouslyFiledUrls)),
        )
        // Per-run cache: url → full untruncated text. fetch_page stores here; fetch_page_section
        // reads sections without re-fetching (pagination without cost).
        val pageCache = mutableMapOf<String, String>()
        // URLs the run has actually seen: search-result hits + successfully fetched pages.
        val seenUrls = mutableSetOf<String>()
        // Content images harvested from fetched pages (pre-plain-text-strip), with the page
        // they came from — the provenance gate for cover selection and figure injection.
        val pageImages = mutableListOf<PageImage>()
        var searchCount = 0
        var fetchCount = 0
        var sectionCount = 0
        var idleRounds = 0       // consecutive turns with no tool call
        var toolRounds = 0       // turns that produced tool calls (idle nudges are free)
        var iterations = 0       // absolute guard — the loop can never spin unbounded
        var finalizeRepairs = 0  // malformed-finalize repair rounds used (max 1)
        var fetchNudgeUsed = false

        while (toolRounds < budget.maxRounds) {
            if (++iterations > budget.maxRounds * 3 + 10) break
            val turn = llm.completeWithTools(
                tier = LlmTier.TIER1_FAST,
                systemPrompt = systemPrompt,
                conversation = compacted(conversation, budget),
                tools = tools,
            )
            val result = when (turn) {
                is LlmOutcome.Err -> return turn
                is LlmOutcome.Ok -> turn.value
            }
            conversation += ToolMessage.Assistant(result.content, result.toolCalls)

            // No tool calls this round — the model emitted plain text only. Nudges are free:
            // they never consume a tool round.
            if (result.toolCalls.isEmpty()) {
                idleRounds++
                if (idleRounds >= MAX_IDLE_ROUNDS) {
                    // Treat accumulated content as a best-effort answer and stop.
                    return bestEffortResult(conversation, seenUrls)
                }
                // Nudge the model to call a tool or finalize.
                conversation += ToolMessage.User(
                    "Call a tool to gather information, or call finalize to produce your result. Do not respond with text only."
                )
                continue
            }
            idleRounds = 0
            toolRounds++
            var searchesThisRound = 0

            // Dispatch each tool call, append results to the conversation.
            for (call in result.toolCalls) {
                val toolResult = when (call.name) {
                    "web_search" -> {
                        if (searchCount >= budget.maxSearches) {
                            ToolResultPayload.error("search budget exhausted (${budget.maxSearches} max per run); work with what you have.")
                        } else if (searchesThisRound >= budget.maxSearchesPerRound) {
                            searchesThisRound++
                            ToolResultPayload.error("search budget for this round exhausted (${budget.maxSearchesPerRound} per round); fetch and read a result instead.")
                        } else {
                            searchCount++
                            searchesThisRound++
                            doSearch(call, seenUrls)
                        }
                    }
                    "fetch_page" -> {
                        if (fetchCount >= budget.maxFetches) {
                            ToolResultPayload.error("fetch budget exhausted ($MAX_FETCHES max); work with what you have.")
                        } else {
                            fetchCount++
                            doFetch(call, budget, pageCache, seenUrls, pageImages, pageCapFor(conversation, budget))
                        }
                    }
                    "fetch_page_section" -> {
                        if (sectionCount >= budget.maxSections) {
                            ToolResultPayload.error("section budget exhausted (${budget.maxSections} max); you have read enough of this page.")
                        } else {
                            sectionCount++
                            doFetchSection(call, budget, pageCache)
                        }
                    }
                    "finalize" -> when (val parsed = parseFinalize(call)) {
                        is FinalizeParse.Ok -> {
                            val items = parsed.result.items
                            if (!fetchNudgeUsed && fetchCount == 0 && searchCount > 0 &&
                                seenUrls.isNotEmpty() && items.isNotEmpty()
                            ) {
                                // Search-only finalize with usable hits on the table: demand at
                                // least one real page read before accepting. Once per run.
                                fetchNudgeUsed = true
                                ToolMessage.ToolResult(
                                    call.id,
                                    """{"error":"you have not fetched any page yet — snippets are not enough to ground a digest. Call fetch_page on the most promising result, then call finalize."}""",
                                )
                            } else {
                                return LlmOutcome.Ok(accept(items, seenUrls, job.maxItems, pageImages))
                            }
                        }
                        is FinalizeParse.Malformed -> {
                            if (finalizeRepairs < MAX_FINALIZE_REPAIRS) {
                                finalizeRepairs++
                                ToolMessage.ToolResult(
                                    call.id,
                                    """{"error":"finalize payload malformed (${parsed.reason}). 'items' MUST be a JSON ARRAY of objects — not a string containing JSON. Ensure every string is properly escaped: use single quotes inside HTML attributes (class='x'), never nested double quotes. Re-call finalize with a clean array."}""",
                                )
                            } else {
                                return bestEffortResult(conversation, seenUrls, call.arguments)
                            }
                        }
                    }
                    else -> ToolResultPayload.error("unknown tool: ${call.name}")
                }
                conversation += when (toolResult) {
                    is ToolResultPayload -> ToolMessage.ToolResult(call.id, toolResult.content)
                    is ToolMessage.ToolResult -> toolResult
                    else -> throw IllegalStateException("unreachable")
                }
            }
        }

        // Tool rounds exhausted without finalize — force one more turn that must finalize.
        return forceFinalize(systemPrompt, conversation, tools, seenUrls, pageImages, job.maxItems)
    }

    // ---- Acceptance: citation scrubbing + dedup + item cap + images ----

    /**
     * Applies acceptance discipline. When the run gathered evidence, HTTP(S) URLs that never
     * appeared in a search result or fetch are stripped: the primary url is salvaged to the
     * first seen source (or nulled), unseen sources are dropped. Items are never dropped on
     * citation grounds — an item with no surviving citation files under the synthetic
     * agent:// URL, keeping provenance honest without destroying content.
     *
     * Images: content images harvested from the item's cited pages (matched by normalized
     * page URL) become the item's [AgentSynthesisItem.coverUrl] (first meaningful one,
     * preferring the primary url's page) and are appended into the body as `<figure>`
     * blocks — all meaningful ones, capped. Only harvested images are used, so a cover or
     * figure can never be fabricated.
     */
    private fun accept(
        items: List<AgentSynthesisItem>,
        seenUrls: Set<String>,
        maxItems: Int,
        pageImages: List<PageImage>,
    ): AgentSynthesisResult {
        val deduped = items
            .map { item ->
                if (seenUrls.isEmpty()) item
                else {
                    val urlSeen = item.url == null || !item.url.startsWith("http") ||
                        seenUrls.contains(normalize(item.url!!))
                    val seenSources = item.sources.filter {
                        !it.url.startsWith("http") || seenUrls.contains(normalize(it.url))
                    }
                    val salvagedUrl = when {
                        urlSeen -> item.url
                        else -> seenSources.firstOrNull()?.url
                    }
                    item.copy(url = salvagedUrl, sources = seenSources)
                }
            }
            .distinctBy { it.url?.let(::normalize) ?: "title:" + it.title.lowercase().trim() }
            .take(maxItems)
            .map { item -> withImages(item, pageImages) }
        return AgentSynthesisResult(deduped)
    }

    /** Attaches provenance-gated images: one cover + inline `<figure>` blocks in the body. */
    private fun withImages(item: AgentSynthesisItem, pageImages: List<PageImage>): AgentSynthesisItem {
        if (pageImages.isEmpty()) return item
        val citedPages = buildList {
            item.url?.let { add(normalize(it)) }
            item.sources.forEach { add(normalize(it.url)) }
        }.toSet()
        if (citedPages.isEmpty()) return item
        val candidates = pageImages.filter { it.pageUrl in citedPages }
        if (candidates.isEmpty()) return item
        // Cover prefers an image from the primary source's own page.
        val primaryPage = item.url?.let(::normalize)
        val cover = candidates.firstOrNull { primaryPage != null && it.pageUrl == primaryPage }
            ?: candidates.first()
        val inline = (listOf(cover) + candidates.filterNot { it == cover })
            .distinctBy { it.url }
            .take(MAX_IMAGES_PER_ITEM)
        val figures = inline.joinToString("\n") { img ->
            buildString {
                append("""<figure><img src="${img.url}" alt="${img.alt.orEmpty().replace("\"", "'")}">""")
                if (!img.alt.isNullOrBlank()) append("<figcaption>${img.alt}</figcaption>")
                append("</figure>")
            }
        }
        val body = item.body.orEmpty().let { if (it.isBlank()) figures else "$it\n$figures" }
        return item.copy(coverUrl = cover.url, body = body)
    }

    /** Lower-cases scheme/host and drops trailing slash — the same URL in two spellings matches. */
    private fun normalize(url: String): String {
        val marker = "://"
        val idx = url.indexOf(marker)
        if (idx < 0) return url.trim().lowercase().trimEnd('/')
        val scheme = url.substring(0, idx).lowercase()
        val rest = url.substring(idx + marker.length)
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val host = (if (hostEnd < 0) rest else rest.substring(0, hostEnd)).lowercase().removePrefix("www.")
        val tail = if (hostEnd < 0) "" else rest.substring(hostEnd)
        return "$scheme$marker$host$tail".trimEnd('/')
    }

    // ---- Tool implementations ----


    private suspend fun doSearch(call: ToolCall, seenUrls: MutableSet<String>): ToolResultPayload {
        val query = parseStringArg(call.arguments, "query")
            ?: return ToolResultPayload.error("missing 'query' argument")

        val hits = runCatching { webSearch.search(query) }.getOrDefault(emptyList())
        if (hits.isEmpty()) return ToolResultPayload.error("no search results for: $query")

        hits.forEach { seenUrls.add(normalize(it.url)) }
        // Array shape (not url-keyed map): preserves rank order and survives duplicate URLs.
        val json = buildJsonString {
            put("results", kotlinx.serialization.json.buildJsonArray {
                for (hit in hits.take(10)) {
                    add(buildJsonObject {
                        put("title", hit.title)
                        put("url", hit.url)
                        put("snippet", hit.content.take(300))
                    })
                }
            })
        }
        return ToolResultPayload.ok(json)
    }


    private suspend fun doFetch(
        call: ToolCall,
        budget: LoopBudget,
        pageCache: MutableMap<String, String>,
        seenUrls: MutableSet<String>,
        pageImages: MutableList<PageImage>,
        pageCap: Int,
    ): ToolResultPayload {
        val url = parseStringArg(call.arguments, "url")
            ?: return ToolResultPayload.error("missing 'url' argument")

        // Cheap tier: readability extractor (plain HTTP + Readability).
        when (val outcome = runCatching { extractor.extract(url) }.getOrNull()) {
            is ExtractionOutcome.Ok -> {
                harvestImages(outcome.html, url, pageImages)
                val text = outcome.html.toPlainText()
                if (text.isNotBlank() && text.length > 100) {
                    pageCache[url] = text
                    seenUrls.add(normalize(url))
                    return pageResult(url, outcome.title, text, budget, pageCap)
                }
            }
            null, is ExtractionOutcome.Err -> { /* fall through to browser */ }
        }

        // Expensive tier: browser render (handles dynamic/bot-blocked pages).
        return when (val rendered = runCatching { browser.render(RenderRequest(url)) }.getOrNull()) {
            null -> ToolResultPayload.error("fetch failed (exception)")
            else -> if (rendered.ok && !rendered.text.isNullOrBlank()) {
                pageCache[url] = rendered.text!!
                seenUrls.add(normalize(url))
                pageResultWithLinks(url, rendered.title, rendered.text!!, rendered.links, budget, pageCap)
            } else {
                ToolResultPayload.error(rendered.error ?: "page could not be read (dynamic/blocked)")
            }
        }
    }

    /**
     * Extracts content images from the readability-cleaned HTML (which preserves content
     * `<img>` but drops most chrome) before the caller strips tags. Junk filter removes
     * trackers/icons/logos by URL shape; relative srcs resolve against the page URL;
     * attribute entities are decoded. `src`/`data-src` only, matching the reader parser.
     */
    private fun harvestImages(html: String, pageUrl: String, into: MutableList<PageImage>) {
        val known = into.mapTo(mutableSetOf()) { it.url }
        Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { tag ->
            val src = (attrOf(tag.value, "src") ?: attrOf(tag.value, "data-src"))?.trim() ?: return@forEach
            val resolved = resolveUrl(src, pageUrl) ?: return@forEach
            if (!isMeaningfulImage(resolved)) return@forEach
            if (known.add(resolved) && into.size < MAX_PAGE_IMAGES_PER_RUN) {
                val alt = attrOf(tag.value, "alt")?.let(::htmlUnescape)?.trim()?.takeIf { it.isNotBlank() }
                into.add(PageImage(url = resolved, pageUrl = normalize(pageUrl), alt = alt))
            }
        }
    }

    private fun attrOf(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.let(::htmlUnescape)

    private fun resolveUrl(src: String, pageUrl: String): String? = try {
        java.net.URI(pageUrl).resolve(src).toString().takeIf { it.startsWith("http") }
    } catch (_: Exception) {
        null
    }

    private fun isMeaningfulImage(url: String): Boolean {
        val u = url.lowercase()
        if (u.startsWith("data:")) return false
        if (JUNK_IMAGE_PATTERN.containsMatchIn(u)) return false
        if (u.substringBefore('?').endsWith(".svg")) return false // overwhelmingly logos/icons
        return true
    }

    /** Attribute values arrive HTML-escaped; `&amp;` in an image URL breaks the HTTP call. */
    private fun htmlUnescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")

    /**
     * Read a section of a page by character [offset] from the per-run cache (no network).
     * Consumes a section slot from [LoopBudget.maxSections] — pagination is cheap but not
     * free, and unbounded section reads used to let the model spend every round on one page.
     */
    private suspend fun doFetchSection(
        call: ToolCall,
        budget: LoopBudget,
        pageCache: MutableMap<String, String>,
    ): ToolResultPayload {
        val url = parseStringArg(call.arguments, "url")
            ?: return ToolResultPayload.error("missing 'url' argument")
        val offset = parseIntArg(call.arguments, "offset") ?: 0

        val full = pageCache[url]
            ?: return ToolResultPayload.error("page not in cache — call fetch_page first for: $url")

        if (offset >= full.length) {
            return ToolResultPayload.error("offset $offset beyond page length ${full.length}")
        }

        val section = full.drop(offset).take(budget.maxCharsPerPage)
        val remaining = full.length - offset - section.length
        return ToolResultPayload.ok(buildJsonString {
            put("url", url)
            put("offset", offset)
            put("length", section.length)
            put("total_length", full.length)
            if (remaining > 0) {
                put("truncated", true)
                put("next_offset", offset + section.length)
            }
            put("text", section)
        })
    }

    /** Builds a page result with truncation marker when content exceeds the per-page cap. */
    private fun pageResult(url: String, title: String?, fullText: String, budget: LoopBudget, pageCap: Int = budget.maxCharsPerPage): ToolResultPayload {
        val cap = minOf(budget.maxCharsPerPage, pageCap)
        val visible = fullText.take(cap)
        val truncated = fullText.length > cap
        return ToolResultPayload.ok(buildJsonString {
            put("title", title ?: url)
            put("url", url)
            put("text", visible)
            if (truncated) {
                put("truncated", true)
                put("total_length", fullText.length)
                put("hint", "Content truncated. Use fetch_page_section(url, offset) to read more starting at offset ${visible.length}.")
            }
        })
    }

    private fun pageResultWithLinks(
        url: String, title: String?, fullText: String,
        links: List<com.sapphire.domain.browser.LinkRef>, budget: LoopBudget,
        pageCap: Int = budget.maxCharsPerPage,
    ): ToolResultPayload {
        val cap = minOf(budget.maxCharsPerPage, pageCap)
        val visible = fullText.take(cap)
        val truncated = fullText.length > cap
        return ToolResultPayload.ok(buildJsonString {
            put("title", title ?: url)
            put("url", url)
            put("text", visible)
            if (truncated) {
                put("truncated", true)
                put("total_length", fullText.length)
                put("hint", "Content truncated. Use fetch_page_section(url, offset) to read more starting at offset ${visible.length}.")
            }
            putJsonObject("links") {
                links.take(25).forEach { link -> put(link.text.take(60), link.url) }
            }
        })
    }

    // ---- Finalize parsing (with one repair round on malformed payloads) ----

    private sealed interface FinalizeParse {
        data class Ok(val result: AgentSynthesisResult) : FinalizeParse
        data class Malformed(val reason: String) : FinalizeParse
    }

    private fun parseFinalize(call: ToolCall): FinalizeParse {
        // Direct decode.
        decodeItems(call.arguments)?.let { return FinalizeParse.Ok(it) }
        // Common double-encoding: {"items": "<json-array-as-string>"}.
        val itemsString = parseStringArg(call.arguments, "items")
        if (itemsString != null) {
            decodeItems(itemsString)?.let { return FinalizeParse.Ok(it) }
            // Or the whole arguments string is the array text itself.
            if (itemsString.trim().startsWith("[")) {
                decodeItems("""{"items":${itemsString.trim()}}""")?.let { return FinalizeParse.Ok(it) }
            }
        }
        return FinalizeParse.Malformed("could not decode items from the finalize arguments")
    }

    private fun decodeItems(json: String): AgentSynthesisResult? = try {
        json.decodeFromJsonString()
    } catch (_: Exception) {
        null
    }

    private fun String.decodeFromJsonString(): AgentSynthesisResult =
        json.decodeFromString(AgentSynthesisResult.serializer(), this)


    private suspend fun forceFinalize(
        systemPrompt: String,
        conversation: List<ToolMessage>,
        tools: List<ToolDefinition>,
        seenUrls: Set<String>,
        pageImages: List<PageImage>,
        maxItems: Int,
    ): LlmOutcome<AgentSynthesisResult> {
        val forced = conversation + ToolMessage.User(
            "BUDGET EXHAUSTED. You MUST call finalize now with whatever items you have gathered so far. Do not call any other tool."
        )
        val turn = llm.completeWithTools(LlmTier.TIER1_FAST, systemPrompt, forced, tools)
        val toolTurn = when (turn) {
            is LlmOutcome.Err -> return turn
            is LlmOutcome.Ok -> turn.value
        }
        // Look for a finalize call in the response.
        val finalizeCall = toolTurn.toolCalls.firstOrNull { it.name == "finalize" }
        if (finalizeCall != null) {
            return when (val parsed = parseFinalize(finalizeCall)) {
                is FinalizeParse.Ok -> LlmOutcome.Ok(accept(parsed.result.items, seenUrls, maxItems, pageImages))
                is FinalizeParse.Malformed -> bestEffortResult(forced, seenUrls, finalizeCall.arguments)
            }
        }
        // Last resort: try to parse content as the result.
        return bestEffortResult(forced, seenUrls)
    }

    private fun bestEffortResult(
        conversation: List<ToolMessage>,
        seenUrls: Set<String>,
        lastFinalizeArguments: String? = null,
    ): LlmOutcome<AgentSynthesisResult> {
        val text = conversation.mapNotNull { (it as? ToolMessage.Assistant)?.content }.lastOrNull()
        if (!text.isNullOrBlank()) {
            return LlmOutcome.Ok(AgentSynthesisResult(listOf(
                AgentSynthesisItem(
                    title = "Agent result",
                    summary = text.take(300),
                    body = text,
                    // Attach the last URL the run actually read, if any — a bare agent:// item
                    // otherwise pollutes the next run's exclusion list with a synthetic URL.
                    url = seenUrls.lastOrNull(),
                )
            )))
        }
        // Tool-call-only turns carry no assistant text; the final (unparseable) finalize
        // arguments still hold the model's work — salvage a readable item from them.
        salvageFromFinalize(lastFinalizeArguments, seenUrls)?.let { return LlmOutcome.Ok(it) }
        return LlmOutcome.Ok(AgentSynthesisResult(emptyList()))
    }

    /**
     * Best-effort extraction of one item from raw finalize arguments whose JSON was too
     * broken to decode (observed live: nested-escape corruption in HTML bodies). Pulls
     * title/summary/body by pattern, un-escapes common sequences, and strips any residual
     * JSON syntax — imperfect text beats losing the run's content entirely.
     */
    private fun salvageFromFinalize(arguments: String?, seenUrls: Set<String>): AgentSynthesisResult? {
        if (arguments == null) return null
        val title = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(arguments)?.groupValues?.get(1)?.unescapeJson()
        val body = Regex(""""body"\s*:\s*"((?:[^"\\]|\\.)*)"""", RegexOption.DOT_MATCHES_ALL)
            .find(arguments)?.groupValues?.get(1)?.unescapeJson()
        if (title.isNullOrBlank() && body.isNullOrBlank()) return null
        val url = Regex(""""url"\s*:\s*"([^"]+)"""").find(arguments)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") && (seenUrls.isEmpty() || seenUrls.contains(normalize(it))) }
            ?: seenUrls.lastOrNull()
        return AgentSynthesisResult(listOf(
            AgentSynthesisItem(
                title = title?.take(80)?.ifBlank { "Agent result" } ?: "Agent result",
                summary = body?.take(300),
                body = body ?: title,
                url = url,
            )
        ))
    }

    private fun String.unescapeJson(): String = replace("\\\\\"", "\"")
        .replace("\\\\n", "\n")
        .replace("\\\\t", " ")
        .replace("\\\\", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    // ---- Context budget ----

    /**
     * Per-fetch visible-text cap that keeps the conversation comfortably inside the token
     * ceiling: the remaining budget (85% of ceiling, minus current context), with a 20K
     * floor so early fetches are never starved. Capping at append time is the only way to
     * bound a run whose newest fetch alone would blow the window — compaction after the
     * fact can't shrink a message the model is about to read.
     */
    private fun pageCapFor(conversation: List<ToolMessage>, budget: LoopBudget): Int {
        val ceilingChars = budget.inputTokenCeiling * 4
        val used = conversation.sumOf { msg ->
            when (msg) {
                is ToolMessage.User -> msg.content.length
                is ToolMessage.Assistant -> (msg.content?.length ?: 0) + msg.toolCalls.sumOf { it.arguments.length }
                is ToolMessage.ToolResult -> msg.content.length
            }
        }
        return maxOf(MIN_PAGE_CAP, (ceilingChars * 85 / 100) - used)
    }


    /**
     * Keeps the conversation under [LoopBudget.inputTokenCeiling] by dropping the oldest
     * tool-result messages (the biggest and least recent context) while preserving the
     * original task prompt and the most recent turns. A synthetic note marks the elision.
     */
    private fun compacted(conversation: List<ToolMessage>, budget: LoopBudget): List<ToolMessage> {
        if (estimateTokens(conversation) <= budget.inputTokenCeiling) return conversation
        val head = conversation.first() // the task prompt — never dropped
        var rest = conversation.drop(1)
        var dropped = 0
        // Drop the oldest ToolResult except the very last message, until under the ceiling.
        // Fresh giant pages may be dropped too — a trimmed context beats a blown one.
        while (estimateTokens(listOf(head) + rest) > budget.inputTokenCeiling && rest.size > 1) {
            val idx = rest.dropLast(1).indexOfFirst { it is ToolMessage.ToolResult }
            if (idx < 0) break
            rest = rest.filterIndexed { i, _ -> i != idx }
            dropped++
        }
        if (dropped == 0) return conversation
        val note = ToolMessage.User("[context note: $dropped older tool results were elided to fit the context window]")
        return listOf(head) + rest + listOf(note)
    }

    // ---- Prompt builders ----

    private fun buildSystemPrompt(maxItems: Int): String {
        return """You are an autonomous research agent inside a news reader app. Your job is to gather information and produce a digest.

You have four tools:
1. web_search(query) — search the live web. Returns a JSON array of {title, url, snippet}, best matches first.
2. fetch_page(url) — read the full text of a web page (up to 200000 chars). For long pages, the result includes a "truncated" flag and a "hint" with the next offset.
3. fetch_page_section(url, offset) — read a continuation of a long page. Use the offset from the previous result's "next_offset" or "hint". Reads from cache.
4. finalize(items) — produce your final result. ALWAYS end by calling finalize.

Search strategy (important — you have a limited budget):
- Start with 1-2 broad searches. If promising hits come back, FETCH and read 2-4 of them before searching more. Snippets alone are not enough to ground a digest.
- If a search returns nothing usable, rephrase once or twice at most; if still nothing, STOP searching and finalize with whatever you have. Never repeat the same query in different words.
- At most 2 searches per round and 8 per run are executed; excess calls return an error.

Rules:
- Ground every claim in pages you actually fetched or search results you found. Never fabricate sources.
- NEVER invent names, dates, or URLs that did not appear in tool results. If you don't know, say so or omit it.
- Read enough source material to write a thorough, substantive result. For long pages (transcripts, articles), use fetch_page_section to read the full content before finalizing.
- Call finalize when you have gathered enough and can produce a rich result. Do not finalize prematurely with thin content.
- If a page can't be read, try another source rather than giving up.
- Each item MUST have: a title (under 80 chars, plain text — no HTML), a one-line summary (plain text), a body, and a source URL.
- Cite only URLs that appeared in web_search results or that you fetched.
- The body MUST be valid HTML using standard tags: <h2> for section headings, <p> for paragraphs, <ul><li> or <ol><li> for lists, <blockquote> for quotes, <strong>/<em> for emphasis. Do NOT use <html>, <head>, or <body> wrapper tags — just the content tags directly. Write substantial, well-developed content: aim for at least 300-500 words for single-item digests, using multiple paragraphs and sections.
- Default to prose paragraphs. Use bullet lists only when the task or format field explicitly requests them.
- Produce at most $maxItems item(s).

The finalize tool takes: {"items": [{"title": "plain text title", "summary": "one-line plain text summary", "body": "<h2>Heading</h2><p>Full paragraph...</p><p>Another paragraph...</p>", "url": "primary source url", "sources": [{"title": "...", "url": "..."}]}]}
"items" MUST be a JSON array of objects — never a string containing JSON.
If nothing notable was found, call finalize with an empty items list."""
    }

    private fun buildUserPrompt(job: AgentJob, previouslyFiledUrls: List<String>): String {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val seed = Random.nextInt(1, 1001)
        val exclusion = if (previouslyFiledUrls.isNotEmpty()) {
            val urls = previouslyFiledUrls.take(20).joinToString("\n") { "  - $it" }
            "\n\nIMPORTANT: You have already covered these URLs in previous runs — pick DIFFERENT content this time:\n$urls"
        } else ""
        return "Task: ${job.directive}\n\nToday's date: $today\nRandom seed: $seed (use this to vary your selection — e.g. if choosing among options, pick position (seed mod count))." +
            "$exclusion\n\nGather information using your tools, then call finalize with the result."
    }
    // ---- Helpers ----

    private fun parseStringArg(json: String, key: String): String? = try {
        json.parseToJsonObjectOrNull()?.get(key)?.jsonPrimitive?.content
    } catch (e: Exception) { null }

    private fun estimateTokens(conversation: List<ToolMessage>): Int {
        // Rough estimate: ~4 chars per token.
        val chars = conversation.sumOf { msg ->
            when (msg) {
                is ToolMessage.User -> msg.content.length
                is ToolMessage.Assistant -> (msg.content?.length ?: 0) + msg.toolCalls.sumOf { it.arguments.length }
                is ToolMessage.ToolResult -> msg.content.length
            }
        }
        return chars / 4
    }

    private fun parseIntArg(json: String, key: String): Int? = try {
        json.parseToJsonObjectOrNull()?.get(key)?.jsonPrimitive?.content?.toIntOrNull()
    } catch (e: Exception) { null }

    private fun String.toPlainText(): String =
        replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun buildJsonString(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        buildJsonObject(block).toString()

    private fun String.parseToJsonObjectOrNull(): JsonObject? = try {
        json.parseToJsonElement(this).jsonObject
    } catch (e: Exception) { null }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- Tool definitions (OpenAI function-calling schema) ----

    private val FINALIZE_SCHEMA = """{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"title":{"type":"string"},"summary":{"type":"string"},"body":{"type":"string"},"url":{"type":"string"},"sources":{"type":"array","items":{"type":"object","properties":{"title":{"type":"string"},"url":{"type":"string"}}}}}}}}}"""

    private val TOOL_DEFINITIONS = listOf(
        ToolDefinition(
            name = "web_search",
            description = "Search the live web for current information. Returns a JSON array of results: {title, url, snippet}, best matches first.",
            jsonSchema = """{"type":"object","properties":{"query":{"type":"string","description":"The search query"}},"required":["query"]}""",
        ),
        ToolDefinition(
            name = "fetch_page",
            description = "Read the full text content of a web page (up to 200000 chars). If truncated, the result includes a 'next_offset' to continue reading with fetch_page_section.",
            jsonSchema = """{"type":"object","properties":{"url":{"type":"string","description":"The URL to read"}},"required":["url"]}""",
        ),
        ToolDefinition(
            name = "fetch_page_section",
            description = "Read a continuation section of a previously fetched page by character offset. Use when fetch_page returned 'truncated: true'. Reads from cache — no network cost.",
            jsonSchema = """{"type":"object","properties":{"url":{"type":"string","description":"The URL (must have been fetched via fetch_page first)"},"offset":{"type":"integer","description":"Character offset to start reading from (use next_offset from the previous result)"}},"required":["url","offset"]}""",
        ),
        ToolDefinition(
            name = "finalize",
            description = "Produce your final result and end the task. Always call this when done. 'items' MUST be a JSON array of objects (not a string).",
            jsonSchema = FINALIZE_SCHEMA,
        ),
    )

    companion object {
        private const val MAX_IDLE_ROUNDS = 2
        private const val MAX_FINALIZE_REPAIRS = 1
        private const val MAX_FETCHES = 5
        private const val MIN_PAGE_CAP = 20_000

        /** Per-item inline image cap (cover + extras appended as figures). */
        private const val MAX_IMAGES_PER_ITEM = 4

        /** Per-run harvest cap — memory bound only, never enters LLM context. */
        private const val MAX_PAGE_IMAGES_PER_RUN = 24

        /** URL shapes that are chrome/telemetry, not content. */
        private val JUNK_IMAGE_PATTERN = Regex(
 "logo|icon|sprite|avatar|badge|favicon|pixel|spacer|blank\\.gif|tracking|analytics|doubleclick|gravatar|emoji|1x1",
            RegexOption.IGNORE_CASE,
        )
    }

}

/** A content image harvested from a fetched page, with its provenance page. */
private data class PageImage(
    val url: String,
    val pageUrl: String,
    val alt: String?,
)

data class LoopBudget(
    val maxRounds: Int = 8,
    val maxFetches: Int = MAX_FETCHES,
    val maxCharsPerPage: Int = 200_000,
    val inputTokenCeiling: Int = 100_000,
    /** Anti search-spam: total searches per run. */
    val maxSearches: Int = 8,
    /** Anti search-spam: searches allowed within a single assistant turn. */
    val maxSearchesPerRound: Int = 2,
    /** Cache pagination reads per run — bounded so the model can't spend every round on one page. */
    val maxSections: Int = 3,
) {
    companion object {
        private const val MAX_FETCHES = 5
    }
}

/** Internal wrapper for a tool execution result string. */
private data class ToolResultPayload(val content: String) {
    companion object {
        fun ok(json: String) = ToolResultPayload(json)
        fun error(message: String) = ToolResultPayload(buildJsonObject { put("error", message) }.toString())
    }
}
