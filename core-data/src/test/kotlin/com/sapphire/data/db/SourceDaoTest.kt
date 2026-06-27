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

/**
 * Sources drawer DAO. Covers the mutations the drawer performs post-onboarding:
 * add/update/move/delete source, add/rename/delete category, and the tree-assembly reads.
 * The `(category_id, url)` unique-index conflict path is the one non-obvious behavior —
 * it must surface as a -1 rowid, not an exception.
 */
@RunWith(RobolectricTestRunner::class)
class SourceDaoTest {

    private lateinit var db: SapphireDatabase
    private lateinit var dao: SourceDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SapphireDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.sourceDao()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `observe flows return seeded tree`() = runTest {
        seed()
        val categories = dao.observeCategories().first()
        val sources = dao.observeSources().first()

        assertEquals(listOf("Tech", "World"), categories.map { it.name })
        // observeSources orders by (category_id, title): c2 (Tech) entries first, then c3 (World).
        assertEquals(listOf("AI Blog", "Papers", "Labs"), sources.map { it.title })
    }

    @Test
    fun `insertSource returns -1 on category_id+url unique conflict`() = runTest {
        seed()
        val dup = SourceEntity(
            id = "sx", categoryId = "c2", topicId = "t1",
            kind = SourceKind.RSS, url = "https://ai.feed", title = "Dup",
        )
        assertEquals(-1L, dao.insertSource(dup))
        assertEquals(3, dao.countSources())
    }

    @Test
    fun `updateSource changes title url kind and enabled`() = runTest {
        seed()
        dao.updateSource("s1", "Renamed", "https://new.url", SourceKind.ATOM, enabled = false)

        val src = dao.observeSources().first().first { it.id == "s1" }
        assertEquals("Renamed", src.title)
        assertEquals("https://new.url", src.url)
        assertEquals(SourceKind.ATOM, src.kind)
        assertFalse(src.enabled)
    }

    @Test
    fun `urlTakenInCategory flags duplicate url excluding the edited row`() = runTest {
        seed()
        // editing s1 to s2's url within the same category -> taken
        assertTrue(dao.urlTakenInCategory("c2", "https://papers.feed", "s1"))
        // s1 keeping its own url -> not taken (excluded)
        assertFalse(dao.urlTakenInCategory("c2", "https://ai.feed", "s1"))
        // different category -> not taken
        assertFalse(dao.urlTakenInCategory("c3", "https://papers.feed", "s1"))
    }

    @Test
    fun `moveSource reassigns category and urlTaken guards conflict into target`() = runTest {
        seed()
        // moving s1 (ai.feed) into c3 (World) which has a different url -> ok
        dao.moveSource("s1", "c3")
        val moved = dao.observeSources().first().first { it.id == "s1" }
        assertEquals("c3", moved.categoryId)

        // c3 now holds ai.feed (moved s1); inserting another ai.feed there is guarded
        assertTrue(dao.urlTakenInCategory("c3", "https://ai.feed", "s2"))
        assertFalse(dao.urlTakenInCategory("c3", "https://papers.feed", "s2"))
    }

    @Test
    fun `deleteSource removes only that source`() = runTest {
        seed()
        dao.deleteSource("s1")
        val sources = dao.observeSources().first()
        assertEquals(2, sources.size)
        assertTrue(sources.all { it.id != "s1" })
    }

    @Test
    fun `addCategory appends a new folder`() = runTest {
        seed()
        dao.insertCategory(
            CategoryEntity(
                id = "c4", topicId = "t1", level = 1, parentId = null,
                name = "Tools", sortOrder = 2,
            ),
        )
        val categories = dao.observeCategories().first()
        assertTrue(categories.any { it.id == "c4" && it.parentId == null })
    }

    @Test
    fun `renameCategory updates name only`() = runTest {
        seed()
        dao.renameCategory("c2", "Engineering")
        assertEquals("Engineering", dao.observeCategories().first().first { it.id == "c2" }.name)
    }

    @Test
    fun `deleteCategory cascades to its sources and feed items`() = runTest {
        seed()
        // attach a feed item to s1 so we can assert CASCADE reaches it
        db.feedDao().insertItems(
            listOf(
                FeedItemEntity(
                    hashUuid = "h1", sourceId = "s1", categoryId = "c2",
                    title = "post", fetchedAt = 0L,
                ),
            ),
        )
        assertEquals(1, db.feedDao().countItems())

        dao.deleteCategory("c2") // cascades to sources under c2, then their items

        assertEquals(1, dao.countSources()) // only s3 (under c3) remains
        assertEquals(0, db.feedDao().countItems())
    }

    @Test
    fun `maxSortOrder returns -1 for a topic with no categories`() = runTest {
        db.onboardingDao().commitOnboarding(
            topic = TopicEntity("t1", "AI", 0L),
            categories = emptyList(),
            keywords = emptyList(),
            sources = emptyList(),
        )
        assertEquals(-1, dao.maxSortOrder("t1"))
    }

    private suspend fun seed() {
        db.onboardingDao().commitOnboarding(
            topic = TopicEntity("t1", "AI", 0L),
            categories = listOf(
                CategoryEntity("c2", "t1", 1, null, "Tech", 0),
                CategoryEntity("c3", "t1", 1, null, "World", 1),
            ),
            keywords = emptyList(),
            sources = listOf(
                SourceEntity("s1", "c2", "t1", SourceKind.RSS, "https://ai.feed", "AI Blog"),
                SourceEntity("s2", "c2", "t1", SourceKind.JSON, "https://papers.feed", "Papers"),
                SourceEntity("s3", "c3", "t1", SourceKind.ATOM, "https://labs.feed", "Labs"),
            ),
        )
    }
}
