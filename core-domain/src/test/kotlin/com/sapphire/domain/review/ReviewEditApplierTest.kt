package com.sapphire.domain.review

import com.sapphire.domain.llm.TaxonomyFeed
import com.sapphire.domain.llm.TaxonomyL1
import com.sapphire.domain.llm.TaxonomyL2
import com.sapphire.domain.llm.TaxonomyResponse
import com.sapphire.domain.model.SourceKind
import com.sapphire.domain.util.IdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewEditApplierTest {

    private val ids = SequentialIds

    @Test
    fun `delete folder removes only the targeted folder`() {
        val model = sampleModel()
        val targetId = model.folders[0].id
        val edited = ReviewEditApplier.apply(model, ReviewEdit.DeleteFolder(targetId))

        assertEquals(1, edited.folders.size)
        assertEquals("Deep Tech", edited.folders[0].name)
    }

    @Test
    fun `toggle feed flips enabled without touching siblings`() {
        val model = sampleModel()
        val feedId = model.folders[0].feeds[0].id
        val edited = ReviewEditApplier.apply(model, ReviewEdit.ToggleFeed(feedId, enabled = false))

        assertFalse(edited.folders[0].feeds[0].enabled)
        assertTrue(edited.folders[1].feeds[0].enabled)
    }

    @Test
    fun `rename folder trims and updates only the targeted folder`() {
        val model = sampleModel()
        val folderId = model.folders[0].id
        val edited = ReviewEditApplier.apply(model, ReviewEdit.RenameFolder(folderId, "  Better Health  "))

        assertEquals("Better Health", edited.folders[0].name)
        assertEquals("Deep Tech", edited.folders[1].name)
    }

    @Test
    fun `rename folder on unknown id leaves model unchanged`() {
        val model = sampleModel()
        val edited = ReviewEditApplier.apply(model, ReviewEdit.RenameFolder("does-not-exist", "X"))
        assertEquals(model, edited)
    }

    @Test
    fun `add keyword appends as userAdded`() {
        val model = sampleModel()
        val folderId = model.folders[0].id
        val edited = ReviewEditApplier.apply(model, ReviewEdit.AddKeyword(folderId, "melatonin"))
        val keywords = edited.folders[0].keywords

        assertEquals(4, keywords.size)
        assertEquals("melatonin", keywords.last().text)
        assertTrue(keywords.last().userAdded)
    }

    @Test
    fun `blank keyword is ignored`() {
        val model = sampleModel()
        val folderId = model.folders[0].id
        val edited = ReviewEditApplier.apply(model, ReviewEdit.AddKeyword(folderId, "   "))

        assertEquals(3, edited.folders[0].keywords.size)
    }

    @Test
    fun `remove keyword drops by id`() {
        val model = sampleModel()
        val keywordId = model.folders[0].keywords[0].id
        val edited = ReviewEditApplier.apply(model, ReviewEdit.RemoveKeyword(keywordId))

        assertEquals(2, edited.folders[0].keywords.size)
        assertEquals("circadian", edited.folders[0].keywords[0].text)
    }

    @Test
    fun `add manual feed appends as userAdded and enabled`() {
        val model = sampleModel()
        val folderId = model.folders[0].id
        val edited = ReviewEditApplier.apply(
            model,
            ReviewEdit.AddManualFeed(folderId, url = "https://mine.com/rss", title = "Mine", kind = SourceKind.RSS),
        )
        val feeds = edited.folders[0].feeds

        assertEquals(2, feeds.size)
        val added = feeds.last()
        assertEquals("Mine", added.title)
        assertEquals("https://mine.com/rss", added.url)
        assertEquals(SourceKind.RSS, added.kind)
        assertTrue(added.userAdded && added.enabled)
    }

    @Test
    fun `add manual feed with blank title falls back to url`() {
        val model = sampleModel()
        val folderId = model.folders[0].id
        val edited = ReviewEditApplier.apply(
            model,
            ReviewEdit.AddManualFeed(folderId, url = "https://mine.com/rss", title = "  ", kind = SourceKind.RSS),
        )
        assertEquals("https://mine.com/rss", edited.folders[0].feeds.last().title)
    }

    private fun sampleModel(): ReviewModel =
        ReviewBuilder(ids).build(
            "Biohacking",
            TaxonomyResponse(
                categories = listOf(
                    TaxonomyL1(
                        name = "Health & Performance",
                        level2 = listOf(
                            TaxonomyL2(
                                name = "Sleep Optimization",
                                keywords = listOf("sleep", "circadian"),
                                feeds = listOf(
                                    TaxonomyFeed(title = "Sleep Sci", url = "https://example.com/sleep.xml", kind = "rss"),
                                ),
                            ),
                            TaxonomyL2(name = "Nootropics", keywords = listOf("racetams"), feeds = emptyList()),
                        ),
                    ),
                    TaxonomyL1(
                        name = "Deep Tech",
                        level2 = listOf(
                            TaxonomyL2(
                                name = "AI Infra",
                                keywords = listOf("vllm"),
                                feeds = listOf(
                                    TaxonomyFeed(title = "AI Blog", url = "https://example.com/ai.xml", kind = "rss"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

    private object SequentialIds : IdGenerator {
        private var counter = 0
        override fun uuid(): String = "id-${counter++}"
    }
}
