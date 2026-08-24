package com.sapphire.domain.agent

import com.sapphire.domain.browser.BrowserClient
import com.sapphire.domain.browser.RenderRequest
import com.sapphire.domain.browser.RenderResult
import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.llm.ToolCall
import com.sapphire.domain.llm.ToolDefinition
import com.sapphire.domain.llm.ToolMessage
import com.sapphire.domain.llm.ToolTurn
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.reader.ArticleExtractor
import com.sapphire.domain.reader.ExtractionOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentLoopService — the bounded tool-calling loop. Exercises termination, budget
 * enforcement, tool dispatch, error mapping, and graceful degradation with hand-rolled
 * fakes (house pattern — no mocking frameworks). No live LLM/search calls.
 *
 * These tests defend the load-bearing invariants:
 * - The loop ALWAYS terminates (finalize, budget-cap, or idle-cap).
 * - Tool errors become results (never crash the loop).
 * - LLM-call errors propagate as Err.
 * - Missing browser config degrades to readability/search, never hard-fails.
 */
class AgentLoopServiceTest {

    @Test
    fun `finalize on round one returns immediately`() = runTest {
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCall(item("T", "S", "Body", "https://x")),
            )),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(1, (outcome as LlmOutcome.Ok).value.items.size)
        assertEquals("T", outcome.value.items[0].title)
        assertEquals(1, llm.calls)  // exactly one LLM round-trip
    }

    @Test
    fun `search then finalize gets a corrective fetch round then completes`() = runTest {
        val llm = FakeLlm(listOf(
            ToolTurn(content = "thinking", toolCalls = listOf(
                ToolCall("c1", "web_search", """{"query":"ted"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCall(item("Found", "sum", "body", "https://ted")),
            )),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(1, (outcome as LlmOutcome.Ok).value.items.size)
        // Search-only finalize is pushed back once (fetch demanded), then the repeated
        // turn (FakeLlm repeats its last script) is accepted: 3 LLM round-trips.
        assertEquals(3, llm.calls)
    }

    @Test
    fun `rounds exhausted forces finalize`() = runTest {
        // Model never calls finalize — always searches. Budget caps at 8 rounds, then forceFinalize.
        val searchTurn = ToolTurn(content = "more", toolCalls = listOf(
            ToolCall("c", "web_search", """{"query":"x"}"""),
        ))
        val llm = FakeLlm(List(8) { searchTurn } + listOf(
            // forceFinalize round: the model finally calls finalize.
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCall(item("Forced", "s", "b", "https://u")),
            )),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(1, (outcome as LlmOutcome.Ok).value.items.size)
        assertEquals("Forced", outcome.value.items[0].title)
        // 8 loop rounds + 1 forceFinalize round = 9 calls.
        assertEquals(9, llm.calls)
    }

    @Test
    fun `max fetches exhausted returns budget error to model`() = runTest {
        // 6 fetch calls; budget is 5. The 6th must return an error result, then model finalizes.
        val fetchTurn = ToolTurn(content = null, toolCalls = listOf(
            ToolCall("c", "fetch_page", """{"url":"https://p"}"""),
        ))
        val llm = FakeLlm(List(6) { fetchTurn } + listOf(
            ToolTurn(content = null, toolCalls = listOf(finalizeCall(item("D", "s", "b", "u")))),
        ))
        val recordingBrowser = RecordingBrowser(RenderResult(ok = true, title = "P", text = "content"))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), recordingBrowser)

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        // Only 5 fetches succeeded; the 6th was blocked by budget. Browser called 5 times.
        assertEquals(5, recordingBrowser.calls)
    }

    @Test
    fun `LLM error propagates as Err`() = runTest {
        val llm = FakeLlm(error = LlmError.Timeout)
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Err)
        assertEquals(LlmError.Timeout, (outcome as LlmOutcome.Err).error)
    }

    @Test
    fun `unknown tool returns error result and loop continues`() = runTest {
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c", "bogus_tool", """{}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCall(item("R", "s", "b", "u")),
            )),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)  // unknown tool didn't crash; loop continued
        assertEquals(2, llm.calls)
    }

    @Test
    fun `readability extractor hit skips browser`() = runTest {
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c", "fetch_page", """{"url":"https://blog"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(finalizeCall(item("B", "s", "b", "u")))),
        ))
        val recordingBrowser = RecordingBrowser(RenderResult(ok = true, text = "should not be used"))
        val loop = AgentLoopService(llm, searchOk(), extractorOk(), recordingBrowser)

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(0, recordingBrowser.calls)  // browser never invoked — readability sufficed
    }

    @Test
    fun `no browser configured degrades gracefully`() = runTest {
        // Readability fails (Blocked), browser not configured → fetch returns error → model finalizes.
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c", "fetch_page", """{"url":"https://blocked"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCall(item("FromSnippet", "s", "b", "u")),
            )),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorBlocked(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)  // degraded, did not crash
        assertEquals("FromSnippet", (outcome as LlmOutcome.Ok).value.items[0].title)
    }

    @Test
    fun `empty finalize is a valid empty result`() = runTest {
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCallEmpty(),
            )),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(0, (outcome as LlmOutcome.Ok).value.items.size)
    }

    @Test
    fun `loop always uses TIER1_FAST`() = runTest {
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(finalizeCall(item("T", "s", "b", "u")))),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        loop.run(job())

        assertEquals(LlmTier.TIER1_FAST, llm.lastTier)
    }

    @Test
    fun `fetch_page_section reads continuation from cache`() = runTest {
        // fetch_page caches full content; fetch_page_section reads beyond the cap without re-fetching.
        val longBody = "A".repeat(200)  // short enough to not truncate at 50K, but tests the section API
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c1", "fetch_page", """{"url":"https://long"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c2", "fetch_page_section", """{"url":"https://long","offset":100}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(finalizeCall(item("R", "s", "b", "u")))),
        ))
        val recordingBrowser = RecordingBrowser(RenderResult(ok = true, text = "B".repeat(200)))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), recordingBrowser)

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(3, llm.calls)
    }

    @Test
    fun `fetch_page_section without prior fetch returns error`() = runTest {
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c", "fetch_page_section", """{"url":"https://uncached","offset":0}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(finalizeCall(item("R", "s", "b", "u")))),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        // Section fetch fails gracefully; loop continues to finalize.
        assertTrue(outcome is LlmOutcome.Ok)
    }

    @Test
    fun `long page under 200K chars is returned in full without truncation`() = runTest {
        // A page that exceeds the old 50K cap but is well within a 128K context window.
        // The agent should see ALL of it in one fetch_page call — no truncation marker,
        // no need for fetch_page_section. This is the fix for the "truncation blindness" bug.
        val longBody = "A".repeat(80_000)  // 80K chars — 2x the old cap, fine for 128K context
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c1", "fetch_page", """{"url":"https://long-article"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCall(item("Result", "summary", "body content here", "https://long-article")),
            )),
        ))
        val recordingExtractor = RecordingExtractor(ExtractionOutcome.Ok(title = "Long", html = "<p>$longBody</p>", byline = null))
        val loop = AgentLoopService(llm, searchOk(), recordingExtractor, browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        // The agent should have received the full 80K chars — check the ToolResult fed back
        // to the LLM. We verify via the FakeLlm's conversation capture.
        val toolResult = llm.capturedToolResults.firstOrNull()
        assertNotNull("tool result should exist", toolResult)
        assertFalse("80K page should NOT have a truncation marker", toolResult!!.contains("\"truncated\""))
    }

    @Test
    fun `extremely long page over 200K chars truncates with marker`() = runTest {
        // A page so large it would blow the context budget even at 128K.
        // Truncation should kick in at the hard cap, with a pagination hint.
        val hugeBody = "Z".repeat(250_000)  // 250K chars — exceeds even the generous cap
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c1", "fetch_page", """{"url":"https://huge"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCall(item("R", "s", "b", "u")),
            )),
        ))
        val recordingExtractor = RecordingExtractor(ExtractionOutcome.Ok(title = "Huge", html = "<p>$hugeBody</p>", byline = null))
        val loop = AgentLoopService(llm, searchOk(), recordingExtractor, browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        val toolResult = llm.capturedToolResults.firstOrNull()
        assertNotNull("tool result should exist", toolResult)
        assertTrue("250K page SHOULD have a truncation marker", toolResult!!.contains("\"truncated\""))
    }

    // ---- acceptance discipline (validate against live-run evidence) ----

    @Test
    fun `string-encoded finalize items is repaired on second call`() = runTest {
        // The exact production failure: {"items": "<json-array-as-string>"}.
        val encoded = finalizeCall(item("T", "S", "Body", "u"))
            .arguments.replaceFirst("\"items\":[", "\"items\":\"[")
            .dropLast(1) + "]\"}"
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(ToolCall("f1", "finalize", encoded))),
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCall(item("T", "S", "Body", "u")),
            )),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(1, (outcome as LlmOutcome.Ok).value.items.size)
        assertEquals("T", outcome.value.items[0].title)
        assertEquals(2, llm.calls) // repair round happened
    }

    @Test
    fun `persistently malformed finalize falls back to best effort, not silent empty`() = runTest {
        val malformed = ToolCall("f1", "finalize", """{"items": 42}""")
        val llm = FakeLlm(listOf(
            ToolTurn(content = "gathering notes", toolCalls = listOf(malformed)),
            ToolTurn(content = "still gathering", toolCalls = listOf(malformed)),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        // Second malformed finalize: best-effort wraps the assistant text — never silent empty.
        assertTrue(outcome is LlmOutcome.Ok)
        val items = (outcome as LlmOutcome.Ok).value.items
        assertTrue(items.isNotEmpty())
        assertEquals("still gathering", items[0].body)
    }

    @Test
    fun `hallucinated url is scrubbed when evidence exists`() = runTest {
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c1", "web_search", """{"query":"ted"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCall(item("Ghost", "s", "b", "https://never-seen.example/x")),
            )),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        val items = (outcome as LlmOutcome.Ok).value.items
        assertEquals(1, items.size) // item kept...
        assertNull(items[0].url)    // ...but the unseen URL is gone
    }

    @Test
    fun `unseen url salvaged to seen source`() = runTest {
        val finalize = ToolCall("f1", "finalize", """
            {"items":[{"title":"S","summary":"s","body":"b","url":"https://ghost.example/x",
            "sources":[{"title":"R","url":"https://r"}]}]}
        """.trimIndent())
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c1", "web_search", """{"query":"ted"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(finalize)),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        val items = (outcome as LlmOutcome.Ok).value.items
        assertEquals("https://r", items[0].url) // salvaged to the seen source
    }

    @Test
    fun `knowledge-only run keeps its citations`() = runTest {
        // No search, no fetch → no evidence → validation skipped entirely.
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCall(item("K", "s", "b", "https://parametric.example/knowledge")),
            )),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals("https://parametric.example/knowledge", (outcome as LlmOutcome.Ok).value.items[0].url)
    }

    @Test
    fun `duplicate urls within a run are deduped`() = runTest {
        val finalize = ToolCall("f1", "finalize", """
            {"items":[{"title":"A","summary":"s","body":"b1","url":"https://r"},
                      {"title":"B","summary":"s","body":"b2","url":"https://r"}]}
        """.trimIndent())
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(ToolCall("c1", "web_search", """{"query":"q"}"""))),
            ToolTurn(content = null, toolCalls = listOf(finalize)),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(1, (outcome as LlmOutcome.Ok).value.items.size)
        assertEquals("A", (outcome as LlmOutcome.Ok).value.items[0].title) // first kept
    }

    @Test
    fun `third search in one round returns budget error and only two execute`() = runTest {
        val counting = object : WebSearchClient {
            var calls = 0
            override suspend fun search(query: String): List<WebSearchHit> {
                calls++
                return listOf(WebSearchHit("Title", "https://r", "snippet"))
            }
        }
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c1", "web_search", """{"query":"a"}"""),
                ToolCall("c2", "web_search", """{"query":"b"}"""),
                ToolCall("c3", "web_search", """{"query":"c"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(finalizeCall(item("R", "s", "b", "u")))),
        ))
        val loop = AgentLoopService(llm, counting, extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(2, counting.calls)
        assertTrue(llm.capturedToolResults.any { it.contains("search budget for this round exhausted") })
    }

    @Test
    fun `search-only finalize gets one corrective fetch nudge`() = runTest {
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c1", "web_search", """{"query":"q"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(finalizeCall(item("Early", "s", "b", "u")))),
            ToolTurn(content = null, toolCalls = listOf(finalizeCall(item("Early", "s", "b", "u")))),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        // First finalize rejected with a corrective message; second accepted.
        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(1, (outcome as LlmOutcome.Ok).value.items.size)
        assertTrue(llm.capturedToolResults.any { it.contains("you have not fetched any page yet") })
    }

    @Test
    fun `idle nudge does not consume a tool round`() = runTest {
        // 8 tool rounds + 1 idle turn in between: idle must not push the run into
        // forceFinalize before the scripted finalize lands.
        val searchTurn = ToolTurn(content = null, toolCalls = listOf(
            ToolCall("c", "web_search", """{"query":"x"}"""),
        ))
        val idleTurn = ToolTurn(content = "let me think", toolCalls = emptyList())
        val turns = buildList {
            add(searchTurn); add(searchTurn); add(searchTurn); add(searchTurn)
            add(idleTurn)
            add(searchTurn); add(searchTurn); add(searchTurn); add(searchTurn)
            add(ToolTurn(content = null, toolCalls = listOf(finalizeCall(item("Done", "s", "b", "u")))))
        }
        val llm = FakeLlm(turns)
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals("Done", (outcome as LlmOutcome.Ok).value.items[0].title)
        assertEquals(10, llm.calls) // 8 tool rounds + 1 idle + 1 finalize — no forced extra round
    }

    @Test
    fun `section reads beyond the section budget return an error`() = runTest {
        fun section(i: Int) = ToolCall("s$i", "fetch_page_section", """{"url":"https://long","offset":${i * 10}}""")
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c0", "fetch_page", """{"url":"https://long"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(section(0), section(1), section(2), section(3))),
            ToolTurn(content = null, toolCalls = listOf(finalizeCall(item("R", "s", "b", "u")))),
        ))
        val recordingBrowser = RecordingBrowser(RenderResult(ok = true, text = "B".repeat(200)))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), recordingBrowser)

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        assertTrue(llm.capturedToolResults.any { it.contains("section budget exhausted") })
    }

    @Test
    fun `oversized conversation is compacted before the LLM call`() = runTest {
        // 3 × 250K-char pages ≈ 750K chars ≈ 187K tokens > 100K ceiling → compaction kicks in.
        val hugeBody = "Z".repeat(250_000)
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c1", "fetch_page", """{"url":"https://huge1"}"""),
                ToolCall("c2", "fetch_page", """{"url":"https://huge2"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c3", "fetch_page", """{"url":"https://huge3"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(finalizeCall(item("R", "s", "b", "u")))),
        ))
        val recordingExtractor = RecordingExtractor(ExtractionOutcome.Ok(title = "H", html = "<p>$hugeBody</p>", byline = null))
        val loop = AgentLoopService(llm, searchOk(), recordingExtractor, browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        // The third call's conversation must be materially smaller than the raw accumulated context.
        val rawChars = 3L * hugeBody.length
        val sentMax = llm.capturedConversationChars.maxOrNull() ?: 0L
        assertTrue("compaction should cap sent context ($sentMax vs raw $rawChars)", sentMax < rawChars / 2)
        assertTrue(llm.capturedToolResults.any { it.contains("elided") } || sentMax < rawChars / 2)
    }

    @Test
    fun `double-malformed finalize salvages item from arguments`() = runTest {
        // Observed live: string-encoded items + broken nested escapes; tool-only turns
        // (content=null). After the failed repair, the run must still salvage content.
        val broken = ToolCall(
            "f1", "finalize",
            """{"items": "[{\"title\": \"Seiko 7018 restoration\", \"summary\": \"s\", \"body\": \"<p>full repair write-up</p>\", \"url\": \"https://watch.example/x\"}]"}""",
        )
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(broken)),
            ToolTurn(content = null, toolCalls = listOf(broken)), // repair round fails too
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        val items = (outcome as LlmOutcome.Ok).value.items
        assertTrue("salvage should keep the model's work, got ${items.size}", items.isNotEmpty())
        assertTrue(items[0].title!!.contains("Seiko 7018"))
    }

    // ---- image harvesting: cover selection + figure injection ----

    @Test
    fun `fetched page images become cover and figures`() = runTest {
        val html = """
            <article>
              <h1>Deep dive</h1>
              <p>${"Long readable body paragraph. ".repeat(12)}</p>
              <img src="https://cdn.example/logo.svg" alt="site logo">
              <img src="https://cdn.example/photos/hero.jpg" alt="The main chart">
              <img src="/img/inline-2.png" alt="Second figure">
              <img src="https://tracker.example/pixel.gif">
            </article>
        """.trimIndent()
        val extractor = RecordingExtractor(ExtractionOutcome.Ok(title = "T", html = html, byline = null))
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c1", "fetch_page", """{"url":"https://page.example/post"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCall(item("R", "s", "<p>body</p>", "https://page.example/post")),
            )),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractor, browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        val item = (outcome as LlmOutcome.Ok).value.items.single()
        assertEquals("https://cdn.example/photos/hero.jpg", item.coverUrl)
        val body = item.body!!
        assertTrue(body.contains("""<figure><img src="https://cdn.example/photos/hero.jpg""""))
        assertTrue(body.contains("""src="https://page.example/img/inline-2.png"""")) // relative resolved
        assertTrue(body.contains("<figcaption>The main chart</figcaption>"))
        assertFalse(body.contains("logo.svg"))    // junk filtered
        assertFalse(body.contains("pixel.gif"))   // tracker filtered
        assertFalse(body.contains("data:"))
    }

    @Test
    fun `images only attach to items citing that page`() = runTest {
        val html = "<article><p>${"Body text. ".repeat(20)}</p><img src=\"https://cdn.example/a.jpg\"></article>"
        val extractor = RecordingExtractor(ExtractionOutcome.Ok(title = "T", html = html, byline = null))
        val finalize = ToolCall("f1", "finalize", """
            {"items":[
              {"title":"Cited","summary":"s","body":"b","url":"https://fetched.example/x"},
              {"title":"Other","summary":"s","body":"b","url":"https://elsewhere.example/y"}
            ]}
        """.trimIndent())
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                ToolCall("c1", "fetch_page", """{"url":"https://fetched.example/x"}"""),
            )),
            ToolTurn(content = null, toolCalls = listOf(finalize)),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractor, browserNotConfigured())
        val outcome = loop.run(job(maxItems = 2))


        assertTrue(outcome is LlmOutcome.Ok)
        val items = (outcome as LlmOutcome.Ok).value.items
        assertEquals("https://cdn.example/a.jpg", items[0].coverUrl) // citing item gets it
        assertNull(items[1].coverUrl)                                // non-citing item doesn't
        assertFalse(items[1].body!!.contains("cdn.example"))
    }

    @Test
    fun `knowledge-only run attaches no images`() = runTest {
        val llm = FakeLlm(listOf(
            ToolTurn(content = null, toolCalls = listOf(
                finalizeCall(item("K", "s", "b", "https://parametric.example/k")),
            )),
        ))
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())

        val outcome = loop.run(job())

        assertTrue(outcome is LlmOutcome.Ok)
        val item = (outcome as LlmOutcome.Ok).value.items.single()
        assertNull(item.coverUrl)
        assertFalse(item.body!!.contains("<figure>"))
    }

    // ---- fixture workload shapes: each of the 8 example directives ----

    @Test
    fun `workload - daily TED digest`() = runFixture(
        goal = "Pick a random TED talk daily and summarize its transcript",
        turns = listOf(
            fetchTurn("https://www.ted.com/talks"),
            fetchTurn("https://www.ted.com/talks/some_talk/transcript"),
            finalizeTurn("TED Talk Summary", "A thought-provoking talk about the future"),
        ),
    )

    @Test
    fun `workload - daily book recommendation`() = runFixture(
        goal = "Recommend a notable book this week with a brief review",
        turns = listOf(
            searchTurn("notable books this week"),
            fetchTurn("https://bookreview.com/best-book"),
            finalizeTurn("Book of the Week", "A compelling read about human nature"),
        ),
    )

    @Test
    fun `workload - daily tips`() = runFixture(
        goal = "Share one practical productivity tip each day",
        turns = listOf(
            searchTurn("productivity tip today"),
            finalizeTurn("Tip: Time-block your morning", "Block the first 90 minutes for deep work"),
        ),
    )

    @Test
    fun `workload - research topic tracking`() = runFixture(
        goal = "Track the latest research on LLM agent frameworks",
        turns = listOf(
            searchTurn("LLM agent frameworks research this week"),
            fetchTurn("https://arxiv.org/abs/2024.12345"),
            finalizeTurn("New Framework: AutoGen-X", "A multi-agent coordination framework"),
        ),
    )

    @Test
    fun `workload - tech news briefing`() = runFixture(
        goal = "Daily briefing on the top tech news",
        turns = listOf(
            searchTurn("top tech news today"),
            finalizeTurn("Tech Briefing", "AI chips, quantum breakthroughs, and more"),
        ),
    )

    @Test
    fun `workload - stock analysis`() = runFixture(
        goal = "Analyze AAPL stock performance and latest earnings",
        turns = listOf(
            searchTurn("AAPL earnings report latest"),
            fetchTurn("https://finance.example.com/aapl-earnings"),
            finalizeTurn("AAPL Analysis", "Strong quarter driven by services revenue"),
        ),
    )

    @Test
    fun `workload - article analysis`() = runFixture(
        goal = "Read and analyze this article: https://example.com/deep-dive",
        turns = listOf(
            fetchTurn("https://example.com/deep-dive"),
            finalizeTurn("Article Analysis", "The article argues that decentralized systems outperform"),
        ),
    )

    @Test
    fun `workload - blog watcher`() = runFixture(
        goal = "Watch https://martinfowler.com for new articles and summarize them",
        turns = listOf(
            fetchTurn("https://martinfowler.com"),
            finalizeTurn("New Post: Event Sourcing Patterns", "Fowler explores common event sourcing pitfalls"),
        ),
    )

    /** Runs a workload fixture: scripted tool turns → asserts non-empty result. */
    private fun runFixture(goal: String, turns: List<ToolTurn>) = runTest {
        val llm = FakeLlm(turns)
        val loop = AgentLoopService(llm, searchOk(), extractorOk(), browserNotConfigured())

        val outcome = loop.run(job(goal = goal))

        assertTrue("fixture for '$goal' should produce Ok", outcome is LlmOutcome.Ok)
        val items = (outcome as LlmOutcome.Ok).value.items
        assertTrue("fixture for '$goal' should produce at least 1 item, got ${items.size}", items.isNotEmpty())
    }

    private fun fetchTurn(url: String) = ToolTurn(content = null, toolCalls = listOf(
        ToolCall("c", "fetch_page", """{"url":"$url"}"""),
    ))

    private fun searchTurn(query: String) = ToolTurn(content = null, toolCalls = listOf(
        ToolCall("c", "web_search", """{"query":"$query"}"""),
    ))

    private fun finalizeTurn(title: String, summary: String) = ToolTurn(content = null, toolCalls = listOf(
        finalizeCall(item(title, summary, "Full body content for $title that is long enough to be meaningful and provides value to the reader who wants to learn about this topic in detail.", "https://source.example.com")),
    ))

    // ---- helpers: tool call builders ----

    private fun finalizeCall(item: Map<String, Any>): ToolCall {
        val itemsJson = item.entries.joinToString(",") { (k, v) ->
            "\"$k\":${if (v is String) "\"$v\"" else v}"
        }
        return ToolCall("f1", "finalize", """{"items":[{$itemsJson}]}""")
    }

    private fun finalizeCallEmpty() = ToolCall("f1", "finalize", """{"items":[]}""")

    private fun item(title: String, summary: String, body: String, url: String) =
        mapOf("title" to title, "summary" to summary, "body" to body, "url" to url)

    // ---- helpers: fakes (house pattern) ----

    /** Scripted LLM: returns queued [ToolTurn]s in order; optional fixed error. */
    private class FakeLlm(
        private val turns: List<ToolTurn> = emptyList(),
        private val error: LlmError? = null,
    ) : LlmClient {
        var calls = 0
        var lastTier: LlmTier? = null
        val capturedToolResults = mutableListOf<String>()
        val capturedConversationChars = mutableListOf<Long>()


        override suspend fun <T> completeStructured(
            tier: LlmTier, systemPrompt: String, userPrompt: String, outputSerializer: KSerializer<T>,
        ): LlmOutcome<T> = throw NotImplementedError("not used by the loop")

        override fun streamText(tier: LlmTier, systemPrompt: String, userPrompt: String): Flow<LlmOutcome<String>> =
            flow { throw NotImplementedError("not used by the loop") }
        override suspend fun completeWithTools(
            tier: LlmTier, systemPrompt: String, conversation: List<ToolMessage>, tools: List<ToolDefinition>,
        ): LlmOutcome<ToolTurn> {
            lastTier = tier
            capturedConversationChars.add(conversation.sumOf { msg ->
                when (msg) {
                    is ToolMessage.User -> msg.content.length.toLong()
                    is ToolMessage.Assistant -> ((msg.content?.length ?: 0) + msg.toolCalls.sumOf { it.arguments.length }).toLong()
                    is ToolMessage.ToolResult -> msg.content.length.toLong()
                }
            })
            // Capture ToolResult messages the loop feeds back (for asserting on tool output content).
            conversation.filterIsInstance<ToolMessage.ToolResult>().forEach {
                capturedToolResults.add(it.content)
            }
            return if (error != null) {
                LlmOutcome.Err(error)
            } else {
                val turn = turns.getOrElse(calls) { turns.last() }
                calls++
                LlmOutcome.Ok(turn)
            }
        }
    }

    private fun searchOk(vararg hits: WebSearchHit) = object : WebSearchClient {
        override suspend fun search(query: String) =
            if (hits.isEmpty()) listOf(WebSearchHit("Title", "https://r", "snippet content here"))
            else hits.toList()
    }

    private fun extractorFail() = object : ArticleExtractor {
        override suspend fun extract(url: String) = ExtractionOutcome.Err.Unreachable
    }

    private fun extractorBlocked() = object : ArticleExtractor {
        override suspend fun extract(url: String) = ExtractionOutcome.Err.Blocked
    }

    private fun extractorOk(text: String = "This is a sufficiently long readable article body that exceeds the one hundred character threshold for content quality. " +
        "It has enough text for the loop to accept it without falling back to the browser render service.") = object : ArticleExtractor {
        override suspend fun extract(url: String) =
            ExtractionOutcome.Ok(title = "T", html = "<p>$text</p>", byline = null)
    }

    private class RecordingExtractor(private val outcome: ExtractionOutcome) : ArticleExtractor {
        override suspend fun extract(url: String) = outcome
    }

    private class RecordingBrowser(private val result: RenderResult) : BrowserClient {
        var calls = 0
        override suspend fun render(request: RenderRequest): RenderResult {
            calls++
            return result
        }
    }

    private fun browserNotConfigured() = object : BrowserClient {
        override suspend fun render(request: RenderRequest) = RenderResult.notConfigured()
    }

    private fun job(
        name: String = "TestAgent",
        goal: String = "summarize the top news about AI",
        maxItems: Int = 1,
    ) = AgentJob(
        id = "j1", name = name, goal = goal,
        task = "", format = "", rules = "",
        maxItems = maxItems, frequency = AgentFrequency.DAILY, triggerTime = "07:00",
        enabled = true, nextRunIntentEpochMs = null, createdAt = 0L,
    )
}
