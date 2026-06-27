package com.sapphire.data.explore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAssetParserTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val parser = CatalogAssetParser(json)

    @Test
    fun `parses sample asset into domains and feeds`() {
        val input = javaClass.classLoader!!.getResourceAsStream("explore-catalog-sample.json")!!
        val asset = parser.parse(input)

        assertEquals(1, asset.version)
        assertEquals(listOf("tech", "science"), asset.domains.map { it.id })
        val tech = asset.domains[0]
        assertEquals("Technology", tech.name)
        assertEquals(2, tech.feeds.size)
        assertEquals("Hacker News", tech.feeds[0].title)
        assertEquals("atom", tech.feeds[1].kind)
    }

    @Test
    fun `corrupt json returns empty asset instead of throwing`() {
        val corrupt = "not json".byteInputStream()
        val asset = parser.parse(corrupt)
        assertTrue(asset.domains.isEmpty())
    }

    @Test
    fun `defaults kind to rss when missing`() {
        val minimal = """{"version":1,"domains":[{"id":"x","name":"X","feeds":[{"title":"F","url":"https://e.com"}]}]}""".byteInputStream()
        val asset = parser.parse(minimal)
        assertEquals("rss", asset.domains[0].feeds[0].kind)
    }
}
