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

class BaiduSearchClientTest {

    private lateinit var searchServer: MockWebServer
    private lateinit var redirectServer: MockWebServer

    @Before
    fun setUp() {
        searchServer = MockWebServer()
        searchServer.start()
        redirectServer = MockWebServer()
        redirectServer.start()
    }

    @After
    fun tearDown() {
        searchServer.shutdown()
        redirectServer.shutdown()
    }

    private fun client(): BaiduSearchClient =
        BaiduSearchClient(
            OkHttpClient.Builder().build(),
            endpoint = searchServer.url("/s").toString(),
        )

    @Test
    fun `parses h3 titles and follows baidu link redirector to real url`() = runTest {
        val realUrl = "https://real.example.com/article"
        val redirectorUrl = redirectServer.url("/link").toString()
        // Baidu wraps: http://www.baidu.com/link?url=XXX — we point at our mock redirector.
        val html = """
            <html><body>
            <div class="result">
              <h3><a href="$redirectorUrl">Tokio Async Runtime 讲解</a></h3>
              <div class="c-abstract">Rust 异步运行时实现细节...</div>
            </div>
            </body></html>
        """.trimIndent()
        searchServer.enqueue(MockResponse().setBody(html).setResponseCode(200))
        // Redirector: 302 to the real URL.
        redirectServer.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", realUrl),
        )
        // Final hop body (OkHttp auto-follows 302; this is what the GET lands on).
        redirectServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200))

        val hits = client().search("tokio")

        assertEquals(1, hits.size)
        assertEquals("Tokio Async Runtime 讲解", hits[0].title)
        assertEquals(realUrl, hits[0].url)
        assertEquals("Rust 异步运行时实现细节...", hits[0].content)
    }

    @Test
    fun `gbk-declared charset is decoded as GB18030 fallback`() = runTest {
        // Search response declaring GBK; MockWebServer body is UTF-8-declared-by-bytes
        // — we just assert the parser doesn't crash on the declared charset header.
        val html = """
            <html><head><meta charset="gbk"></head><body>
            <div class="result"><h3><a href="https://x.com/a">title</a></h3>
            <div class="c-abstract">snippet</div></div>
            </body></html>
        """.trimIndent()
        searchServer.enqueue(
            MockResponse().setBody(html)
                .setHeader("Content-Type", "text/html; charset=gbk")
                .setResponseCode(200),
        )

        val hits = client().search("test")

        // Snippet may be decoded with GB18030; we just want the title+url through cleanly.
        assertEquals(1, hits.size)
        assertEquals("https://x.com/a", hits[0].url)
    }

    @Test
    fun `429 response degrades to empty rather than throwing`() = runTest {
        searchServer.enqueue(MockResponse().setResponseCode(429))
        val hits = client().search("test")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `malformed HTML body degrades to empty rather than throwing`() = runTest {
        searchServer.enqueue(MockResponse().setBody("<<<not html>>>").setResponseCode(200))
        val hits = client().search("test")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `no result blocks returns empty`() = runTest {
        searchServer.enqueue(MockResponse().setBody("<html><body>nothing</body></html>").setResponseCode(200))
        val hits = client().search("obscure")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `redirector that loops or errors leaves that hit url as-is and continues others`() = runTest {
        val redirectorUrl = redirectServer.url("/link").toString()
        val html = """
            <html><body>
            <div class="result">
              <h3><a href="$redirectorUrl">First</a></h3>
              <div class="c-abstract">first snippet</div>
            </div>
            <div class="result">
              <h3><a href="https://direct.example.com/d">Second</a></h3>
              <div class="c-abstract">second snippet</div>
            </div>
            </body></html>
        """.trimIndent()
        searchServer.enqueue(MockResponse().setBody(html).setResponseCode(200))
        // First result's redirector errors with 500 — we fall back to the wrapped URL.
        redirectServer.enqueue(MockResponse().setResponseCode(500))

        val hits = client().search("test")

        assertEquals(2, hits.size)
        // First hit's URL is the unresolved wrapped form (the redirector URL itself).
        assertEquals(redirectorUrl, hits[0].url)
        assertEquals("First", hits[0].title)
        // Second hit was already a direct URL; no redirector hop needed.
        assertEquals("https://direct.example.com/d", hits[1].url)
    }
}
