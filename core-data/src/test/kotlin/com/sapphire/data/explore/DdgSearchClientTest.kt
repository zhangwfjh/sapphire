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

class DdgSearchClientTest {

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

    private fun client(): DdgSearchClient =
        DdgSearchClient(OkHttpClient(), endpoint = server.url("/html/").toString())

    @Test
    fun `parses result__a titles urls and result__snippet contents`() = runTest {
        val html = """
            <html><body>
            <div class="result">
              <a class="result__a" href="https://example.com/rust">Rust Async Runtime</a>
              <a class="result__snippet">Tokio is the leading async runtime...</a>
            </div>
            <div class="result">
              <a class="result__a" href="https://example.com/async">Async Programming</a>
              <a class="result__snippet">async/await syntax...</a>
            </div>
            </body></html>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))

        val hits = client().search("rust async")

        assertEquals(2, hits.size)
        assertEquals("Rust Async Runtime", hits[0].title)
        assertEquals("https://example.com/rust", hits[0].url)
        assertEquals("Tokio is the leading async runtime...", hits[0].content)
        assertEquals("Async Programming", hits[1].title)
    }

    @Test
    fun `DDG redirect-wrap links are stripped to the raw URL via uddg param`() = runTest {
        // DDG wraps external links as //duckduckgo.com/l/?uddg=<encoded>
        val html = """
            <html><body>
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Freal">Real</a>
              <a class="result__snippet">snippet</a>
            </div>
            </body></html>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))

        val hits = client().search("test")

        assertEquals(1, hits.size)
        assertEquals("https://example.com/real", hits[0].url)
    }

    @Test
    fun `429 response degrades to empty rather than throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        val hits = client().search("test")
        assertTrue("expected empty on 429", hits.isEmpty())
    }

    @Test
    fun `malformed HTML body degrades to empty rather than throwing`() = runTest {
        server.enqueue(MockResponse().setBody("not html at all <><<").setResponseCode(200))
        val hits = client().search("test")
        assertTrue("expected empty on malformed html", hits.isEmpty())
    }

    @Test
    fun `no result blocks returns empty`() = runTest {
        server.enqueue(MockResponse().setBody("<html><body>no results</body></html>").setResponseCode(200))
        val hits = client().search("obscurequery")
        assertTrue(hits.isEmpty())
    }
}
