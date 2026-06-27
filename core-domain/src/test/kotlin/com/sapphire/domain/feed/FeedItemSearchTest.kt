package com.sapphire.domain.feed

import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.model.ReadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-feed text search predicate (design: 2026-06-24-in-feed-text-search-design.md).
 * Covers: blank = identity, title/summary/author matches, case-insensitivity,
 * null-field safety, multi-word-as-substring contract, list filter ordering.
 */
class FeedItemSearchTest {

    private fun item(
        title: String,
        summary: String? = null,
        author: String? = null,
    ) = FeedItem(
        hashUuid = "h-$title",
        sourceId = "s",
        categoryId = "c",
        title = title,
        summary = summary,
        bodyRaw = null,
        authorHandle = author,
        publishedAt = null,
        fetchedAt = 0L,
        platformTag = null,
        mediaUrl = null,
        readState = ReadState.UNREAD,
    )

    @Test
    fun `blank query matches everything`() {
        assertTrue(item("Anything").matchesQuery(""))
        assertTrue(item("Anything").matchesQuery("   "))
        assertTrue(item("Anything").matchesQuery("\t"))
    }

    @Test
    fun `title substring match`() {
        assertTrue(item("LLM Infra Weekly").matchesQuery("infra"))
        assertTrue(item("LLM Infra Weekly").matchesQuery("LLM"))
    }

    @Test
    fun `summary match when title does not`() {
        assertTrue(item("Nope", summary = "A deep dive into rust async").matchesQuery("rust"))
    }

    @Test
    fun `author handle match`() {
        assertTrue(item("Post", author = "@ada_lovelace").matchesQuery("ada"))
    }

    @Test
    fun `case-insensitive across all fields`() {
        val it = item("Rust Release", summary = "Summary TEXT", author = "@Author")
        assertTrue(it.matchesQuery("RUST"))
        assertTrue(it.matchesQuery("text"))
        assertTrue(it.matchesQuery("AUTHOR"))
    }

    @Test
    fun `no match returns false`() {
        assertFalse(item("Hello", summary = "World", author = "@x").matchesQuery("zzz"))
    }

    @Test
    fun `null summary and author do not crash or false-match`() {
        val it = item("Only Title", summary = null, author = null)
        assertTrue(it.matchesQuery("only"))
        assertFalse(it.matchesQuery("missing"))
    }

    @Test
    fun `query is a single substring not tokenized`() {
        // "ai infra" must match the literal contiguous "ai infra", not "ai" AND "infra".
        assertTrue(item("The ai infra landscape").matchesQuery("ai infra"))
        // An item that has "ai" and "infra" separately does NOT match the phrase.
        assertFalse(item("ai is great, infra is hard").matchesQuery("ai infra"))
    }

    @Test
    fun `query is trimmed`() {
        assertTrue(item("Rust").matchesQuery("  rust  "))
    }

    @Test
    fun `filterByQuery preserves order and blank is identity`() {
        val list = listOf(
            item("Rust 1", author = "@a"),
            item("Go 2", summary = "rust mention"),
            item("Python 3"),
        )
        assertEquals(list, list.filterByQuery(""))
        assertEquals(list, list.filterByQuery("   "))
        // Only the first two mention rust.
        val filtered = list.filterByQuery("rust")
        assertEquals(2, filtered.size)
        assertEquals("Rust 1", filtered[0].title)
        assertEquals("Go 2", filtered[1].title)
    }

    @Test
    fun `bodyRaw is not searched`() {
        // An item whose title/summary/author do not match but body would, must not match.
        val it = item("Title", summary = "Summary", author = "@a").copy(bodyRaw = "secret buried text")
        assertFalse(it.matchesQuery("secret buried"))
    }
}
