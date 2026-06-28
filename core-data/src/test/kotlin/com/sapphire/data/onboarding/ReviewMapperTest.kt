package com.sapphire.data.onboarding

import com.sapphire.domain.model.HealthState
import com.sapphire.domain.model.SourceKind
import com.sapphire.domain.review.ReviewFeed
import com.sapphire.domain.review.ReviewFolder
import com.sapphire.domain.review.ReviewKeyword
import com.sapphire.domain.review.ReviewModel
import com.sapphire.domain.util.IdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewMapperTest {

    @Test
    fun `maps topic categories keywords and sources with correct linkage`() {
        val mapper = ReviewMapper(SequentialIds)
        val review = ReviewModel(
            topicPhrase = "AI",
            folders = listOf(
                ReviewFolder(
                    id = "folder-review",
                    name = "Tech",
                    keywords = mutableListOf(ReviewKeyword("kw-1", "llm", userAdded = false)),
                    feeds = mutableListOf(
                        ReviewFeed("fd-1", "AI Blog", "https://ai.feed", SourceKind.RSS, userAdded = false),
                    ),
                ),
            ),
        )

        val batch = mapper.map(review, now = 1_000L)

        assertEquals("AI", batch.topic.phrase)
        assertEquals(1_000L, batch.topic.createdAt)

        // One folder → one category (single-level).
        assertEquals(1, batch.categories.size)
        val category = batch.categories[0]
        assertEquals(1, category.level)
        assertNull(category.parentId)
        assertEquals("Tech", category.name)
        assertEquals(batch.topic.id, category.topicId)

        assertEquals(1, batch.keywords.size)
        assertEquals("llm", batch.keywords[0].text)
        assertEquals(category.id, batch.keywords[0].categoryId)
        assertFalse(batch.keywords[0].userAdded)

        assertEquals(1, batch.sources.size)
        val src = batch.sources[0]
        assertEquals("AI Blog", src.title)
        assertEquals("https://ai.feed", src.url)
        assertEquals(SourceKind.RSS, src.kind)
        assertEquals(HealthState.OK, src.healthState)
        assertEquals(category.id, src.categoryId)
        assertEquals(batch.topic.id, src.topicId)
    }

    @Test
    fun `multiple folders each carry their own keywords and sources`() {
        val mapper = ReviewMapper(SequentialIds)
        val review = ReviewModel(
            topicPhrase = "AI",
            folders = listOf(
                ReviewFolder(
                    id = "f1",
                    name = "Tech",
                    keywords = mutableListOf(ReviewKeyword("kw-1", "llm", userAdded = false)),
                    feeds = mutableListOf(
                        ReviewFeed("fd-1", "AI Blog", "https://ai.feed", SourceKind.RSS, userAdded = false),
                    ),
                ),
                ReviewFolder(
                    id = "f2",
                    name = "World",
                    keywords = mutableListOf(ReviewKeyword("kw-2", "geopolitics", userAdded = false)),
                    feeds = mutableListOf(
                        ReviewFeed("fd-2", "BBC", "https://bbc.feed", SourceKind.RSS, userAdded = false),
                    ),
                ),
            ),
        )

        val batch = mapper.map(review, now = 1_000L)

        assertEquals(2, batch.categories.size)
        assertEquals(2, batch.keywords.size)
        assertEquals(2, batch.sources.size)
        assertEquals(listOf("Tech", "World"), batch.categories.map { it.name })
        assertEquals(listOf(0, 1), batch.categories.map { it.sortOrder })
    }

    private object SequentialIds : IdGenerator {
        private var counter = 0
        override fun uuid(): String = "id-${counter++}"
    }
}
