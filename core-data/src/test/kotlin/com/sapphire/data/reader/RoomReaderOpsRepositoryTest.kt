package com.sapphire.data.reader

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sapphire.data.db.CategoryEntity
import com.sapphire.data.db.FeedItemEntity
import com.sapphire.data.db.LlmCacheEntity
import com.sapphire.data.db.OnboardingDao
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.data.db.SourceEntity
import com.sapphire.data.db.TopicEntity
import com.sapphire.domain.model.SourceKind
import com.sapphire.domain.util.LlmCacheKey
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S03 data-layer integration: the `llm_cache` table + reader-op ports over Room.
 *
 * Covers:
 * - [LlmCacheDao] insert/get by the domain-derived [LlmCacheKey].
 * - Classification persistence onto `feed_item.classification` (PRD §3.5 macro source).
 * - [RoomReaderOpCache] round-trip (cache-first contract backing).
 * - [RoomReaderItemStore] item lookup + classification write.
 *
 * Runs on Robolectric so Room Flows/transactions resolve under runTest.
 */
@RunWith(RobolectricTestRunner::class)
class RoomReaderOpsRepositoryTest {

    private lateinit var db: SapphireDatabase
    private lateinit var onboarding: OnboardingDao
    private lateinit var cache: RoomReaderOpCache
    private lateinit var items: RoomReaderItemStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SapphireDatabase::class.java,
        ).allowMainThreadQueries().build()
        onboarding = db.onboardingDao()
        cache = RoomReaderOpCache(db.llmCacheDao())
        items = RoomReaderItemStore(db.feedDao())
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `cache put then get round-trips the payload`() = runTest {
        seedItem("hash-a")
        val key = LlmCacheKey.compute("hash-a", "summary", "gpt-4o")
        cache.put("hash-a", key, "summary", """{"bullets":["a","b","c"]}""")
        assertEquals("""{"bullets":["a","b","c"]}""", cache.get(key))
    }

    @Test
    fun `cache miss returns null`() = runTest {
        seedItem("hash-a")
        assertNull(cache.get(LlmCacheKey.compute("hash-a", "summary", "gpt-4o")))
    }

    @Test
    fun `cache replace overwrites stale payload for same key`() = runTest {
        seedItem("hash-a")
        val key = LlmCacheKey.compute("hash-a", "summary", "gpt-4o")
        cache.put("hash-a", key, "summary", "old")
        cache.put("hash-a", key, "summary", "new")
        assertEquals("new", cache.get(key))
    }

    @Test
    fun `setClassification persists onto the feed item row`() = runTest {
        seedItem("hash-a")
        items.setClassification("hash-a", "Tech Blog")
        val item = items.item("hash-a")
        assertNotNull(item)
        assertEquals("Tech Blog", item!!.classification)
    }

    @Test
    fun `item lookup returns bodyRaw for the reader body parse`() = runTest {
        seedItem("hash-a", bodyRaw = "<p>Para one</p><p>Para two</p>")
        val item = items.item("hash-a")
        assertNotNull(item)
        assertEquals("<p>Para one</p><p>Para two</p>", item!!.bodyRaw)
    }

    @Test
    fun `item lookup returns null for unknown id`() = runTest {
        assertNull(items.item("does-not-exist"))
    }

    @Test
    fun `distinct ops for the same item produce distinct cache rows`() = runTest {
        seedItem("hash-a")
        val classifyKey = LlmCacheKey.compute("hash-a", "classification", "m1")
        val summaryKey = LlmCacheKey.compute("hash-a", "summary", "m2")
        cache.put("hash-a", classifyKey, "classification", """{"classification":"Tech Blog"}""")
        cache.put("hash-a", summaryKey, "summary", """{"bullets":[]}""")
        assertEquals("""{"classification":"Tech Blog"}""", cache.get(classifyKey))
        assertEquals("""{"bullets":[]}""", cache.get(summaryKey))
    }

    // ---------- helpers ----------

    private suspend fun seedItem(hash: String, bodyRaw: String? = null) {
        onboarding.commitOnboarding(
            topic = TopicEntity("t1", "AI", 0L),
            categories = listOf(CategoryEntity("c1", "t1", 1, null, "Tech", 0)),
            keywords = emptyList(),
            sources = listOf(SourceEntity("s1", "c1", "t1", SourceKind.RSS, "https://feed", "Blog")),
        )
        db.feedDao().insertItems(
            listOf(
                FeedItemEntity(
                    hashUuid = hash,
                    sourceId = "s1",
                    categoryId = "c1",
                    title = "Title",
                    summary = "Summary",
                    bodyRaw = bodyRaw,
                    fetchedAt = 0L,
                ),
            ),
        )
    }
}
