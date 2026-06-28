package com.sapphire.domain.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlFeedParserTest {

    @Test
    fun `blank returns null`() {
        assertNull(parseUrlFeed("   "))
    }

    @Test
    fun `plain topic returns null`() {
        assertNull(parseUrlFeed("biohacking"))
        assertNull(parseUrlFeed("rust programming"))
    }

    @Test
    fun `URL with scheme yields a parsed feed`() {
        val feed = parseUrlFeed("https://hnrss.org/frontpage")!!
        assertEquals("hnrss.org/frontpage", feed.title)
        assertEquals("https://hnrss.org/frontpage", feed.url)
    }

    @Test
    fun `bare host with path qualifies`() {
        val feed = parseUrlFeed("example.com/feed.xml")!!
        assertEquals("example.com/feed.xml", feed.title)
        assertEquals("example.com/feed.xml", feed.url)
    }

    @Test
    fun `bare host without path does not qualify`() {
        // "example.com" alone is ambiguous (could be a search); require a path or scheme.
        assertNull(parseUrlFeed("example.com"))
    }

    @Test
    fun `host without dot is treated as a topic`() {
        assertNull(parseUrlFeed("localhost/feed"))
    }

    @Test
    fun `root path does not leak into title`() {
        val feed = parseUrlFeed("https://example.com/")!!
        assertEquals("example.com", feed.title)
    }
}
