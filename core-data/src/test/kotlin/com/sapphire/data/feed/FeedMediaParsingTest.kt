package com.sapphire.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the media-extraction helpers in [FeedParsing]. No Robolectric needed —
 * these are plain string functions. The end-to-end `<item>` extraction path is exercised in
 * [RssAtomFetcherParserTest].
 */
class FeedMediaParsingTest {

    @Test
    fun `looksLikeImageUrl detects common raster extensions ignoring query`() {
        assertTrue(looksLikeImageUrl("https://x.com/a/b/photo.jpg"))
        assertTrue(looksLikeImageUrl("https://x.com/PNG.PNG"))
        assertTrue(looksLikeImageUrl("https://x.com/photo.jpg?x=1"))
        assertTrue(looksLikeImageUrl("https://x.com/i.webp#frag"))
        assertTrue(looksLikeImageUrl("https://x.com/a.GIF"))
    }

    @Test
    fun `looksLikeImageUrl rejects non-image urls`() {
        assertFalse(looksLikeImageUrl("https://x.com/audio.mp3"))
        assertFalse(looksLikeImageUrl("https://x.com/page.html"))
        assertFalse(looksLikeImageUrl("https://x.com/noext"))
    }

    @Test
    fun `firstImgSrc extracts first src from body html`() {
        assertEquals(
            "https://x.com/img/cover.jpg",
            firstImgSrc("<p>hi</p><img src=\"https://x.com/img/cover.jpg\" alt=\"c\"/>"),
        )
    }

    @Test
    fun `firstImgSrc falls back to data-src`() {
        assertEquals(
            "https://x.com/lazy.webp",
            firstImgSrc("<img class=\"lazy\" data-src=\"https://x.com/lazy.webp\" />"),
        )
    }

    @Test
    fun `firstImgSrc is case-insensitive on the tag and skips malformed tags`() {
        assertEquals(
            "https://x.com/second.png",
            firstImgSrc("<IMG SRC=\"#\"><img data-src=\"https://x.com/second.png\">"),
        )
    }

    @Test
    fun `firstImgSrc returns null when no img present`() {
        assertNull(firstImgSrc("<p>no imagery here</p>"))
        assertNull(firstImgSrc(""))
    }
}
