package com.sapphire.domain.explore

import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SearchFeedsUseCase — LLM-free topic search over the [FeedFinder] engine only. URL-shaped
 * queries short-circuit with no network; topic queries pass the RAW keyword to the finder
 * and hand its feed hits to the harvester for verification. The web-search engine chain is
 * not involved (agent-loop retrieval). Empty yield anywhere surfaces Empty.
 */
class SearchFeedsUseCaseTest {

    @Test
    fun `blank query returns Empty error without calling finder or harvester`() = runTest {
        val finder = RecordingFeedFinder()
        val harvester = RecordingHarvester()
        val useCase = SearchFeedsUseCase(finder, harvester)

        val outcome = useCase.invoke("   ")

        assertTrue(outcome is LlmOutcome.Err)
        assertTrue((outcome as LlmOutcome.Err).error is LlmError.Empty)
        assertEquals(0, finder.calls)
        assertEquals(0, harvester.calls)
    }

    @Test
    fun `URL query returns single result without calling finder or harvester`() = runTest {
        val finder = RecordingFeedFinder()
        val harvester = RecordingHarvester()
        val useCase = SearchFeedsUseCase(finder, harvester)

        val outcome = useCase.invoke("https://hnrss.org/frontpage")

        assertTrue(outcome is LlmOutcome.Ok)
        val results = (outcome as LlmOutcome.Ok).value
        assertEquals(1, results.size)
        assertEquals("https://hnrss.org/frontpage", results[0].url)
        assertEquals("hnrss.org/frontpage", results[0].title)
        assertEquals(0, finder.calls)
        assertEquals(0, harvester.calls)
    }

    @Test
    fun `URL query without scheme still detected as URL`() = runTest {
        val useCase = SearchFeedsUseCase(RecordingFeedFinder(), RecordingHarvester())

        val outcome = useCase.invoke("example.com/feed.xml")

        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals("example.com/feed.xml", (outcome as LlmOutcome.Ok).value[0].url)
    }

    @Test
    fun `topic query passes the raw keyword to the finder and returns verified results`() = runTest {
        val hits = listOf(
            WebSearchHit(title = "BBC News - China", url = "https://feeds.bbci.co.uk/news/world/asia/china/rss.xml", content = "curated"),
        )
        val finder = RecordingFeedFinder(hits)
        val harvested = listOf(
            FeedSearchResult(title = "BBC News - China", url = "https://feeds.bbci.co.uk/news/world/asia/china/rss.xml", kind = "rss"),
        )
        val harvester = RecordingHarvester(harvested)
        val useCase = SearchFeedsUseCase(finder, harvester)

        val outcome = useCase.invoke("china")

        assertEquals(listOf("china"), finder.topics)
        assertEquals(hits, harvester.lastHits)
        assertTrue(outcome is LlmOutcome.Ok)
        assertEquals(harvested, (outcome as LlmOutcome.Ok).value)
    }

    @Test
    fun `finder returning no hits returns Empty and never harvests`() = runTest {
        val finder = RecordingFeedFinder(emptyList())
        val harvester = RecordingHarvester(
            listOf(FeedSearchResult(title = "x", url = "https://x.com/feed")),
        )
        val useCase = SearchFeedsUseCase(finder, harvester)

        val outcome = useCase.invoke("biohacking")

        assertTrue(outcome is LlmOutcome.Err)
        assertTrue((outcome as LlmOutcome.Err).error is LlmError.Empty)
        assertEquals(0, harvester.calls)
    }

    @Test
    fun `empty harvest returns Empty error`() = runTest {
        val finder = RecordingFeedFinder(
            listOf(WebSearchHit(title = "Dir", url = "https://example.com", content = "")),
        )
        val useCase = SearchFeedsUseCase(finder, RecordingHarvester(emptyList()))

        val outcome = useCase.invoke("obscure topic")

        assertTrue(outcome is LlmOutcome.Err)
        assertTrue((outcome as LlmOutcome.Err).error is LlmError.Empty)
    }

    @Test
    fun `finder throwing degrades to Empty rather than propagating`() = runTest {
        val useCase = SearchFeedsUseCase(ThrowingFeedFinder(), RecordingHarvester())

        val outcome = useCase.invoke("obscure topic")

        assertTrue(outcome is LlmOutcome.Err)
        assertTrue((outcome as LlmOutcome.Err).error is LlmError.Empty)
    }

    @Test
    fun `harvester throwing degrades to Empty rather than propagating`() = runTest {
        val finder = RecordingFeedFinder(
            listOf(WebSearchHit(title = "Dir", url = "https://example.com", content = "")),
        )
        val useCase = SearchFeedsUseCase(finder, ThrowingHarvester())

        val outcome = useCase.invoke("obscure topic")

        assertTrue(outcome is LlmOutcome.Err)
        assertTrue((outcome as LlmOutcome.Err).error is LlmError.Empty)
    }

    // ---------- helpers ----------

    private class RecordingFeedFinder(
        private val hits: List<WebSearchHit> = emptyList(),
    ) : FeedFinder {
        var calls = 0
            private set
        val topics = mutableListOf<String>()

        override suspend fun findFeeds(topic: String): List<WebSearchHit> {
            calls++
            topics += topic
            return hits
        }
    }

    private class ThrowingFeedFinder : FeedFinder {
        override suspend fun findFeeds(topic: String): List<WebSearchHit> = error("finder down")
    }

    private class RecordingHarvester(
        private val results: List<FeedSearchResult> = emptyList(),
    ) : FeedLinkHarvester {
        var calls = 0
            private set
        var lastHits: List<WebSearchHit>? = null
            private set

        override suspend fun harvest(hits: List<WebSearchHit>): List<FeedSearchResult> {
            calls++
            lastHits = hits
            return results
        }
    }

    private class ThrowingHarvester : FeedLinkHarvester {
        override suspend fun harvest(hits: List<WebSearchHit>): List<FeedSearchResult> =
            error("harvest down")
    }
}
