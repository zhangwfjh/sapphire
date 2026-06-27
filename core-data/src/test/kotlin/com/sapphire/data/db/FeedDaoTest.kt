package com.sapphire.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sapphire.domain.model.ReadMechanism
import com.sapphire.domain.model.ReadState
import com.sapphire.domain.model.SourceKind
import kotlinx.coroutines.flow.first
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
 * Room tests for the S02 feed layer: PK dedup (PRD §3.2 global hash), read-state
 * transactions, timeline ordering, undo batch, ReadLog append. Runs on Robolectric so the
 * Flow queries resolve synchronously under runTest.
 */
@RunWith(RobolectricTestRunner::class)
class FeedDaoTest {

    private lateinit var db: SapphireDatabase
    private lateinit var dao: FeedDao
    private lateinit var onboarding: OnboardingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SapphireDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.feedDao()
        onboarding = db.onboardingDao()
    }

    @After fun tearDown() { db.close() }

    private suspend fun seedSourceAndItems() {
        // Minimal topic + category + source to satisfy FKs (feed_item.source_id → source.id).
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
        val items = listOf(
            FeedItemEntity("hash-a", "s1", "c2", "Older", publishedAt = now - 2000, fetchedAt = now),
            FeedItemEntity("hash-b", "s1", "c2", "Newer", publishedAt = now, fetchedAt = now),
            FeedItemEntity("hash-c", "s1", "c2", "No pubdate", publishedAt = null, fetchedAt = now - 1000),
        )
        dao.insertItems(items)
    }

    @Test
    fun `timeline is newest-first by publishedAt with nulls falling to fetchedAt`() = runTest {
        seedSourceAndItems()
        val timeline = dao.observeTimeline().first()
        assertEquals(listOf("Newer", "No pubdate", "Older"), timeline.map { it.title })
    }

    @Test
    fun `duplicate hash_uuid insert is ignored without throwing`() = runTest {
        seedSourceAndItems()
        // Re-insert the same hash with different content — IGNORE drops it, original survives.
        val dup = FeedItemEntity("hash-a", "s1", "c2", "SHOULD NOT APPEAR", publishedAt = 0L, fetchedAt = 0L)
        val rowIds = dao.insertItems(listOf(dup))
        assertEquals(-1L, rowIds[0]) // -1 = IGNORE'd conflict
        val timeline = dao.observeTimeline().first()
        assertEquals("Older", timeline.first { it.hashUuid == "hash-a" }.title)
    }

    @Test
    fun `markRead sets state and appends ReadLog once`() = runTest {
        seedSourceAndItems()
        dao.markRead("hash-a", ReadMechanism.DWELL, 100L)
        assertEquals(ReadState.READ, dao.readStateOf("hash-a"))

        // Idempotent: re-marking READ does not duplicate the ReadLog row.
        dao.markRead("hash-a", ReadMechanism.SCROLLED_PAST, 200L)
        val count = db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM read_log WHERE item_id = 'hash-a'")
            .use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, count)
    }

    @Test
    fun `markUnread reverts and logs MANUAL`() = runTest {
        seedSourceAndItems()
        dao.markRead("hash-a", ReadMechanism.DWELL, 100L)
        dao.markUnread("hash-a", 200L)
        assertEquals(ReadState.UNREAD, dao.readStateOf("hash-a"))
    }

    @Test
    fun `undoBatch reverts only the items that were READ`() = runTest {
        seedSourceAndItems()
        dao.markRead("hash-a", ReadMechanism.SCROLLED_PAST, 100L)
        dao.markRead("hash-b", ReadMechanism.SCROLLED_PAST, 100L)
        // hash-c stays UNREAD

        dao.undoBatch(listOf("hash-a", "hash-c"), 200L)

        assertEquals(ReadState.UNREAD, dao.readStateOf("hash-a"))
        assertEquals(ReadState.READ, dao.readStateOf("hash-b")) // unchanged
        assertEquals(ReadState.UNREAD, dao.readStateOf("hash-c"))
    }

    @Test
    fun `unread count flow reflects state transitions`() = runTest {
        seedSourceAndItems()
        assertEquals(3, dao.observeUnreadCountRaw().first())
        dao.markRead("hash-a", ReadMechanism.DWELL, 0L)
        dao.markRead("hash-b", ReadMechanism.DWELL, 0L)
        assertEquals(1, dao.observeUnreadCountRaw().first())
    }

    @Test
    fun `category filter returns only that category`() = runTest {
        seedSourceAndItems()
        val catItems = dao.observeCategory("c2").first()
        assertEquals(3, catItems.size)
        assertTrue(catItems.all { it.categoryId == "c2" })
    }

    @Test
    fun `observeCategories returns union across multiple category ids`() = runTest {
        seedSourceAndItems()
        // Add a second source + items under a different category (c1).
        onboarding.commitOnboarding(
            topic = TopicEntity("t2", "AI2", 0L),
            categories = emptyList(),
            keywords = emptyList(),
            sources = listOf(
                SourceEntity("s2", "c1", "t1", SourceKind.RSS, "https://c1feed", "C1 Blog"),
            ),
        )
        dao.insertItems(
            listOf(
                FeedItemEntity("hash-d", "s2", "c1", "C1 Post", publishedAt = 0L, fetchedAt = 0L),
            ),
        )

        // Multi-id query spanning c1 + c2.
        val union = dao.observeCategories(listOf("c1", "c2")).first()
        assertEquals(4, union.size)
        assertTrue(union.map { it.categoryId }.toSet() == setOf("c1", "c2"))

        // Single-element list behaves like observeCategory.
        val onlyC1 = dao.observeCategories(listOf("c1")).first()
        assertEquals(1, onlyC1.size)
        assertEquals("c1", onlyC1.single().categoryId)
    }

    @Test
    fun `observeBySource returns only that source items newest first`() = runTest {
        seedSourceAndItems() // s1 has 3 items under c2
        val s1Items = dao.observeBySource("s1").first()
        assertEquals(3, s1Items.size)
        assertTrue(s1Items.all { it.sourceId == "s1" })
        // newest first: "Newer" (publishedAt=now) before "Older" (now-2000)
        assertEquals("Newer", s1Items.first().title)
        // An unknown source yields nothing.
        assertTrue(dao.observeBySource("nope").first().isEmpty())
    }

    @Test
    fun `existingIds filters to rows already present`() = runTest {
        seedSourceAndItems()
        assertEquals(
            listOf("hash-a"),
            dao.existingIds(listOf("hash-a", "hash-missing")),
        )
    }

    @Test
    fun `readStateOf returns null for unknown item`() = runTest {
        assertNull(dao.readStateOf("does-not-exist"))
    }
    @Test
    fun `markReadBySource flips only that source items and logs each`() = runTest {
        seedSourceAndItems() // s1 / c2, 3 UNREAD items
        // Add a second source under the same category to verify scoping.
        db.sourceDao().insertSource(
            SourceEntity("s2", "c2", "t1", SourceKind.RSS, "https://other", "Other"),
        )
        val now = System.currentTimeMillis()
        dao.insertItems(
            listOf(
                FeedItemEntity("hash-d", "s2", "c2", "Other unread", publishedAt = now, fetchedAt = now),
            ),
        )

        dao.markReadBySource("s1", now)

        // s1's three items are READ; s2's item stays UNREAD.
        assertEquals(ReadState.READ, dao.readStateOf("hash-a"))
        assertEquals(ReadState.READ, dao.readStateOf("hash-b"))
        assertEquals(ReadState.READ, dao.readStateOf("hash-c"))
        assertEquals(ReadState.UNREAD, dao.readStateOf("hash-d"))

        // Idempotent: re-running leaves state unchanged.
        dao.markReadBySource("s1", now)
        assertEquals(ReadState.READ, dao.readStateOf("hash-a"))
    }

    @Test
    fun `markReadByCategory flips every item in that category`() = runTest {
        seedSourceAndItems() // s1 / c2 (3 UNREAD)
        db.sourceDao().insertCategory(
            CategoryEntity("c3", "t1", 3, null, "Other Cat", 0),
        )
        db.sourceDao().insertSource(
            SourceEntity("s3", "c3", "t1", SourceKind.RSS, "https://c3", "C3 Src"),
        )
        val now = System.currentTimeMillis()
        dao.insertItems(
            listOf(
                FeedItemEntity("hash-e", "s3", "c3", "In other category", publishedAt = now, fetchedAt = now),
            ),
        )

        dao.markReadByCategory("c2", now)

        // c2 items flipped; c3 untouched.
        assertEquals(ReadState.READ, dao.readStateOf("hash-a"))
        assertEquals(ReadState.READ, dao.readStateOf("hash-b"))
        assertEquals(ReadState.READ, dao.readStateOf("hash-c"))
        assertEquals(ReadState.UNREAD, dao.readStateOf("hash-e"))
    }

    @Test
    fun `unreadIdsBySource returns only unread ids for that source`() = runTest {
        seedSourceAndItems()
        dao.setReadState("hash-a", ReadState.READ)
        val ids = dao.unreadIdsBySource("s1").toSet()
        assertEquals(setOf("hash-b", "hash-c"), ids)
        assertTrue(dao.unreadIdsBySource("nope").isEmpty())
    }
}
