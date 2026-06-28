package com.sapphire.data.reader

import com.sapphire.domain.reader.ExtractionOutcome
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

@RunWith(RobolectricTestRunner::class)
class ReadabilityArticleExtractorTest {
    private lateinit var server: MockWebServer
    private lateinit var extractor: ReadabilityArticleExtractor

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        extractor = ReadabilityArticleExtractor(OkHttpClient())
    }
    @After fun tearDown() { server.shutdown() }

    private fun loadFixture(name: String): String =
        javaClass.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `extracts ordered paragraphs from a full article`() = runTest {
        server.enqueue(MockResponse().setBody(loadFixture("/readability-sample-article.html")).setHeader("Content-Type", "text/html"))
        val outcome = extractor.extract(server.url("/post").toString())
        assertTrue(outcome is ExtractionOutcome.Ok)
        val ok = outcome as ExtractionOutcome.Ok
        assertTrue(ok.paragraphs.size >= 3)
        assertTrue(ok.paragraphs.first().contains("first paragraph"))
        assertTrue(ok.paragraphs.last().contains("third paragraph"))
    }

    @Test
    fun `returns Blocked on 4xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val outcome = extractor.extract(server.url("/paywall").toString())
        assertEquals(ExtractionOutcome.Err.Blocked, outcome)
    }

    @Test
    fun `returns Empty when readability extracts nothing usable`() = runTest {
        server.enqueue(MockResponse().setBody(loadFixture("/readability-empty-shell.html")).setHeader("Content-Type", "text/html"))
        val outcome = extractor.extract(server.url("/spa").toString())
        assertEquals(ExtractionOutcome.Err.Empty, outcome)
    }

    @Test
    fun `returns Unreachable on connection failure`() = runTest {
        server.shutdown()
        val outcome = extractor.extract(server.url("/dead").toString())
        assertTrue(outcome is ExtractionOutcome.Err.Unreachable)
    }
}
