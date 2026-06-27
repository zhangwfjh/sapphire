package com.sapphire.data.save

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sapphire.data.db.CategoryEntity
import com.sapphire.data.db.FeedItemEntity
import com.sapphire.data.db.OnboardingDao
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.data.db.SourceEntity
import com.sapphire.data.db.TopicEntity
import com.sapphire.domain.model.ReadState
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

/**
 * S07 Room integration for the Save Later repository: the `saved_item` table, its
 * transactional coupling to `feed_item.saved_later`, CASCADE behavior, and the
 * [RoomRetentionPurge] query (read + unsaved + older-than-cutoff).
 *
 * Runs on Robolectric so Room Flows/transactions resolve under runTest.
 */
@RunWith(RobolectricTestRunner::class)
class RoomSavedItemRepositoryTest {

    private lateinit var db: SapphireDatabase
    private lateinit var onboarding: OnboardingDao
    private lateinit var repo: RoomSavedItemRepository
    private lateinit var purge: RoomRetentionPurge

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SapphireDatabase::class.java,
        ).allowMainThreadQueries().build()
        onboarding = db.onboardingDao()
        repo = RoomSavedItemRepository(db, db.savedItemDao(), db.feedDao())
        purge = RoomRetentionPurge(db.feedDao())
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `save promotes item, flips saved_later flag, and appears in observeAll`() = runTest {
        seedSourceAndItems()
        repo.save("hash-a", folder = "Inbox")

        assertTrue(repo.isSaved("hash-a"))
        assertEquals(true, db.feedDao().itemById("hash-a")?.savedLater)
        val saved = repo.observeAll().first()
        assertEquals(1, saved.size)
        assertEquals("hash-a", saved[0].itemId)
        assertEquals("Inbox", saved[0].folder)
    }

    @Test
    fun `save with labels round-trips the label map`() = runTest {
        seedSourceAndItems()
        repo.save("hash-a", folder = "Research", labels = mapOf("topic" to "AI", "priority" to "high"))

        val saved = repo.observeAll().first().single()
        assertEquals(mapOf("topic" to "AI", "priority" to "high"), saved.labels)
    }

    @Test
    fun `unsave removes the row and clears saved_later`() = runTest {
        seedSourceAndItems()
        repo.save("hash-a", folder = "Inbox")
        repo.unsave("hash-a")

        assertFalse(repo.isSaved("hash-a"))
        assertEquals(false, db.feedDao().itemById("hash-a")?.savedLater)
        assertTrue(repo.observeAll().first().isEmpty())
    }

    @Test
    fun `re-save with new folder replaces the row (REPLACE on PK)`() = runTest {
        seedSourceAndItems()
        repo.save("hash-a", folder = "Inbox")
        repo.save("hash-a", folder = "Deep Research")

        val saved = repo.observeAll().first()
        assertEquals(1, saved.size)
        assertEquals("Deep Research", saved[0].folder)
    }

    @Test
    fun `observeCount reflects saved total`() = runTest {
        seedSourceAndItems()
        assertEquals(0, repo.observeCount().first())
        repo.save("hash-a", folder = "Inbox")
        repo.save("hash-b", folder = "Inbox")
        assertEquals(2, repo.observeCount().first())
    }

    @Test
    fun `cascade delete of feed_item removes the saved_item row`() = runTest {
        seedSourceAndItems()
        repo.save("hash-a", folder = "Inbox")
        // Deleting the source cascades to feed_item then to saved_item.
        db.openHelper.writableDatabase.execSQL("DELETE FROM source WHERE id = ?", arrayOf<Any?>("s1"))

        assertTrue(repo.observeAll().first().isEmpty())
    }

    // ---------- retention purge ----------

    @Test
    fun `purge deletes only read, unsaved items older than cutoff`() = runTest {
        seedSourceAndItems()
        val now = System.currentTimeMillis()
        // hash-a: old + read + unsaved  -> purged
        db.feedDao().setReadState("hash-a", ReadState.READ)
        // hash-b: new + read + unsaved  -> kept (too new)
        db.feedDao().setReadState("hash-b", ReadState.READ)
        // hash-c: old + unread          -> kept (unread)
        // (hash-c fetchedAt = now - 1000, set below)

        val cutoff = now - 500
        val purged = purge.purgeOlderThan(cutoff)

        assertEquals(1, purged)
        // hash-a gone, hash-b and hash-c remain
        assertEquals(null, db.feedDao().itemById("hash-a"))
        assertEquals("Newer", db.feedDao().itemById("hash-b")?.title)
        assertEquals("No pubdate", db.feedDao().itemById("hash-c")?.title)
    }

    @Test
    fun `purge skips saved items even when read and old`() = runTest {
        seedSourceAndItems()
        val now = System.currentTimeMillis()
        db.feedDao().setReadState("hash-a", ReadState.READ)
        repo.save("hash-a", folder = "Inbox")

        val purged = purge.purgeOlderThan(now + 9999)

        assertEquals(0, purged)
        assertTrue(repo.isSaved("hash-a"))
    }

    @Test
    fun `purge cascades to llm_cache rows for the purged item`() = runTest {
        seedSourceAndItems()
        val now = System.currentTimeMillis()
        db.feedDao().setReadState("hash-a", ReadState.READ)
        // Seed an llm_cache row attached to hash-a.
        db.llmCacheDao().insert(
            com.sapphire.data.db.LlmCacheEntity(
                cacheKey = "k1",
                itemId = "hash-a",
                op = "summary",
                payloadJson = "{}",
                createdAt = now,
            ),
        )

        purge.purgeOlderThan(now + 9999)

        assertEquals(null, db.llmCacheDao().payload("k1"))
    }

    @Test
    fun `purge on empty db is a no-op returning zero`() = runTest {
        val purged = purge.purgeOlderThan(System.currentTimeMillis() + 9999)
        assertEquals(0, purged)
    }

    // ---------- helpers ----------

    private suspend fun seedSourceAndItems() {
        onboarding.commitOnboarding(
            topic = TopicEntity("t1", "AI", 0L),
            categories = listOf(
                CategoryEntity("c1", "t1", 1, null, "Tech", 0),
                CategoryEntity("c2", "t1", 2, "c1", "AI Infra", 0),
            ),
            keywords = emptyList(),
            sources = listOf(
                SourceEntity("s1", "c2", "t1", SourceKind.RSS, "https://feed", "AI Blog"),
            ),
        )
        val now = System.currentTimeMillis()
        dao = db.feedDao()
        dao.insertItems(
            listOf(
                FeedItemEntity("hash-a", "s1", "c2", "Older", publishedAt = now - 2000, fetchedAt = now - 2000),
                FeedItemEntity("hash-b", "s1", "c2", "Newer", publishedAt = now, fetchedAt = now),
                FeedItemEntity("hash-c", "s1", "c2", "No pubdate", publishedAt = null, fetchedAt = now - 1000),
            ),
        )
    }

    private lateinit var dao: com.sapphire.data.db.FeedDao
}
