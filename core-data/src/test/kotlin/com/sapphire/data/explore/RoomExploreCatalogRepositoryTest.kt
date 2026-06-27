package com.sapphire.data.explore

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sapphire.data.db.DiscoveredFeedEntity
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.domain.explore.ExploreSection
import com.sapphire.domain.model.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomExploreCatalogRepositoryTest {

    private lateinit var db: SapphireDatabase
    private lateinit var repo: RoomExploreCatalogRepository
    private lateinit var discoveredRepo: RoomDiscoveredFeedRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, SapphireDatabase::class.java)
            .allowMainThreadQueries().build()
        val parser = CatalogAssetParser(Json { ignoreUnknownKeys = true; explicitNulls = false })
        repo = RoomExploreCatalogRepository(context, parser, db.discoveredFeedDao())
        discoveredRepo = RoomDiscoveredFeedRepository(db.discoveredFeedDao())
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `discovered pool yields a trailing Recently discovered rail`() = runTest {
        db.discoveredFeedDao().upsert(
            DiscoveredFeedEntity(
                id = "x", title = "Found Feed", url = "https://example.com/f",
                kind = SourceKind.RSS, discoveredAt = 1L, subscribeCount = 1,
            ),
        )
        val sections = repo.observeCatalog().first()
        val discovered = sections.filterIsInstance<ExploreSection.RecentlyDiscovered>()
        assertEquals(1, discovered.size)
        assertEquals("Found Feed", discovered[0].feeds[0].title)
    }

    @Test
    fun `empty discovered pool yields no Recently discovered rail`() = runTest {
        val sections = repo.observeCatalog().first()
        assertTrue(sections.none { it is ExploreSection.RecentlyDiscovered })
    }

    @Test
    fun `Recently discovered rail appears last`() = runTest {
        db.discoveredFeedDao().upsert(
            DiscoveredFeedEntity(
                id = "x", title = "Found", url = "https://example.com/f",
                kind = SourceKind.RSS, discoveredAt = 1L, subscribeCount = 1,
            ),
        )
        val sections = repo.observeCatalog().first()
        assertTrue(sections.last() is ExploreSection.RecentlyDiscovered)
    }

    @Test
    fun `record stores a schemeful url so the discovered rail round-trips to a fetchable source`() = runTest {
        // Regression for the schemeless-URL bug: the discovered rail hands its url back to
        // SourceRepository.addSource, whose fetchers require a scheme. record() must store
        // a schemeful absolute url even when the scheme differs across re-subscribes.
        discoveredRepo.record(
            title = "AI Blog",
            url = "https://example.com/ai",
            kind = SourceKind.RSS,
            description = null,
            domainHint = null,
            language = null,
        )
        val stored = db.discoveredFeedDao().observeRecent(10).first().single()
        assertTrue("stored url must be schemeful: ${stored.url}", stored.url.contains("://"))
        assertEquals("https://example.com/ai", stored.url)
    }

    @Test
    fun `record prepends a scheme when the input url lacks one`() = runTest {
        discoveredRepo.record(
            title = "Bare Host",
            url = "example.com/feed.xml",
            kind = SourceKind.RSS,
            description = null,
            domainHint = null,
            language = null,
        )
        val stored = db.discoveredFeedDao().observeRecent(10).first().single()
        assertTrue("bare host must get a scheme: ${stored.url}", stored.url.startsWith("https://"))
    }

    @Test
    fun `record dedupes http and https variants of the same url`() = runTest {
        discoveredRepo.record("A", "https://example.com/feed", SourceKind.RSS, null, null, null)
        discoveredRepo.record("A", "http://example.com/feed", SourceKind.RSS, null, null, null)
        val rows = db.discoveredFeedDao().observeRecent(10).first()
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].subscribeCount)
    }
}
