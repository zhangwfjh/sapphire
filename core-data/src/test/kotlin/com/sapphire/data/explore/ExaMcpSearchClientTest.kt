package com.sapphire.data.explore

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ExaMcpSearchClient — the JSON-RPC `tools/call` + SSE `data:` parser. Covers: text-block
 * extraction (Title:/URL:/Highlights:), non-text blocks skipped, missing URL skipped,
 * missing data line, and non-fatal degradation on HTTP error / malformed payload.
 */
@RunWith(RobolectricTestRunner::class)
class ExaMcpSearchClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): ExaMcpSearchClient =
        ExaMcpSearchClient(OkHttpClient(), endpoint = server.url("/mcp").toString())

    /** SSE body carrying text blocks as the `data:` JSON payload. */
    private fun sse(vararg textBlocks: String): String {
        val arr = org.json.JSONArray()
        textBlocks.forEach { t -> arr.put(org.json.JSONObject().put("type", "text").put("text", t)) }
        val full = org.json.JSONObject().put("result", org.json.JSONObject().put("content", arr))
        return "event: message\ndata: $full\n\n"
    }

    private fun block(title: String, url: String) =
        "Title: $title\nURL: $url\nHighlights: something relevant about $title"

    @Test
    fun `parses text blocks into hits`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                sse(block("Rust Feed", "https://blog.rust-lang.org/feed.xml")),
            ).setResponseCode(200),
        )

        val hits = client().search("rust blog")

        assertEquals(1, hits.size)
        assertEquals("Rust Feed", hits[0].title)
        assertEquals("https://blog.rust-lang.org/feed.xml", hits[0].url)
        assertTrue(hits[0].content.contains("Rust Feed"))
    }

    @Test
    fun `block without url is skipped`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                sse("Title: No Link\nHighlights: content only"),
            ).setResponseCode(200),
        )

        val hits = client().search("test")

        assertTrue(hits.isEmpty())
    }

    @Test
    fun `blank title falls back to url`() = runTest {
        server.enqueue(
            MockResponse().setBody(sse("URL: https://example.com/x\nHighlights: h")).setResponseCode(200),
        )

        val hits = client().search("test")

        assertEquals(1, hits.size)
        assertEquals("https://example.com/x", hits[0].title)
    }

    @Test
    fun `non-text content blocks are skipped`() = runTest {
        val arr = org.json.JSONArray()
            .put(org.json.JSONObject().put("type", "image").put("text", block("I", "https://i")))
            .put(org.json.JSONObject().put("type", "text").put("text", block("T", "https://t")))
        val full = org.json.JSONObject().put("result", org.json.JSONObject().put("content", arr))
        server.enqueue(MockResponse().setBody("data: $full\n").setResponseCode(200))

        val hits = client().search("test")

        assertEquals(listOf("https://t"), hits.map { it.url })
    }

    @Test
    fun `response without data line degrades to empty`() = runTest {
        server.enqueue(MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"id\":1}").setResponseCode(200))

        val hits = client().search("test")

        assertTrue(hits.isEmpty())
    }

    @Test
    fun `500 response degrades to empty rather than throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val hits = client().search("test")

        assertTrue(hits.isEmpty())
    }

    @Test
    fun `malformed data payload degrades to empty`() = runTest {
        server.enqueue(MockResponse().setBody("data: {not json").setResponseCode(200))

        val hits = client().search("test")

        assertTrue(hits.isEmpty())
    }
}
