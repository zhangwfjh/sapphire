package com.sapphire.data.explore

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TavilySearchClientTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(apiKey: String = KEY): TavilySearchClient =
        TavilySearchClient(apiKey, json, OkHttpClient(), endpoint = server.url("/search").toString())

    @Test
    fun `blank api key short-circuits to empty without calling the endpoint`() = runTest {
        val results = client(apiKey = "").search("anything")

        assertTrue(results.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `successful response maps title url and content into hits`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"results":[
                  {"title":"AI Blog","url":"https://example.com/feed","content":"Recent AI posts"},
                  {"title":"ML News","url":"https://ml.example.com/rss","content":"Machine learning updates"}
                ]}
                """.trimIndent(),
            ),
        )

        val results = client().search("artificial intelligence")

        assertEquals(2, results.size)
        assertEquals("AI Blog", results[0].title)
        assertEquals("https://example.com/feed", results[0].url)
        assertEquals("Recent AI posts", results[0].content)
        assertEquals("ML News", results[1].title)
    }

    @Test
    fun `sends api_key and query in the request body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[]}"""))

        client().search("biohacking")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val body = recorded.body.readUtf8()
        assertTrue("body must carry the api_key", body.contains("\"api_key\":\"$KEY\""))
        assertTrue("body must carry the query", body.contains("\"query\":\"biohacking\""))
    }

    @Test
    fun `non-2xx response degrades to empty rather than throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"invalid key"}"""))

        val results = client().search("anything")

        assertTrue(results.isEmpty())
    }

    @Test
    fun `malformed json body degrades to empty rather than throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        val results = client().search("anything")

        assertTrue(results.isEmpty())
    }

    @Test
    fun `missing results array is treated as empty`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"answer":"no hits"}"""))

        val results = client().search("anything")

        assertTrue(results.isEmpty())
    }

    private companion object {
        const val KEY = "tvly-test-key"
    }
}
