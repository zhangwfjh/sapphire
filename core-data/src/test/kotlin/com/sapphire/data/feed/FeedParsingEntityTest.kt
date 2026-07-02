package com.sapphire.data.feed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [stripHtml] / [decodeHtmlEntities] — title & summary snippet entity decoding. Pure JVM.
 *
 * Regression guard: a feed title like `Anthropic&#8217;s long-sidelined …` must decode the
 * numeric right-quote, not leak the raw entity onto the card.
 */
class FeedParsingEntityTest {

    @Test
    fun `numeric decimal entity decodes`() {
        assertEquals("Anthropic’s Fable", "Anthropic&#8217;s Fable".stripHtml())
    }

    @Test
    fun `numeric hex entity decodes`() {
        assertEquals("Anthropic’s", "Anthropic&#x2019;s".stripHtml())
    }

    @Test
    fun `common named entities decode`() {
        assertEquals("a — b … “c”", "a &mdash; b &hellip; &ldquo;c&rdquo;".stripHtml())
    }

    @Test
    fun `legacy named entities still decode`() {
        assertEquals("Tom & Jerry <3 \"q\" 'a'", "Tom &amp; Jerry &lt;3 &quot;q&quot; &#39;a&#39;".stripHtml())
    }

    @Test
    fun `tags are stripped before entity decode`() {
        assertEquals("Tom & Jerry", "<p>Tom &amp; <b>Jerry</b></p>".stripHtml())
    }

    @Test
    fun `literal ampersand-entity is not double-decoded`() {
        // Source literally contains the text "&amp;#39;" → decodes once to "&#39;", not "'".
        assertEquals("&#39;", "&amp;#39;".stripHtml())
    }

    @Test
    fun `unknown entity left intact`() {
        assertEquals("x &frobnicate; y", "x &frobnicate; y".stripHtml())
    }

    @Test
    fun `bare ampersand without semicolon preserved`() {
        assertEquals("Tom & Jerry", "Tom & Jerry".stripHtml())
    }

    @Test
    fun `full reported title decodes cleanly`() {
        val raw = "Anthropic&#8217;s long-sidelined Fable 5 is greenlit to return"
        assertEquals(
            "Anthropic’s long-sidelined Fable 5 is greenlit to return",
            raw.stripHtml(),
        )
    }
}
