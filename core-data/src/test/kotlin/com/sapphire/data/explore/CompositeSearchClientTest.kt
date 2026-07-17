package com.sapphire.data.explore

import com.sapphire.domain.explore.SearchConfig
import com.sapphire.domain.explore.SearchRegion
import com.sapphire.domain.explore.SearchRegionResolver
import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositeSearchClientTest {

    private fun hit(tag: String) = WebSearchHit(title = tag, url = "https://$tag", content = "")

    private class CountingClient(private val hits: List<WebSearchHit>) : WebSearchClient {
        var calls = 0; private set
        override suspend fun search(query: String): List<WebSearchHit> {
            calls++
            return hits
        }
    }

    private class EmptyClient : WebSearchClient {
        override suspend fun search(query: String) = emptyList<WebSearchHit>()
    }

    private class TestConfig(
        region: SearchRegion = SearchRegion.AUTO,
        key: String = "",
    ) : SearchConfig {
        private val regionFlow = MutableStateFlow(region)
        private val keyFlow = MutableStateFlow(key)
        override fun region() = regionFlow.value
        override fun observeRegion() = regionFlow
        override fun observeTavilyKey() = keyFlow
        override suspend fun setRegion(r: SearchRegion) { regionFlow.value = r }
        override suspend fun setTavilyKey(k: String) { keyFlow.value = k }
    }

    private class FixedResolver(private val r: SearchRegion) : SearchRegionResolver {
        override fun resolve() = r
    }

    @Test
    fun `WEST region tries DDG first and returns its results without calling Baidu`() = runTest {
        val ddg = CountingClient(listOf(hit("ddg")))
        val baidu = CountingClient(listOf(hit("baidu")))
        val c = CompositeSearchClient(
            tavily = EmptyClient(),
            ddg = ddg,
            baidu = baidu,
            config = TestConfig(region = SearchRegion.WEST),
            regionResolver = FixedResolver(SearchRegion.WEST),
        )
        val result = c.search("test")
        assertEquals(listOf(hit("ddg")), result)
        assertEquals(1, ddg.calls)
        assertEquals(0, baidu.calls)
    }

    @Test
    fun `CHINA region tries Baidu first and returns its results without calling DDG`() = runTest {
        val ddg = CountingClient(listOf(hit("ddg")))
        val baidu = CountingClient(listOf(hit("baidu")))
        val c = CompositeSearchClient(
            tavily = EmptyClient(),
            ddg = ddg,
            baidu = baidu,
            config = TestConfig(region = SearchRegion.CHINA),
            regionResolver = FixedResolver(SearchRegion.CHINA),
        )
        val result = c.search("test")
        assertEquals(listOf(hit("baidu")), result)
        assertEquals(0, ddg.calls)
        assertEquals(1, baidu.calls)
    }

    @Test
    fun `WEST with DDG empty fails over to Baidu`() = runTest {
        val ddg = EmptyClient()
        val baidu = CountingClient(listOf(hit("baidu")))
        val c = CompositeSearchClient(
            tavily = EmptyClient(),
            ddg = ddg,
            baidu = baidu,
            config = TestConfig(region = SearchRegion.WEST),
            regionResolver = FixedResolver(SearchRegion.WEST),
        )
        val result = c.search("test")
        assertEquals(listOf(hit("baidu")), result)
        assertEquals(1, baidu.calls)
    }

    @Test
    fun `AUTO with zh-resolving locale uses CHINA order`() = runTest {
        val ddg = CountingClient(listOf(hit("ddg")))
        val baidu = CountingClient(listOf(hit("baidu")))
        val c = CompositeSearchClient(
            tavily = EmptyClient(),
            ddg = ddg,
            baidu = baidu,
            config = TestConfig(region = SearchRegion.AUTO),
            regionResolver = FixedResolver(SearchRegion.CHINA),
        )
        val result = c.search("test")
        assertEquals(listOf(hit("baidu")), result)
        assertEquals(0, ddg.calls)
    }

    @Test
    fun `AUTO with west-resolving locale uses WEST order`() = runTest {
        val ddg = CountingClient(listOf(hit("ddg")))
        val baidu = CountingClient(listOf(hit("baidu")))
        val c = CompositeSearchClient(
            tavily = EmptyClient(),
            ddg = ddg,
            baidu = baidu,
            config = TestConfig(region = SearchRegion.AUTO),
            regionResolver = FixedResolver(SearchRegion.WEST),
        )
        val result = c.search("test")
        assertEquals(listOf(hit("ddg")), result)
        assertEquals(0, baidu.calls)
    }

    @Test
    fun `explicit WEST override beats AUTO + zh locale`() = runTest {
        val ddg = CountingClient(listOf(hit("ddg")))
        val baidu = CountingClient(listOf(hit("baidu")))
        val c = CompositeSearchClient(
            tavily = EmptyClient(),
            ddg = ddg,
            baidu = baidu,
            config = TestConfig(region = SearchRegion.WEST), // explicit
            regionResolver = FixedResolver(SearchRegion.CHINA), // would imply CHINA under AUTO
        )
        val result = c.search("test")
        assertEquals(listOf(hit("ddg")), result)
    }

    @Test
    fun `Tavily key set tries Tavily first, chain only on empty`() = runTest {
        val tavily = CountingClient(listOf(hit("tavily")))
        val ddg = CountingClient(listOf(hit("ddg")))
        val baidu = CountingClient(listOf(hit("baidu")))
        val c = CompositeSearchClient(
            tavily = tavily,
            ddg = ddg,
            baidu = baidu,
            config = TestConfig(region = SearchRegion.AUTO, key = "tvly-x"),
            regionResolver = FixedResolver(SearchRegion.WEST),
        )
        val result = c.search("test")
        assertEquals(listOf(hit("tavily")), result)
        assertEquals(0, ddg.calls)
        assertEquals(0, baidu.calls)
    }

    @Test
    fun `Tavily key set but empty result falls through to chain`() = runTest {
        val tavily = EmptyClient()
        val ddg = CountingClient(listOf(hit("ddg")))
        val baidu = CountingClient(listOf(hit("baidu")))
        val c = CompositeSearchClient(
            tavily = tavily,
            ddg = ddg,
            baidu = baidu,
            config = TestConfig(region = SearchRegion.WEST, key = "tvly-x"),
            regionResolver = FixedResolver(SearchRegion.WEST),
        )
        val result = c.search("test")
        assertEquals(listOf(hit("ddg")), result)
        assertEquals(1, ddg.calls)
    }

    @Test
    fun `all empty returns empty without throwing`() = runTest {
        val c = CompositeSearchClient(
            tavily = EmptyClient(),
            ddg = EmptyClient(),
            baidu = EmptyClient(),
            config = TestConfig(region = SearchRegion.WEST),
            regionResolver = FixedResolver(SearchRegion.WEST),
        )
        assertEquals(emptyList<WebSearchHit>(), c.search("test"))
    }
}
