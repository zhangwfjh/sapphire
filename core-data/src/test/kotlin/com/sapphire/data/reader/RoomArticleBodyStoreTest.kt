package com.sapphire.data.reader

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sapphire.data.db.CategoryEntity
import com.sapphire.data.db.FeedItemEntity
import com.sapphire.data.db.OnboardingDao
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.data.db.SourceEntity
import com.sapphire.data.db.TopicEntity
import com.sapphire.domain.model.SourceKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room-backed [RoomArticleBodyStore] coverage: miss, round-trip, REPLACE overwrite, and
 * FK CASCADE when the parent FeedItem is deleted. Uses the in-memory SapphireDatabase so
 * the real schema (FK + ON DELETE CASCADE) is exercised under Robolectric + runTest.
 */
@RunWith(RobolectricTestRunner::class)
class RoomArticleBodyStoreTest {

    private lateinit var db: SapphireDatabase
    private lateinit var onboarding: OnboardingDao
    private lateinit var store: RoomArticleBodyStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SapphireDatabase::class.java,
        ).allowMainThreadQueries().build()
        onboarding = db.onboardingDao()
        store = RoomArticleBodyStore(db.articleBodyDao())
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `get returns null on miss`() = runTest {
        assertNull(store.get("nope"))
    }

    @Test
    fun `put then get round-trips body html`() = runTest {
        seedItem("h1")
        val html = "<p>First para.</p><p>Second para.</p>"
        store.put("h1", html)
        assertEquals(html, store.get("h1"))
    }

    @Test
    fun `put then get round-trips an empty html body`() = runTest {
        seedItem("h1")
        store.put("h1", "")
        assertEquals("", store.get("h1"))
    }

    @Test
    fun `put replaces prior body for same item`() = runTest {
        seedItem("h1")
        store.put("h1", "<p>old</p>")
        store.put("h1", "<p>new</p>")
        assertEquals("<p>new</p>", store.get("h1"))
    }

    @Test
    fun `article body cascade-deletes with the feed item`() = runTest {
        seedItem("h1")
        store.put("h1", "<p>body</p>")
        // Pre-assert the row exists so the cascade case stands on its own.
        assertEquals("<p>body</p>", store.get("h1"))
        db.feedDao().deleteItem("h1")
        assertNull(store.get("h1"))
    }

    /** Minimal topic + category + source + a single feed_item with [hash], to satisfy FKs. */
    private suspend fun seedItem(hash: String) {
        onboarding.commitOnboarding(
            topic = TopicEntity(id = "t1", phrase = "AI", createdAt = 0L),
            categories = listOf(
                CategoryEntity(id = "c1", topicId = "t1", level = 1, parentId = null, name = "Tech", sortOrder = 0),
            ),
            keywords = emptyList(),
            sources = listOf(
                SourceEntity(id = "s1", categoryId = "c1", topicId = "t1", kind = SourceKind.RSS, url = "https://feed", title = "AI Blog"),
            ),
        )
        val now = System.currentTimeMillis()
        db.feedDao().insertItems(
            listOf(FeedItemEntity(hashUuid = hash, sourceId = "s1", categoryId = "c1", title = "Item $hash", fetchedAt = now)),
        )
    }
}
