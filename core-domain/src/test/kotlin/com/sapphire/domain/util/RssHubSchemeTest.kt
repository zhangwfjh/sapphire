package com.sapphire.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RssHubSchemeTest {

    @Test
    fun `rsshub route resolves to the public instance`() {
        assertEquals(
            "https://russhub.umzzz.com/bbc/world",
            resolveFeedUrl("rsshub://bbc/world"),
        )
    }

    @Test
    fun `rsshub scheme is case-insensitive`() {
        assertEquals(
            "https://russhub.umzzz.com/bbc/world",
            resolveFeedUrl("RSSHUB://bbc/world"),
        )
    }

    @Test
    fun `collapses leading slashes after the scheme`() {
        assertEquals(
            "https://russhub.umzzz.com/bbc/world",
            resolveFeedUrl("rsshub:///bbc/world"),
        )
    }

    @Test
    fun `nested route is preserved`() {
        assertEquals(
            "https://russhub.umzzz.com/bilibili/user/dynamic/12345",
            resolveFeedUrl("rsshub://bilibili/user/dynamic/12345"),
        )
    }

    @Test
    fun `plain https url is returned unchanged`() {
        assertEquals(
            "https://hnrss.org/frontpage",
            resolveFeedUrl("https://hnrss.org/frontpage"),
        )
    }

    @Test
    fun `bare path without scheme is returned unchanged`() {
        assertEquals("example.com/feed", resolveFeedUrl("example.com/feed"))
    }

    @Test
    fun `empty rsshub route falls back to the input`() {
        // No route → nothing to resolve; don't fabricate a bare host request.
        assertEquals("rsshub://", resolveFeedUrl("rsshub://"))
    }
}
