package com.sapphire.domain.reader

import com.sapphire.domain.reader.RichBlock.Code
import com.sapphire.domain.reader.RichBlock.Heading
import com.sapphire.domain.reader.RichBlock.Image
import com.sapphire.domain.reader.RichBlock.ListItem
import com.sapphire.domain.reader.RichBlock.Paragraph
import com.sapphire.domain.reader.RichBlock.Quote
import com.sapphire.domain.reader.RichSpan.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the translate/summary paragraph-alignment contract: the LLM input view
 * ([toPlainParagraphs] / [textBlocks]) and the renderer's translate-slot counter must agree
 * on which blocks consume a slot, in document order. A drift here is exactly the
 * "translation lands under the wrong paragraph" bug.
 */
class RichContentAlignmentTest {

    private fun para(text: String) = Paragraph(listOf(Text(text)))
    private fun quote(text: String) = Quote(listOf(Text(text)))
    private fun heading(text: String) = Heading(2, listOf(Text(text)))
    private fun item(text: String) = ListItem(listOf(Text(text)), ordered = false, index = 0)

    @Test
    fun `code is never a translate slot even though it has text`() {
        val code = Code("val x = 42")
        assertEquals("val x = 42", code.plainText()) // still honest about its text…
        assertFalse("…but it consumes no LLM slot", code.isTextBlock())
    }

    @Test
    fun `every prose block type is a translate slot`() {
        assertTrue(para("p").isTextBlock())
        assertTrue(heading("h").isTextBlock())
        assertTrue(item("i").isTextBlock())
        assertTrue(quote("q").isTextBlock())
    }

    @Test
    fun `image is a slot only when it carries caption or alt text`() {
        assertFalse(Image("u", alt = null, caption = null).isTextBlock())
        assertTrue(Image("u", alt = "alt", caption = null).isTextBlock())
        assertTrue(Image("u", alt = null, caption = "cap").isTextBlock())
    }

    @Test
    fun `textBlocks drops code and media-only images, keeps document order`() {
        val blocks = listOf(
            para("P1"),
            Image("u", alt = null, caption = null), // media-only: dropped
            Code("code(body)"),                     // code: dropped
            quote("Q1"),
            para("P2"),
        )
        assertEquals(listOf(para("P1"), quote("Q1"), para("P2")), blocks.textBlocks)
    }

    @Test
    fun `toPlainParagraphs is paragraph-aligned with textBlocks indices`() {
        // Simulates the translate pipeline: the i-th plain paragraph is what the LLM
        // translates, and the renderer re-indexes by the same count. Code between two
        // paragraphs must NOT shift the second paragraph's index.
        val blocks = listOf(
            para("First."),
            Code("val x = 1"),
            quote("A quote."),
            para("Last."),
        )
        assertEquals(listOf("First.", "A quote.", "Last."), blocks.toPlainParagraphs())

        // If the renderer counted Code as a slot, "Last." would pair with the wrong
        // translation. textBlocks and toPlainParagraphs share one index space, so the
        // third slot (index 2) is "Last." and its translation pairs with it.
        val translations = blocks.toPlainParagraphs().map { "T:$it" }
        assertEquals(listOf("T:First.", "T:A quote.", "T:Last."), translations)
        assertEquals("T:Last.", translations[blocks.textBlocks.indexOf(blocks.last())])
    }

    @Test
    fun `empty spans yield no slot`() {
        assertFalse(Paragraph(emptyList()).isTextBlock())
        assertTrue(Paragraph(listOf(Text(""))).plainText().isEmpty())
    }
}
