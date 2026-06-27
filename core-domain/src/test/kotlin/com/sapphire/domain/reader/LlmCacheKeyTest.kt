package com.sapphire.domain.reader

import com.sapphire.domain.util.LlmCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [LlmCacheKey] determinism + separation. Same (item, op, model) must collide; any single
 * field change must diverge. This is the idempotent-cache contract (PRD §4.2 re-open is free).
 */
class LlmCacheKeyTest {

    @Test
    fun `same inputs produce the same key`() {
        val a = LlmCacheKey.compute("item-1", "summary", "gpt-4o")
        val b = LlmCacheKey.compute("item-1", "summary", "gpt-4o")
        assertEquals(a, b)
    }

    @Test
    fun `different item diverges`() {
        val a = LlmCacheKey.compute("item-1", "summary", "gpt-4o")
        val b = LlmCacheKey.compute("item-2", "summary", "gpt-4o")
        assertNotEquals(a, b)
    }

    @Test
    fun `different op diverges`() {
        val a = LlmCacheKey.compute("item-1", "summary", "gpt-4o")
        val b = LlmCacheKey.compute("item-1", "classification", "gpt-4o")
        assertNotEquals(a, b)
    }

    @Test
    fun `different model version diverges`() {
        val a = LlmCacheKey.compute("item-1", "summary", "gpt-4o")
        val b = LlmCacheKey.compute("item-1", "summary", "gpt-4o-2024")
        assertNotEquals(a, b)
    }

    @Test
    fun `translate op keyed per target language diverges`() {
        val zh = LlmCacheKey.compute("item-1", "translate:zh", "gpt-4o")
        val ja = LlmCacheKey.compute("item-1", "translate:ja", "gpt-4o")
        assertNotEquals(zh, ja)
    }

    @Test
    fun `key is 64-char hex sha256`() {
        val key = LlmCacheKey.compute("item-1", "summary", "gpt-4o")
        assertEquals(64, key.length)
        assert(key.all { it in "0123456789abcdef" })
    }
}
