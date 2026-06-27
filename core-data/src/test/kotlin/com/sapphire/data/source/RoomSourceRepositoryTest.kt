package com.sapphire.data.source

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sapphire.data.db.FeedItemEntity
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.data.db.CategoryEntity
import com.sapphire.data.db.SourceEntity
import com.sapphire.data.db.TopicEntity
import com.sapphire.domain.model.SourceKind
import com.sapphire.domain.explore.DiscoveredFeedRepository
import com.sapphire.domain.source.SourceCounts
import com.sapphire.domain.source.SourceRepository.Outcome
import com.sapphire.domain.util.IdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room integration for the single-level sources tree. Categories are flat folders with
 * sources attached directly; the tree surfaces each folder with its sources.
 */
@RunWith(RobolectricTestRunner::class)
class RoomSourceRepositoryTest {

    private lateinit var db: SapphireDatabase
    private lateinit var repo: RoomSourceRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SapphireDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = RoomSourceRepository(db.sourceDao(), db.feedDao(), SequentialIds, NoopDiscoveredRepo())
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `sources attached to a folder appear on the folder node`() = runTest {
        seedFlat() // one folder, two sources
        val tree = repo.observeTree().first()

        assertEquals(1, tree.size)
        val folder = tree.single()
        assertEquals("Technology", folder.category.name)
        assertEquals(listOf("Hacker News", "The Verge"), folder.sources.map { it.source.title })
    }

    @Test
    fun `addSource attaches to the folder`() = runTest {
        seedFlat()
        repo.addSource("c1", "https://new.feed", "New", SourceKind.ATOM)
        val folder = repo.observeTree().first().single()
        assertTrue(folder.sources.any { it.source.title == "New" && it.source.url == "https://new.feed" })
    }

    @Test
    fun `moveSource between folders moves the source`() = runTest {
        seedTwoFolders()
        // move Hacker News (s1 under c1) into c2 (World)
        val outcome = repo.moveSource("s1", "c2")
        assertEquals(Outcome.Ok, outcome)
        val tree = repo.observeTree().first()
        val tech = tree.first { it.category.id == "c1" }
        val world = tree.first { it.category.id == "c2" }
        assertTrue(tech.sources.none { it.source.id == "s1" })
        assertTrue(world.sources.any { it.source.id == "s1" })
    }

    @Test
    fun `moveSource batch via repo moveSources skips collisions`() = runTest {
        seedTwoFolders()
        // s1 url is https://hnrss.org/frontpage; add a colliding source in c2.
        repo.addSource("c2", "https://hnrss.org/frontpage", "HN Dup", SourceKind.RSS)
        // Now batch-move s1 into c2 → should be skipped (conflict), outcome surfaces it.
        val outcome = repo.moveSource("s1", "c2")
        assertTrue(outcome is Outcome.Conflict)
        // s1 stays in c1 because of the collision.
        val tree = repo.observeTree().first()
        val tech = tree.first { it.category.id == "c1" }
        assertTrue(tech.sources.any { it.source.id == "s1" })
    }

    @Test
    fun `addSource colliding on category_id+url returns Conflict`() = runTest {
        seedFlat()
        val outcome = repo.addSource("c1", "https://hnrss.org/frontpage", "Dup", SourceKind.RSS)
        assertTrue(outcome is Outcome.Conflict)
    }

    @Test
    fun `deleteSources batch removes the given sources`() = runTest {
        seedFlat()
        repo.deleteSource("s1")
        repo.deleteSource("s2")
        val folder = repo.observeTree().first().single()
        assertTrue(folder.sources.isEmpty())
    }

    @Test
    fun `addCategory creates a new folder`() = runTest {
        seedFlat() // one folder "Technology" under t1
        repo.addCategory(topicId = "t1", name = "Sports")

        val tree = repo.observeTree().first()
        assertEquals(2, tree.size)
        val names = tree.map { it.category.name }
        assertTrue("Sports" in names)
        assertTrue("Technology" in names)
        val sports = tree.first { it.category.name == "Sports" }
        assertEquals(1, sports.category.level)
        assertEquals(null, sports.category.parentId)
        assertTrue(sports.sources.isEmpty())
    }

    @Test
    fun `currentTopicId resolves even when no categories exist`() = runTest {
        // Topic present, every folder deleted — the state that broke folder creation.
        seedTopicOnly()

        assertEquals("t1", repo.currentTopicId())
        assertTrue(repo.observeHasTopic().first())
        // Tree is empty (no categories), yet a topic exists.
        assertTrue(repo.observeTree().first().isEmpty())
    }

    @Test
    fun `addCategory then addSource works with no prior folders`() = runTest {
        // Reproduces the ExploreViewModel.subscribeIntoNewFolder sequence against a tree
        // that has zero categories. Before the fix, topicId came from the first category
        // (null here) and the folder was never created.
        seedTopicOnly()

        val topicId = repo.currentTopicId() ?: error("topic must exist")
        val categoryId = repo.addCategory(topicId, "First Folder")
        val outcome = repo.addSource(categoryId, "https://example.com/feed.xml", "Example", SourceKind.RSS)

        assertEquals(Outcome.Ok, outcome)
        val tree = repo.observeTree().first()
        assertEquals(1, tree.size)
        assertEquals("First Folder", tree.single().category.name)
        assertEquals("Example", tree.single().sources.single().source.title)
    }

    @Test
    fun `observeHasTopic is false before any topic exists`() = runTest {
        assertFalse(repo.observeHasTopic().first())
        assertNull(repo.currentTopicId())
    }

    @Test
    fun `per-source counts reflect unread and total`() = runTest {
        db.onboardingDao().commitOnboarding(
            topic = TopicEntity("t1", "Tech", 0L),
            categories = listOf(
                CategoryEntity("c1", "t1", 1, null, "Tech", 0),
                CategoryEntity("c2", "t1", 1, null, "World", 1),
            ),
            keywords = emptyList(),
            sources = listOf(
                SourceEntity("s1", "c1", "t1", SourceKind.RSS, "https://a", "A"),
                SourceEntity("s2", "c2", "t1", SourceKind.RSS, "https://b", "B"),
            ),
        )
        val feed = db.feedDao()
        feed.insertItems(
            listOf(
                FeedItemEntity("h1", "s1", "c1", "p1", fetchedAt = 0L),
                FeedItemEntity("h2", "s1", "c1", "p2", fetchedAt = 0L),
                FeedItemEntity("h3", "s2", "c2", "p3", fetchedAt = 0L),
            ),
        )
        feed.markRead("h1", com.sapphire.domain.model.ReadMechanism.MANUAL, 0L)
        feed.markRead("h2", com.sapphire.domain.model.ReadMechanism.MANUAL, 0L)

        val tree = repo.observeTree().first()
        val tech = tree.first { it.category.id == "c1" }
        val world = tree.first { it.category.id == "c2" }
        val srcA = tech.sources.first { it.source.id == "s1" }
        val srcB = world.sources.first { it.source.id == "s2" }
        assertEquals(SourceCounts(0, 2), srcA.counts)
        assertEquals(SourceCounts(1, 1), srcB.counts)
    }

    @Test
    fun `per-source counts are zero for a folder with no items`() = runTest {
        seedFlat() // sources exist but no feed items inserted
        val folder = repo.observeTree().first().single()
        val src = folder.sources.first()
        assertEquals(SourceCounts(0, 0), src.counts)
    }

    private suspend fun seedFlat() {
        db.onboardingDao().commitOnboarding(
            topic = TopicEntity("t1", "Tech", 0L),
            categories = listOf(CategoryEntity("c1", "t1", 1, null, "Technology", 0)),
            keywords = emptyList(),
            sources = listOf(
                SourceEntity("s1", "c1", "t1", SourceKind.RSS, "https://hnrss.org/frontpage", "Hacker News"),
                SourceEntity("s2", "c1", "t1", SourceKind.RSS, "https://www.theverge.com/rss/index.xml", "The Verge"),
            ),
        )
    }

    private suspend fun seedTwoFolders() {
        db.onboardingDao().commitOnboarding(
            topic = TopicEntity("t1", "Tech", 0L),
            categories = listOf(
                CategoryEntity("c1", "t1", 1, null, "Technology", 0),
                CategoryEntity("c2", "t1", 1, null, "World", 1),
            ),
            keywords = emptyList(),
            sources = listOf(
                SourceEntity("s1", "c1", "t1", SourceKind.RSS, "https://hnrss.org/frontpage", "Hacker News"),
                SourceEntity("s2", "c2", "t1", SourceKind.RSS, "https://feeds.bbci.co.uk/news/rss.xml", "BBC"),
            ),
        )
    }

    /** A topic with no categories — simulates onboarding that left a topic, or all folders deleted. */
    private suspend fun seedTopicOnly() {
        db.onboardingDao().commitOnboarding(
            topic = TopicEntity("t1", "Tech", 0L),
            categories = emptyList(),
            keywords = emptyList(),
            sources = emptyList(),
        )
    }

    private object SequentialIds : IdGenerator {
        private var counter = 100
        override fun uuid(): String = "uuid-${counter++}"
    }
    @Test
    fun `addSource records the feed into the discovered pool`() = runTest {
        seedFlat()
        val recorder = RecordingDiscoveredRepo()
        val repoWithDiscovery = RoomSourceRepository(db.sourceDao(), db.feedDao(), SequentialIds, recorder)

        val outcome = repoWithDiscovery.addSource("c1", "https://new.example.com/feed", "New Feed", SourceKind.RSS)

        assertEquals(Outcome.Ok, outcome)
        assertEquals(1, recorder.recorded.size)
        assertEquals("https://new.example.com/feed", recorder.recorded[0].url)
        assertEquals("New Feed", recorder.recorded[0].title)
    }

    @Test
    fun `addSource conflict does not record into discovered pool`() = runTest {
        seedFlat() // c1 already has https://hnrss.org/frontpage
        val recorder = RecordingDiscoveredRepo()
        val repoWithDiscovery = RoomSourceRepository(db.sourceDao(), db.feedDao(), SequentialIds, recorder)

        val outcome = repoWithDiscovery.addSource("c1", "https://hnrss.org/frontpage", "Dupe", SourceKind.RSS)

        assertEquals(Outcome.Conflict("c1"), outcome)
        assertEquals(0, recorder.recorded.size)
    }

    /** Captures record() calls so we can assert the discovered-pool trigger fired. */
    private class RecordingDiscoveredRepo : DiscoveredFeedRepository {
        data class Recorded(val title: String, val url: String, val kind: SourceKind)
        val recorded = mutableListOf<Recorded>()

        override suspend fun record(
            title: String,
            url: String,
            kind: SourceKind,
            description: String?,
            domainHint: String?,
            language: String?,
        ) {
            recorded.add(Recorded(title, url, kind))
        }
    }

    private class NoopDiscoveredRepo : DiscoveredFeedRepository {
        override suspend fun record(
            title: String, url: String, kind: SourceKind,
            description: String?, domainHint: String?, language: String?,
        ) = Unit
    }
}
