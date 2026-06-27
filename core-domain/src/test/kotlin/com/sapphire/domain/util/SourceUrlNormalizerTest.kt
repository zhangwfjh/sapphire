package com.sapphire.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceUrlNormalizerTest {

    @Test
    fun `blank input returns empty string`() {
        assertEquals("", normalizeSourceUrl("   "))
    }

    @Test
    fun `adds https scheme then drops it so bare host round-trips`() {
        // Scheme is dropped for equivalence, so a bare host+path stays scheme-less.
        assertEquals("example.com/feed", normalizeSourceUrl("example.com/feed"))
    }

    @Test
    fun `lowercases host but preserves path case`() {
        assertEquals("example.com/Feed.xml", normalizeSourceUrl("https://example.com/Feed.xml"))
    }

    @Test
    fun `http and https normalize equal because scheme is dropped`() {
        assertEquals(
            normalizeSourceUrl("https://hnrss.org/frontpage"),
            normalizeSourceUrl("http://hnrss.org/frontpage"),
        )
    }

    @Test
    fun `strips trailing slash unless root`() {
        assertEquals("example.com/feed", normalizeSourceUrl("https://example.com/feed/"))
        assertEquals("example.com/", normalizeSourceUrl("https://example.com/"))
    }

    @Test
    fun `drops fragment and port but keeps query`() {
        assertEquals(
            "example.com/feed?limit=10",
            normalizeSourceUrl("https://example.com:443/feed?limit=10#top"),
        )
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals(
            normalizeSourceUrl("example.com/feed"),
            normalizeSourceUrl("  https://example.com/feed  "),
        )
    }
}
