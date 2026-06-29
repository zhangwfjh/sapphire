package com.sapphire.data.reader

import com.sapphire.domain.reader.RichBlock
import com.sapphire.domain.reader.RichSpan
import com.sapphire.domain.reader.toPlainParagraphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JsoupRichContentParser] — HTML → [RichBlock] structure. Covers the inline spans
 * (bold/italic/strike/code/link) and the block types (paragraph/heading/list/quote/code/
 * image) the reader renders, plus the plain-paragraph view the Tier-2 LLM ops consume.
 *
 * Pure-JVM (Jsoup has no Android deps) so runs under the standard core-data unit test runner.
 */
class JsoupRichContentParserTest {

    private val parser = JsoupRichContentParser()

    @Test
    fun `null or blank returns empty`() {
        assertTrue(parser.parse(null).isEmpty())
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse("   ").isEmpty())
    }

    @Test
    fun `plain text becomes a single paragraph`() {
        val blocks = parser.parse("Just some plain text.")
        assertEquals(1, blocks.size)
        val p = blocks.first() as RichBlock.Paragraph
        assertEquals("Just some plain text.", p.spans.text())
    }

    @Test
    fun `bold and italic spans survive`() {
        val blocks = parser.parse("<p>This is <b>bold</b> and <i>italic</i> text.</p>")
        val p = blocks.first() as RichBlock.Paragraph
        // Order: Text, Bold, Text, Italic, Text
        assertEquals(RichSpan.Text("This is "), p.spans[0])
        assertEquals(RichSpan.Bold(listOf(RichSpan.Text("bold"))), p.spans[1])
        assertEquals(RichSpan.Text(" and "), p.spans[2])
        assertEquals(RichSpan.Italic(listOf(RichSpan.Text("italic"))), p.spans[3])
        assertEquals(RichSpan.Text(" text."), p.spans[4])
    }

    @Test
    fun `nested bold-italic preserves nesting`() {
        val blocks = parser.parse("<p><b><i>both</i></b></p>")
        val p = blocks.first() as RichBlock.Paragraph
        assertEquals(
            RichSpan.Bold(listOf(RichSpan.Italic(listOf(RichSpan.Text("both"))))),
            p.spans.first(),
        )
    }

    @Test
    fun `link span carries url and styled children`() {
        val blocks = parser.parse("""<p>See <a href="https://example.com">this</a>.</p>""")
        val p = blocks.first() as RichBlock.Paragraph
        assertEquals(RichSpan.Text("See "), p.spans[0])
        assertEquals(RichSpan.Link("https://example.com", listOf(RichSpan.Text("this"))), p.spans[1])
    }

    @Test
    fun `strikethrough and inline code map`() {
        val blocks = parser.parse("<p><s>gone</s><code>let x = 1</code></p>")
        val p = blocks.first() as RichBlock.Paragraph
        assertEquals(RichSpan.Strikethrough(listOf(RichSpan.Text("gone"))), p.spans[0])
        assertEquals(RichSpan.Code("let x = 1"), p.spans[1])
    }

    @Test
    fun `headings map with level`() {
        val blocks = parser.parse("<h2>Title</h2><p>Body.</p>")
        val h = blocks[0] as RichBlock.Heading
        assertEquals(2, h.level)
        assertEquals("Title", h.spans.text())
        assertTrue(blocks[1] is RichBlock.Paragraph)
    }

    @Test
    fun `unordered list emits bulleted list items`() {
        val blocks = parser.parse("<ul><li>One</li><li>Two</li></ul>")
        assertEquals(2, blocks.size)
        val first = blocks[0] as RichBlock.ListItem
        assertEquals(false, first.ordered)
        assertEquals("One", first.spans.text())
        assertEquals("Two", (blocks[1] as RichBlock.ListItem).spans.text())
    }

    @Test
    fun `ordered list carries one-based index`() {
        val blocks = parser.parse("<ol><li>A</li><li>B</li></ol>")
        val first = blocks[0] as RichBlock.ListItem
        assertEquals(true, first.ordered)
        assertEquals(1, first.index)
        assertEquals(2, (blocks[1] as RichBlock.ListItem).index)
    }

    @Test
    fun `blockquote becomes a quote block`() {
        val blocks = parser.parse("<blockquote>A notable remark.</blockquote>")
        val q = blocks.first() as RichBlock.Quote
        assertEquals("A notable remark.", q.spans.text())
    }

    @Test
    fun `pre block becomes code block`() {
        val blocks = parser.parse("<pre>val x = 42</pre>")
        val c = blocks.first() as RichBlock.Code
        assertEquals("val x = 42", c.text)
    }

    @Test
    fun `image extracts url and alt`() {
        val blocks = parser.parse("""<img src="https://x.test/a.png" alt="diagram">""")
        val img = blocks.first() as RichBlock.Image
        assertEquals("https://x.test/a.png", img.url)
        assertEquals("diagram", img.alt)
    }

    @Test
    fun `figure pairs image with figcaption caption`() {
        val blocks = parser.parse(
            """<figure><img src="https://x.test/b.png" alt="b"><figcaption>Figure 1</figcaption></figure>""",
        )
        val img = blocks.first() as RichBlock.Image
        assertEquals("https://x.test/b.png", img.url)
        assertEquals("Figure 1", img.caption)
    }

    @Test
    fun `wrapper divs are transparent`() {
        val blocks = parser.parse("<div><div><p>Inside</p></div></div>")
        assertEquals(1, blocks.size)
        assertEquals("Inside", (blocks.first() as RichBlock.Paragraph).spans.text())
    }

    @Test
    fun `script and style content is dropped`() {
        val blocks = parser.parse("<p>Keep</p><script>alert('x')</script><style>.a{}</style>")
        assertEquals(1, blocks.size)
        assertEquals("Keep", (blocks.first() as RichBlock.Paragraph).spans.text())
    }

    @Test
    fun `entities are decoded by jsoup`() {
        val blocks = parser.parse("<p>Tom &amp; Jerry &lt;3</p>")
        assertEquals("Tom & Jerry <3", (blocks.first() as RichBlock.Paragraph).spans.text())
    }

    @Test
    fun `plain paragraph view is one non-empty string per text block`() {
        val blocks = parser.parse("<h2>Title</h2><p>First.</p><p>Second.</p>")
        assertEquals(listOf("Title", "First.", "Second."), blocks.toPlainParagraphs())
    }

    @Test
    fun `image without caption is dropped from the plain paragraph view`() {
        val blocks = parser.parse("<p>Text</p><img src=\"x.png\"><p>More</p>")
        // Image has no caption/alt -> not a text-bearing block.
        assertEquals(listOf("Text", "More"), blocks.toPlainParagraphs())
    }

    private fun List<RichSpan>.text(): String = joinToString("") { it.plainText() }
}
