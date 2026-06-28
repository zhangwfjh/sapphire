package com.sapphire.data.feed

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sapphire.data.db.FeedDao
import com.sapphire.data.db.OnboardingDao
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.data.db.SourceEntity
import com.sapphire.data.db.TopicEntity
import com.sapphire.data.db.CategoryEntity
import com.sapphire.domain.feed.FetchResult
import com.sapphire.domain.feed.Fetcher
import com.sapphire.domain.feed.FeedItemCandidate
import com.sapphire.domain.model.HealthState
import com.sapphire.domain.model.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ingest pipeline test: candidates → [FeedRefreshService] → hash → Room (PK dedup).
 * Uses a fake [Fetcher] so no network; asserts the cheap hash-dedup PRD §3.2 requires and
 * that re-fetching a source doesn't duplicate or overwrite existing rows.
 */
@RunWith(RobolectricTestRunner::class)
class FeedRefreshServiceTest {

    private lateinit var db: SapphireDatabase
    private lateinit var feedDao: FeedDao
    private lateinit var onboarding: OnboardingDao
    private lateinit var sources: RoomSourceFeedQuery
    private lateinit var refresh: FeedRefreshService

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SapphireDatabase::class.java,
        ).allowMainThreadQueries().build()
        feedDao = db.feedDao()
        onboarding = db.onboardingDao()
        sources = RoomSourceFeedQuery(onboarding)
    }

    @After fun tearDown() { db.close() }

    private suspend fun seedSource(id: String, url: String, kind: SourceKind = SourceKind.RSS) {
        val topicId = "topic-$id"
        onboarding.commitOnboarding(
            TopicEntity(topicId, "AI", 0L),
            listOf(
                CategoryEntity("cat-l1-$id", topicId, 1, null, "Tech", 0),
                CategoryEntity("cat-l2-$id", topicId, 2, "cat-l1-$id", "AI", 0),
            ),
            emptyList(),
            listOf(SourceEntity(id, "cat-l2-$id", topicId, kind, url, "Blog")),
        )
    }

    @Test
    fun `candidates are hashed and inserted into timeline`() = runTest {
        seedSource("s1", "https://feed")
        val fetcher = StaticFetcher(
            listOf(
                FeedItemCandidate(
                    title = "First",
                    summary = "hello",
                    canonicalUrl = "https://example.com/1",
                    authorHandle = null,
                    publishedAt = 1000L,
                    platformTag = null,
                    mediaUrl = null,
                ),
                FeedItemCandidate(
                    title = "Second",
                    summary = null,
                    canonicalUrl = "https://example.com/2",
                    authorHandle = null,
                    publishedAt = 2000L,
                    platformTag = null,
                    mediaUrl = null,
                ),
            ),
        )
        refresh = FeedRefreshService(FetcherRegistry.forTesting(mapOf(SourceKind.RSS to fetcher)), feedDao, sources)

        val outcome = refresh.refreshAll()
        assertEquals(2, outcome.totalNew)
        assertTrue(outcome.errors.isEmpty())

        val timeline = feedDao.observeTimeline().first()
        assertEquals(2, timeline.size)
        assertEquals("Second", timeline[0].title) // newer first
    }

    @Test
    fun `re-fetching same urls is a no-op dedup`() = runTest {
        seedSource("s1", "https://feed")
        val fetcher = StaticFetcher(
            listOf(
                FeedItemCandidate("Dup", null, "https://example.com/x", null, 0L, null, null),
                FeedItemCandidate("Other", null, "https://example.com/y", null, 0L, null, null),
            ),
        )
        refresh = FeedRefreshService(FetcherRegistry.forTesting(mapOf(SourceKind.RSS to fetcher)), feedDao, sources)

        refresh.refreshAll()
        val second = refresh.refreshAll()

        assertEquals(0, second.totalNew) // both hashes already exist
        assertEquals(2, feedDao.countItems())
    }

    @Test
    fun `items with same url from different source ids are distinct`() = runTest {
        // PRD §3.2 identity is (sourceId, url) — same story across two feeds stays as two
        // rows; semantic dedup (S04) collapses near-dupes only on the agent path.
        seedSource("s1", "https://feed-a")
        seedSource("s2", "https://feed-b")
        val fetcher = StaticFetcher(
            listOf(FeedItemCandidate("Same", null, "https://example.com/post", null, 0L, null, null)),
        )
        refresh = FeedRefreshService(FetcherRegistry.forTesting(mapOf(SourceKind.RSS to fetcher)), feedDao, sources)

        val outcome = refresh.refreshAll()
        assertEquals(2, outcome.totalNew)
        assertEquals(2, feedDao.countItems())
    }

    @Test
    fun `persistent failure flips source health to FAILED`() = runTest {
        seedSource("s1", "https://broken")
        val fetcher = StaticFetcher(emptyList(), FetchResult.PersistentFailure("410"))
        refresh = FeedRefreshService(FetcherRegistry.forTesting(mapOf(SourceKind.RSS to fetcher)), feedDao, sources)

        val outcome = refresh.refreshAll()
        assertEquals(0, outcome.totalNew)
        assertEquals(1, outcome.errors.size)

        val src = sources.allSources().first()
        assertEquals(HealthState.FAILED, src.healthState)
        assertNotNull(src.lastErrorAt)
    }

    @Test
    fun `transient error does not flip health`() = runTest {
        seedSource("s1", "https://flakey")
        val fetcher = StaticFetcher(emptyList(), FetchResult.TransientError("timeout"))
        refresh = FeedRefreshService(FetcherRegistry.forTesting(mapOf(SourceKind.RSS to fetcher)), feedDao, sources)

        refresh.refreshAll()
        val src = sources.allSources().first()
        assertEquals(HealthState.OK, src.healthState)
        assertNull(src.lastErrorAt)
    }

    @Test
    fun `candidate without url falls back to title plus publishedAt hash`() = runTest {
        seedSource("s1", "https://feed")
        val fetcher = StaticFetcher(
            listOf(
                FeedItemCandidate("No Link", "body", null, null, 5000L, null, null),
            ),
        )
        refresh = FeedRefreshService(FetcherRegistry.forTesting(mapOf(SourceKind.RSS to fetcher)), feedDao, sources)
        val outcome = refresh.refreshAll()
        assertEquals(1, outcome.totalNew)
        val item = feedDao.observeTimeline().first().single()
        assertEquals("No Link", item.title)
    }

    /**
     * End-to-end: the REAL [RssAtomFetcher.parse] output (from a captured HN feed with
     * CDATA + dc:creator + numeric-offset pubDate) flows through refreshAll into the
     * timeline query. Catches parser regressions the StaticFetcher tests cannot see.
     */
    @Test
    fun `real rss parse output lands in timeline`() = runTest {
        seedSource("src-real", "https://hnrss.org/frontpage")
        val xml = javaClass.getResourceAsStream("/hn-frontpage.xml")!!.bufferedReader().readText()
        val parsed = RssAtomFetcher(okhttp3.OkHttpClient()).parse(xml) as FetchResult.Success
        val realFetcher = object : Fetcher {
            override suspend fun fetch(url: String, configJson: String?): FetchResult = parsed
        }
        val registry = FetcherRegistry.forTesting(mapOf(SourceKind.RSS to realFetcher))
        val refresh = FeedRefreshService(registry, feedDao, sources)
        val outcome = refresh.refreshAll()
        assertEquals(2, outcome.totalNew)
        assertEquals(1, outcome.fetchedSources)
        val timeline = feedDao.observeTimeline().first()
        assertEquals(2, timeline.size)
        assertEquals("Raspberry Pi Pico W as USB Wi-Fi Adapter", timeline[0].title)
    }

    @Test
    fun `refreshStreaming emits one event per source and an AllDone, concurrent sources do not crash`() = runTest {
        // Regression: concurrent emit() from sibling coroutines used to throw
        // "Flow is sequential, concurrent emit is not allowed". Multiple sources
        // completing around the same time must be drained safely.
        seedSource("s1", "https://feed-a")
        seedSource("s2", "https://feed-b")
        seedSource("s3", "https://feed-c")
        val fetcher = StaticFetcher(
            listOf(FeedItemCandidate("Post", null, "https://example.com/x", null, 0L, null, null)),
        )
        refresh = FeedRefreshService(FetcherRegistry.forTesting(mapOf(SourceKind.RSS to fetcher)), feedDao, sources)

        val events = refresh.refreshStreaming().toCollection(mutableListOf())

        val sourceDone = events.filterIsInstance<FeedRefreshService.StreamEvent.SourceDone>()
        assertEquals(3, sourceDone.size)
        assertTrue(events.last() is FeedRefreshService.StreamEvent.AllDone)
        assertEquals(3, feedDao.countItems())
    }

    @Test
    fun `refreshStreaming survives a throwing fetcher and maps it to SourceError`() = runTest {
        // Regression: an uncaught throw from a real fetcher (parser RuntimeException,
        // SQLiteException, etc.) used to propagate through coroutineScope → flow → collect
        // and crash the app. It must be contained to the failing source.
        seedSource("s1", "https://feed-ok")
        seedSource("s2", "https://feed-boom")
        seedSource("s3", "https://feed-other")
        val fetcher = StaticFetcher(
            listOf(FeedItemCandidate("Post", null, "https://example.com/x", null, 0L, null, null)),
            throwOn = "https://feed-boom",
        )
        refresh = FeedRefreshService(FetcherRegistry.forTesting(mapOf(SourceKind.RSS to fetcher)), feedDao, sources)

        val events = refresh.refreshStreaming().toCollection(mutableListOf())

        val errors = events.filterIsInstance<FeedRefreshService.StreamEvent.SourceError>()
        val done = events.filterIsInstance<FeedRefreshService.StreamEvent.SourceDone>()
        assertEquals(1, errors.size)
        assertTrue(errors.single().message.contains("boom"))
        // The other two sources still completed despite the sibling throwing.
        assertEquals(2, done.size)
        assertTrue(events.last() is FeedRefreshService.StreamEvent.AllDone)
        assertEquals(2, feedDao.countItems())
    }

    private class StaticFetcher(
        private val items: List<FeedItemCandidate>,
        private val result: FetchResult = FetchResult.Success(items),
        private val throwOn: String? = null,
    ) : Fetcher {
        override suspend fun fetch(url: String, configJson: String?): FetchResult {
            if (throwOn != null && url == throwOn) throw RuntimeException("boom from $url")
            return result
        }
    }
}

