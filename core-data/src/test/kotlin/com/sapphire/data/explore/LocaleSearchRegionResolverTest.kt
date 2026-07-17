package com.sapphire.data.explore

import com.sapphire.domain.explore.SearchRegion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class LocaleSearchRegionResolverTest {

    private var savedLocale: Locale? = null

    @Before
    fun saveLocale() {
        savedLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        savedLocale?.let { Locale.setDefault(it) }
    }

    @Test
    fun `zh-CN locale resolves to CHINA`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val resolver = LocaleSearchRegionResolver()
        assertEquals(SearchRegion.CHINA, resolver.resolve())
    }

    @Test
    fun `zh-TW locale resolves to CHINA by language match`() {
        Locale.setDefault(Locale.TAIWAN)
        val resolver = LocaleSearchRegionResolver()
        assertEquals(SearchRegion.CHINA, resolver.resolve())
    }

    @Test
    fun `en-US locale resolves to WEST`() {
        Locale.setDefault(Locale.US)
        val resolver = LocaleSearchRegionResolver()
        assertEquals(SearchRegion.WEST, resolver.resolve())
    }

    @Test
    fun `fr-FR locale resolves to WEST`() {
        Locale.setDefault(Locale.FRANCE)
        val resolver = LocaleSearchRegionResolver()
        assertEquals(SearchRegion.WEST, resolver.resolve())
    }

    @Test
    fun `root locale resolves to WEST`() {
        Locale.setDefault(Locale.ROOT)
        val resolver = LocaleSearchRegionResolver()
        assertEquals(SearchRegion.WEST, resolver.resolve())
    }
}
