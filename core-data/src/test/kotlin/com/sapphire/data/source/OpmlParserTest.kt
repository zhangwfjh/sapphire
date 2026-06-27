package com.sapphire.data.source

import com.sapphire.domain.model.SourceKind
import com.sapphire.domain.source.SourceFolderNode
import com.sapphire.domain.source.SourceNode
import com.sapphire.domain.model.Category
import com.sapphire.domain.model.Source
import com.sapphire.domain.source.SourceCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpmlParserTest {

    @Test
    fun `parses simple two-level OPML with sources`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <opml version="2.0">
              <head><title>Test</title></head>
              <body>
                <outline text="AI Blogs" title="AI Blogs">
                  <outline type="rss" text="OpenAI" title="OpenAI" xmlUrl="https://openai.com/blog" htmlUrl="https://openai.com/blog"/>
                  <outline type="rss" text="Anthropic" title="Anthropic" xmlUrl="https://www.anthropic.com/news" htmlUrl="https://www.anthropic.com/news"/>
                </outline>
              </body>
            </opml>
        """.trimIndent()

        val parsed = OpmlParser.parse(xml.byteInputStream())
        assertEquals(1, parsed.categories.size)
        assertEquals("AI Blogs", parsed.categories[0].name)
        assertEquals(2, parsed.categories[0].sources.size)
        assertEquals("OpenAI", parsed.categories[0].sources[0].title)
        assertEquals("https://openai.com/blog", parsed.categories[0].sources[0].url)
        assertEquals(2, parsed.totalSources)
    }

    @Test
    fun `flattens nested sub-folders into top-level category`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <opml version="2.0">
              <body>
                <outline text="Computer Science" title="Computer Science">
                  <outline text="Blog - AI" title="Blog - AI">
                    <outline type="rss" text="Feed A" xmlUrl="https://a.example.com"/>
                    <outline type="rss" text="Feed B" xmlUrl="https://b.example.com"/>
                  </outline>
                  <outline text="Blog - Security" title="Blog - Security">
                    <outline type="rss" text="Feed C" xmlUrl="https://c.example.com"/>
                  </outline>
                </outline>
              </body>
            </opml>
        """.trimIndent()

        val parsed = OpmlParser.parse(xml.byteInputStream())
        assertEquals(1, parsed.categories.size)
        assertEquals("Computer Science", parsed.categories[0].name)
        // All three leaves roll up into the single top-level category.
        assertEquals(3, parsed.categories[0].sources.size)
    }

    @Test
    fun `strips Backup prefix from category name`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <opml version="2.0">
              <body>
                <outline text="[Backup] Entertainment" title="[Backup] Entertainment">
                  <outline type="rss" text="X" xmlUrl="https://x.example.com"/>
                </outline>
              </body>
            </opml>
        """.trimIndent()

        val parsed = OpmlParser.parse(xml.byteInputStream())
        assertEquals("Entertainment", parsed.categories[0].name)
    }

    @Test
    fun `handles empty body gracefully`() {
        val xml = """<?xml version="1.0"?><opml version="2.0"><body></body></opml>"""
        val parsed = OpmlParser.parse(xml.byteInputStream())
        assertTrue(parsed.categories.isEmpty())
        assertEquals(0, parsed.totalSources)
    }

    @Test
    fun `export-then-import round-trips`() {
        val tree = listOf(
            SourceFolderNode(
                category = Category(id = "c1", topicId = "t1", level = 1, parentId = null, name = "Math", sortOrder = 0),
                sources = listOf(
                    SourceNode(
                        source = Source(id = "s1", categoryId = "c1", topicId = "t1",
                            kind = SourceKind.RSS, url = "https://terrytao.wordpress.com/", title = "Terry Tao"),
                        counts = SourceCounts(0, 0),
                    ),
                ),
            ),
        )
        val exported = OpmlSerializer.serialize(tree)
        val reparsed = OpmlParser.parse(exported.byteInputStream())

        assertEquals(1, reparsed.categories.size)
        assertEquals("Math", reparsed.categories[0].name)
        assertEquals(1, reparsed.categories[0].sources.size)
        assertEquals("Terry Tao", reparsed.categories[0].sources[0].title)
        assertEquals("https://terrytao.wordpress.com/", reparsed.categories[0].sources[0].url)
    }
}
