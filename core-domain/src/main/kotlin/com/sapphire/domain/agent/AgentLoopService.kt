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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
        var fetchCount = 0
        var idleRounds = 0  // consecutive rounds with no tool call

        repeat(budget.maxRounds) { round ->
            val turn = llm.completeWithTools(
                tier = LlmTier.TIER1_FAST,
                systemPrompt = systemPrompt,
                conversation = conversation,
                tools = tools,
            )
            val result = when (turn) {
                is LlmOutcome.Err -> return turn
                is LlmOutcome.Ok -> turn.value
            }
            conversation += ToolMessage.Assistant(result.content, result.toolCalls)

            // No tool calls this round — the model emitted plain text only.
            if (result.toolCalls.isEmpty()) {
                idleRounds++
                if (idleRounds >= MAX_IDLE_ROUNDS) {
                    // Treat accumulated content as a best-effort answer and stop.
                    return bestEffortResult(conversation)
                }
                // Nudge the model to call a tool or finalize.
                conversation += ToolMessage.User(
                    "Call a tool to gather information, or call finalize to produce your result. Do not respond with text only."
                )
                return@repeat
            }
            idleRounds = 0

            // Dispatch each tool call, append results to the conversation.
            for (call in result.toolCalls) {
                val toolResult = when (call.name) {
                    "web_search" -> doSearch(call)
                    "fetch_page" -> {
                        if (fetchCount >= budget.maxFetches) {
                            ToolResultPayload.error("fetch budget exhausted ($MAX_FETCHES max); work with what you have.")
                        } else {
                            fetchCount++
                            doFetch(call, budget, pageCache)
                        }
                    }
                    "fetch_page_section" -> doFetchSection(call, budget, pageCache)
                    "finalize" -> return parseFinalize(call)
                    else -> ToolResultPayload.error("unknown tool: ${call.name}")
                }
                conversation += ToolMessage.ToolResult(call.id, toolResult.content)
            }

            // Token-ceiling guard: if the conversation is growing too large, force finalize.
            if (estimateTokens(conversation) > budget.inputTokenCeiling && round < budget.maxRounds - 1) {
                conversation += ToolMessage.User(
                    "You are near the context limit. Call finalize NOW with whatever you've gathered."
                )
            }
        }

        // Rounds exhausted without finalize — force one more turn that must finalize.
        return forceFinalize(systemPrompt, conversation, tools)
    }

    // ---- Tool implementations ----

    private suspend fun doSearch(call: ToolCall): ToolResultPayload {
        val query = parseStringArg(call.arguments, "query")
            ?: return ToolResultPayload.error("missing 'query' argument")

        val hits = runCatching { webSearch.search(query) }.getOrDefault(emptyList())
        if (hits.isEmpty()) return ToolResultPayload.error("no search results for: $query")

        val json = buildJsonString {
            putJsonObject("results") {
                for (hit in hits.take(10)) {
                    putJsonObject(hit.url) {
                        put("title", hit.title)
                        put("snippet", hit.content.take(500))
                    }
                }
            }
        }
        return ToolResultPayload.ok(json)
    }

    private suspend fun doFetch(
        call: ToolCall,
        budget: LoopBudget,
        pageCache: MutableMap<String, String>,
    ): ToolResultPayload {
        val url = parseStringArg(call.arguments, "url")
            ?: return ToolResultPayload.error("missing 'url' argument")

        // Cheap tier: readability extractor (plain HTTP + Readability).
        when (val outcome = runCatching { extractor.extract(url) }.getOrNull()) {
            is ExtractionOutcome.Ok -> {
                val text = outcome.html.toPlainText()
                if (text.isNotBlank() && text.length > 100) {
                    pageCache[url] = text
                    return pageResult(url, outcome.title, text, budget)
                }
            }
            null, is ExtractionOutcome.Err -> { /* fall through to browser */ }
        }

        // Expensive tier: browser render (handles dynamic/bot-blocked pages).
        return when (val rendered = runCatching { browser.render(RenderRequest(url)) }.getOrNull()) {
            null -> ToolResultPayload.error("fetch failed (exception)")
            else -> if (rendered.ok && !rendered.text.isNullOrBlank()) {
                pageCache[url] = rendered.text!!
                pageResultWithLinks(url, rendered.title, rendered.text!!, rendered.links, budget)
            } else {
                ToolResultPayload.error(rendered.error ?: "page could not be read (dynamic/blocked)")
            }
        }
    }

    /**
     * Read a section of a page by character [offset]. Uses the per-run cache when the URL
     * was already fetched by [doFetch] — no network call, no budget consumed. If not cached,
     * fetches it first (consuming a fetch slot via the caller's budget check is the caller's
     * responsibility; this method does NOT increment fetchCount since it's a read tool).
     * Returns up to [budget.maxCharsPerPage] chars starting at [offset], with a truncation
     * marker if more remains.
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
    private fun pageResult(url: String, title: String?, fullText: String, budget: LoopBudget): ToolResultPayload {
        val visible = fullText.take(budget.maxCharsPerPage)
        val truncated = fullText.length > budget.maxCharsPerPage
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
    ): ToolResultPayload {
        val base = pageResult(url, title, fullText, budget)
        if (links.isEmpty()) return base
        // Re-parse and add links — simpler: build fresh.
        val visible = fullText.take(budget.maxCharsPerPage)
        val truncated = fullText.length > budget.maxCharsPerPage
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

    private fun parseFinalize(call: ToolCall): LlmOutcome<AgentSynthesisResult> {
        return try {
            val result = json.decodeFromString(AgentSynthesisResult.serializer(), call.arguments)
            LlmOutcome.Ok(result)
        } catch (e: Exception) {
            // Malformed finalize — treat as empty (valid: agent found nothing worth filing).
            LlmOutcome.Ok(AgentSynthesisResult(emptyList()))
        }
    }

    private suspend fun forceFinalize(
        systemPrompt: String,
        conversation: List<ToolMessage>,
        tools: List<ToolDefinition>,
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
        if (finalizeCall != null) return parseFinalize(finalizeCall)
        // Last resort: try to parse content as the result.
        return bestEffortResult(forced)
    }

    private fun bestEffortResult(conversation: List<ToolMessage>): LlmOutcome<AgentSynthesisResult> {
        val text = conversation.mapNotNull { (it as? ToolMessage.Assistant)?.content }.lastOrNull()
        return if (!text.isNullOrBlank()) {
            LlmOutcome.Ok(AgentSynthesisResult(listOf(
                AgentSynthesisItem(
                    title = "Agent result",
                    summary = text.take(300),
                    body = text,
                )
            )))
        } else {
            LlmOutcome.Ok(AgentSynthesisResult(emptyList()))
        }
    }

    // ---- Prompt builders ----

    private fun buildSystemPrompt(maxItems: Int): String {
        return """You are an autonomous research agent inside a news reader app. Your job is to gather information and produce a digest.

You have four tools:
1. web_search(query) — search the live web for current information.
2. fetch_page(url) — read the full text of a web page (up to 200000 chars). For long pages, the result includes a "truncated" flag and a "hint" with the next offset.
3. fetch_page_section(url, offset) — read a continuation of a long page. Use the offset from the previous result's "next_offset" or "hint". Reads from cache — no extra network cost.
4. finalize(items) — produce your final result. ALWAYS end by calling finalize.

Rules:
- Ground every claim in pages you actually fetched or search results you found. Never fabricate sources.
- Read enough source material to write a thorough, substantive result. For long pages (transcripts, articles), use fetch_page_section to read the full content before finalizing.
- Call finalize when you have gathered enough and can produce a rich result. Do not finalize prematurely with thin content.
- If a page can't be read, try another source rather than giving up.
- Each item MUST have: a title (under 80 chars, plain text — no HTML), a one-line summary (plain text), a body, and a source URL.
- The body MUST be valid HTML using standard tags: <h2> for section headings, <p> for paragraphs, <ul><li> or <ol><li> for lists, <blockquote> for quotes, <strong>/<em> for emphasis. Do NOT use <html>, <head>, or <body> wrapper tags — just the content tags directly. Write substantial, well-developed content: aim for at least 300-500 words for single-item digests, using multiple paragraphs and sections.
- Default to prose paragraphs. Use bullet lists only when the task or format field explicitly requests them.
- Produce at most $maxItems item(s).

The finalize tool takes: {"items": [{"title": "plain text title", "summary": "one-line plain text summary", "body": "<h2>Heading</h2><p>Full paragraph...</p><p>Another paragraph...</p>", "url": "primary source url", "sources": [{"title": "...", "url": "..."}]}]}
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
            description = "Search the live web for current information. Returns titles, URLs, and snippets.",
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
            description = "Produce your final result and end the task. Always call this when done.",
            jsonSchema = FINALIZE_SCHEMA,
        ),
    )

    companion object {
        private const val MAX_IDLE_ROUNDS = 2
        private const val MAX_FETCHES = 5
    }
}

/** Hard caps that guarantee loop termination. Tuned for a 128K-token context window. */
data class LoopBudget(
    val maxRounds: Int = 8,
    val maxFetches: Int = MAX_FETCHES,
    val maxCharsPerPage: Int = 200_000,
    val inputTokenCeiling: Int = 100_000,
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
