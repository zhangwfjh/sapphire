package com.sapphire.data.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sapphire.data.db.ArticleBodyEntity
import com.sapphire.data.db.CategoryEntity
import com.sapphire.data.db.FeedItemEntity
import com.sapphire.data.db.LlmCacheEntity
import com.sapphire.data.db.OnboardingDao
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.data.db.SavedItemEntity
import com.sapphire.data.db.SourceEntity
import com.sapphire.data.db.TopicEntity
import com.sapphire.domain.model.SourceKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room-backed [RoomDataClearUseCase] coverage: each granular clear empties its target
 * table(s) and returns the deleted-row count; clearAll() sweeps every table. Uses the
 * in-memory SapphireDatabase so the real schema (FK + ON DELETE CASCADE) is exercised
 * under Robolectric + runTest.
 */
@RunWith(RobolectricTestRunner::class)
class RoomDataClearUseCaseTest {

    private lateinit var db: SapphireDatabase
    private lateinit var onboarding: OnboardingDao
    private lateinit var clear: RoomDataClearUseCase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SapphireDatabase::class.java,
        ).allowMainThreadQueries().build()
        onboarding = db.onboardingDao()
        clear = RoomDataClearUseCase(
            feedDao = db.feedDao(),
            llmCacheDao = db.llmCacheDao(),
            articleBodyDao = db.articleBodyDao(),
            savedItemDao = db.savedItemDao(),
            database = db,
        )
        // Seed the FK chain (topic/category/source) once so seedItem() can be called repeatedly.
        runBlocking {
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
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `clearFeedItems empties feed_item and returns count`() = runTest {
        seedItem("h1")
        seedItem("h2")
        val n = clear.clearFeedItems()
        assertEquals(2, n)
        assertNull(db.feedDao().itemById("h1"))
        assertNull(db.feedDao().itemById("h2"))
    }

    @Test
    fun `clearReaderCache empties llm_cache and article_body`() = runTest {
        seedItem("h1")
        db.llmCacheDao().insert(
            LlmCacheEntity(cacheKey = "k1", itemId = "h1", op = "summarize", payloadJson = "{}", createdAt = 0L),
        )
        db.articleBodyDao().upsert(
            ArticleBodyEntity(itemId = "h1", paragraphsJson = "[]", fetchedAt = 0L),
        )
        val n = clear.clearReaderCache()
        assertEquals(2, n)
        assertNull(db.llmCacheDao().payload("k1"))
        assertNull(db.articleBodyDao().findByItem("h1"))
    }

    @Test
    fun `clearSaved empties saved_item`() = runTest {
        seedItem("h1")
        db.savedItemDao().upsert(SavedItemEntity(itemId = "h1", folder = "f", savedAt = 0L))
        val n = clear.clearSaved()
        assertEquals(1, n)
        assertFalse(db.savedItemDao().isSaved("h1"))
    }

    @Test
    fun `clearAll empties every table`() = runTest {
        seedItem("h1")
        db.savedItemDao().upsert(SavedItemEntity(itemId = "h1", folder = "f", savedAt = 0L))
        db.llmCacheDao().insert(
            LlmCacheEntity(cacheKey = "k1", itemId = "h1", op = "summarize", payloadJson = "{}", createdAt = 0L),
        )
        db.articleBodyDao().upsert(
            ArticleBodyEntity(itemId = "h1", paragraphsJson = "[]", fetchedAt = 0L),
        )

        clear.clearAll()

        assertNull(db.feedDao().itemById("h1"))
        assertFalse(db.savedItemDao().isSaved("h1"))
        assertNull(db.llmCacheDao().payload("k1"))
        assertNull(db.articleBodyDao().findByItem("h1"))
    }

    /** Insert a single feed_item with [hash] (the FK chain is seeded once in [setUp]). */
    private suspend fun seedItem(hash: String) {
        val now = System.currentTimeMillis()
        db.feedDao().insertItems(
            listOf(FeedItemEntity(hashUuid = hash, sourceId = "s1", categoryId = "c1", title = "Item $hash", fetchedAt = now)),
        )
    }
}
