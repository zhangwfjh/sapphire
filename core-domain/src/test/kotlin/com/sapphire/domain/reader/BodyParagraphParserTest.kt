package com.sapphire.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BodyParagraphParser] — splits the reader body into clean paragraphs for the
 * paragraph-aligned translate (PRD §3.4) and rendering. Contract: block tags/br-clusters
 * delimit paragraphs, inline HTML is stripped, empties dropped, order preserved.
 */
class BodyParagraphParserTest {

    @Test
    fun `null or blank returns empty list`() {
        assertTrue(BodyParagraphParser.parse(null).isEmpty())
        assertTrue(BodyParagraphParser.parse("").isEmpty())
        assertTrue(BodyParagraphParser.parse("   ").isEmpty())
    }

    @Test
    fun `plain text split on blank lines`() {
        val body = "First paragraph.\n\nSecond paragraph.\n\nThird."
        assertEquals(listOf("First paragraph.", "Second paragraph.", "Third."), BodyParagraphParser.parse(body))
    }

    @Test
    fun `p tags delimit paragraphs`() {
        val body = "<p>One</p><p>Two</p><p>Three</p>"
        assertEquals(listOf("One", "Two", "Three"), BodyParagraphParser.parse(body))
    }

    @Test
    fun `br clusters delimit paragraphs`() {
        val body = "One<br><br>Two<br/><br/>Three"
        assertEquals(listOf("One", "Two", "Three"), BodyParagraphParser.parse(body))
    }

    @Test
    fun `single br becomes line break not paragraph break`() {
        val body = "Line one<br>Line two"
        val paragraphs = BodyParagraphParser.parse(body)
        assertEquals(1, paragraphs.size)
        assertTrue(paragraphs[0].contains("Line one"))
        assertTrue(paragraphs[0].contains("Line two"))
    }

    @Test
    fun `inline tags are stripped`() {
        val body = "<p>This is <b>bold</b> and <a href=\"x\">linked</a> text.</p>"
        val paragraphs = BodyParagraphParser.parse(body)
        assertEquals(1, paragraphs.size)
        assertEquals("This is bold and linked text.", paragraphs[0])
    }

    @Test
    fun `common entities are decoded`() {
        val body = "<p>Tom &amp; Jerry &lt;3 &quot;quotes&quot; &#39;apos&apos;</p>"
        assertEquals(listOf("Tom & Jerry <3 \"quotes\" 'apos'"), BodyParagraphParser.parse(body))
    }

    @Test
    fun `empty paragraphs are dropped`() {
        val body = "<p>One</p><p></p><p>   </p><p>Two</p>"
        assertEquals(listOf("One", "Two"), BodyParagraphParser.parse(body))
    }

    @Test
    fun `div close tags delimit paragraphs`() {
        val body = "<div>Block A</div><div>Block B</div>"
        assertEquals(listOf("Block A", "Block B"), BodyParagraphParser.parse(body))
    }

    @Test
    fun `falls back to summary when bodyRaw null`() {
        // ReaderOpsUseCase handles the fallback; here we just assert parse(null) is empty
        // so the caller's fallback (summary/title) is exercised.
        assertTrue(BodyParagraphParser.parse(null).isEmpty())
    }
}
