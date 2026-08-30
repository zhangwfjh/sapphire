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
 * RssFinderFeedFinder — the /gql `searchInFinder` POST + JSON parse. Covers: feeds
 * mapped to hits (title/description/url), blank-title fallback, non-http URLs skipped,
 * missing feeds node, and non-fatal degradation on HTTP error / malformed payload.
 */
@RunWith(RobolectricTestRunner::class)
class RssFinderFeedFinderTest {

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

    private fun client() =
        RssFinderFeedFinder(OkHttpClient(), endpoint = server.url("/gql").toString())

    @Test
    fun `parses feeds into hits with title and description`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"searchInFinder":{"textResult":{"feeds":[
                  {"title":"BBC News - China","description":"BBC News - China","url":"https://feeds.bbci.co.uk/news/world/asia/china/rss.xml"},
                  {"title":"","description":"","url":"https://example.org/feed"}
                ]}}}}
                """.trimIndent(),
            ).setResponseCode(200),
        )

        val hits = client().findFeeds("china")

        assertEquals(2, hits.size)
        assertEquals("BBC News - China", hits[0].title)
        assertEquals("https://feeds.bbci.co.uk/news/world/asia/china/rss.xml", hits[0].url)
        assertEquals("BBC News - China", hits[0].content)
        // Blank title falls back to the URL.
        assertEquals("https://example.org/feed", hits[1].title)
    }

    @Test
    fun `json null description does not render as literal null string`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"searchInFinder":{"textResult":{"feeds":[
                  {"title":"Dui Hua","description":null,"url":"https://example.org/feeds/posts/default"}
                ]}}}}""",
            ).setResponseCode(200),
        )

        val hits = client().findFeeds("china")

        assertEquals(1, hits.size)
        assertEquals("", hits[0].content)
        assertEquals("Dui Hua", hits[0].title)
    }

    @Test
    fun `non-http feed urls are skipped`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"searchInFinder":{"textResult":{"feeds":[
                  {"title":"a","description":"","url":"javascript:alert(1)"},
                  {"title":"b","description":"","url":"https://ok.example/feed"}
                ]}}}}""",
            ).setResponseCode(200),
        )

        val hits = client().findFeeds("x")

        assertEquals(1, hits.size)
        assertEquals("https://ok.example/feed", hits[0].url)
    }

    @Test
    fun `missing feeds node degrades to empty`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"data":{"searchInFinder":{"textResult":{}}}}""").setResponseCode(200),
        )

        assertTrue(client().findFeeds("x").isEmpty())
    }

    @Test
    fun `500 response degrades to empty rather than throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        assertTrue(client().findFeeds("x").isEmpty())
    }

    @Test
    fun `malformed payload degrades to empty rather than throwing`() = runTest {
        server.enqueue(MockResponse().setBody("<html>not json</html>").setResponseCode(200))

        assertTrue(client().findFeeds("x").isEmpty())
    }
}
