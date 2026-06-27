package com.sapphire.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sapphire.domain.model.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiscoveredFeedDaoTest {

    private lateinit var db: SapphireDatabase
    private lateinit var dao: DiscoveredFeedDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SapphireDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.discoveredFeedDao()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `upsert then observeRecent returns the feed`() = runTest {
        dao.upsert(feed("id1", "https://example.com/a", "Feed A"))
        val recent = dao.observeRecent(10).first()
        assertEquals(1, recent.size)
        assertEquals("Feed A", recent[0].title)
    }

    @Test
    fun `observeRecent orders by subscribe_count desc then discovered_at desc`() = runTest {
        dao.upsert(feed("id1", "https://example.com/a", "A", subscribeCount = 1, discoveredAt = 100))
        dao.upsert(feed("id2", "https://example.com/b", "B", subscribeCount = 3, discoveredAt = 50))
        dao.upsert(feed("id3", "https://example.com/c", "C", subscribeCount = 3, discoveredAt = 200))
        val recent = dao.observeRecent(10).first()
        assertEquals(listOf("C", "B", "A"), recent.map { it.title })
    }

    @Test
    fun `incrementSubscribeCount bumps without duplicating`() = runTest {
        dao.upsert(feed("id1", "https://example.com/a", "A", subscribeCount = 1))
        dao.incrementSubscribeCount("id1")
        val row = dao.observeRecent(10).first().single()
        assertEquals(2, row.subscribeCount)
    }

    @Test
    fun `upsert with same id replaces`() = runTest {
        dao.upsert(feed("id1", "https://example.com/a", "A"))
        dao.upsert(feed("id1", "https://example.com/a", "A (renamed)"))
        val recent = dao.observeRecent(10).first()
        assertEquals(1, recent.size)
        assertEquals("A (renamed)", recent[0].title)
    }

    @Test
    fun `exists returns false for unknown id`() = runTest {
        assertFalse(dao.exists("missing"))
        dao.upsert(feed("id1", "https://example.com/a", "A"))
        assertTrue(dao.exists("id1"))
    }

    @Test
    fun `observeRecent respects limit`() = runTest {
        dao.upsert(feed("id1", "https://example.com/a", "A"))
        dao.upsert(feed("id2", "https://example.com/b", "B"))
        assertEquals(1, dao.observeRecent(1).first().size)
    }

    private fun feed(
        id: String,
        url: String,
        title: String,
        subscribeCount: Int = 1,
        discoveredAt: Long = 0L,
    ) = DiscoveredFeedEntity(
        id = id,
        title = title,
        url = url,
        kind = SourceKind.RSS,
        description = null,
        domainHint = null,
        language = null,
        discoveredAt = discoveredAt,
        subscribeCount = subscribeCount,
    )
}
