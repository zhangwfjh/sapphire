package com.sapphire.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FeedItemIdTest {

    // ---------- determinism / stability ----------

    @Test fun `same inputs produce same hash`() {
        val a = FeedItemId.fromUrl("src-1", "https://example.com/post")
        val b = FeedItemId.fromUrl("src-1", "https://example.com/post")
        assertEquals(a, b)
    }

    @Test fun `different source ids diverge even for same url`() {
        assertNotEquals(
            FeedItemId.fromUrl("src-1", "https://example.com/post"),
            FeedItemId.fromUrl("src-2", "https://example.com/post"),
        )
    }

    @Test fun `hash is 64-char lowercase hex`() {
        val h = FeedItemId.fromUrl("s", "https://x.test/p")
        assertEquals(64, h.length)
        assert(h.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ---------- URL canonicalization: the "same story many feeds" case ----------

    @Test fun `scheme and host case-insensitive`() {
        assertEquals(
            FeedItemId.fromUrl("s", "https://Example.COM/Post"),
            FeedItemId.fromUrl("s", "HTTPS://example.com/Post"),
        )
    }

    @Test fun `default ports 443 and 80 stripped`() {
        assertEquals(
            FeedItemId.fromUrl("s", "https://example.com:443/p"),
            FeedItemId.fromUrl("s", "https://example.com/p"),
        )
        assertEquals(
            FeedItemId.fromUrl("s", "http://example.com:80/p"),
            FeedItemId.fromUrl("s", "http://example.com/p"),
        )
    }

    @Test fun `non-default port preserved`() {
        assertNotEquals(
            FeedItemId.fromUrl("s", "https://example.com:8443/p"),
            FeedItemId.fromUrl("s", "https://example.com/p"),
        )
    }

    @Test fun `trailing slash collapsed`() {
        assertEquals(
            FeedItemId.fromUrl("s", "https://example.com/p/"),
            FeedItemId.fromUrl("s", "https://example.com/p"),
        )
    }

    @Test fun `fragment ignored`() {
        assertEquals(
            FeedItemId.fromUrl("s", "https://example.com/p#section"),
            FeedItemId.fromUrl("s", "https://example.com/p#other"),
        )
    }

    @Test fun `query params order-independent`() {
        assertEquals(
            FeedItemId.fromUrl("s", "https://example.com/p?a=1&b=2"),
            FeedItemId.fromUrl("s", "https://example.com/p?b=2&a=1"),
        )
    }

    @Test fun `utm and fbclid tracking params stripped`() {
        assertEquals(
            FeedItemId.fromUrl("s", "https://example.com/p?id=42&utm_source=feed&fbclid=abc"),
            FeedItemId.fromUrl("s", "https://example.com/p?id=42"),
        )
    }

    @Test fun `distinct query values diverge`() {
        assertNotEquals(
            FeedItemId.fromUrl("s", "https://example.com/p?id=42"),
            FeedItemId.fromUrl("s", "https://example.com/p?id=43"),
        )
    }

    @Test fun `path case preserved`() {
        // Path is case-sensitive on real servers; do NOT lowercase it.
        assertNotEquals(
            FeedItemId.fromUrl("s", "https://example.com/Post"),
            FeedItemId.fromUrl("s", "https://example.com/post"),
        )
    }

    // ---------- title+publishedAt fallback ----------

    @Test fun `title fallback stable and case-insensitive on title`() {
        assertEquals(
            FeedItemId.fromTitleAndPublishedAt("s", "Hello World", 1000L),
            FeedItemId.fromTitleAndPublishedAt("s", "  hello world ", 1000L),
        )
    }

    @Test fun `title fallback diverges on publishedAt`() {
        assertNotEquals(
            FeedItemId.fromTitleAndPublishedAt("s", "Hello", 1000L),
            FeedItemId.fromTitleAndPublishedAt("s", "Hello", 2000L),
        )
    }

    @Test fun `title fallback null publishedAt stable`() {
        assertEquals(
            FeedItemId.fromTitleAndPublishedAt("s", "Hello", null),
            FeedItemId.fromTitleAndPublishedAt("s", "Hello", null),
        )
    }
}
