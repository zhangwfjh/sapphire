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

/**
 * BingSearchClient — the `b_algo` block parser. Covers: block extraction, Bing-internal
 * URL blacklist, snippet cleanup, dedup, and non-fatal degradation (4xx / malformed /
 * empty). MockWebServer + injected endpoint, house pattern.
 */
class BingSearchClientTest {

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

    private fun client(): BingSearchClient =
        BingSearchClient(OkHttpClient(), endpoint = server.url("/search").toString())

    @Test
    fun `parses b_algo blocks into hits`() = runTest {
        val html = """
            <html><body>
            <li class="b_algo"><h2><a href="https://example.com/rust">Rust Guide</a></h2>
              <p class="b_lineclamp2">The Rust programming language guide</p></li>
            <li class="b_algo"><h2><a href="https://example.com/kotlin">Kotlin Docs</a></h2>
              <p class="b_lineclamp2">Kotlin documentation home</p></li>
            </body></html>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))

        val hits = client().search("rust")

        assertEquals(2, hits.size)
        assertEquals("https://example.com/rust", hits[0].url)
        assertTrue(hits[0].content.contains("Rust programming language"))
    }

    @Test
    fun `bing-internal and microsoft urls are blacklisted`() = runTest {
        val html = """
            <html><body>
            <li class="b_algo"><h2><a href="https://www.bing.com/aclick">Ad</a></h2><p class="b_lineclamp2">x</p></li>
            <li class="b_algo"><h2><a href="https://go.microsoft.com/fwlink">MS</a></h2><p class="b_lineclamp2">y</p></li>
            <li class="b_algo"><h2><a href="https://example.com/real">Real</a></h2><p class="b_lineclamp2">z</p></li>
            </body></html>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))

        val hits = client().search("test")

        assertEquals(listOf("https://example.com/real"), hits.map { it.url })
    }

    @Test
    fun `duplicate urls collapse to one hit`() = runTest {
        val html = """
            <html><body>
            <li class="b_algo"><h2><a href="https://example.com/same">First</a></h2><p class="b_lineclamp2">a</p></li>
            <li class="b_algo"><h2><a href="https://example.com/same">Second</a></h2><p class="b_lineclamp2">b</p></li>
            </body></html>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))

        val hits = client().search("test")

        assertEquals(1, hits.size)
    }

    @Test
    fun `snippet html entities are stripped`() = runTest {
        val html = """
            <html><body>
            <li class="b_algo"><h2><a href="https://example.com/e">E</a></h2>
              <p class="b_lineclamp2"><b>bold</b> &amp; amp</p></li>
            </body></html>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))

        val hits = client().search("test")

        assertEquals(1, hits.size)
        assertTrue("snippet should lose markup: ${hits[0].content}", !hits[0].content.contains("<b>"))
    }

    @Test
    fun `no b_algo blocks returns empty`() = runTest {
        server.enqueue(MockResponse().setBody("<html><body>captcha wall</body></html>").setResponseCode(200))

        val hits = client().search("test")

        assertTrue(hits.isEmpty())
    }

    @Test
    fun `429 response degrades to empty rather than throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))

        val hits = client().search("test")

        assertTrue(hits.isEmpty())
    }

    @Test
    fun `malformed body degrades to empty rather than throwing`() = runTest {
        server.enqueue(MockResponse().setBody("total garbage <<>>").setResponseCode(200))

        val hits = client().search("test")

        assertTrue(hits.isEmpty())
    }
}
