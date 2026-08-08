package com.sapphire.data.explore

import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CompositeSearchClient — free-first search routing. The current implementation tries all
 * engines in a flat chain (exa → bing → ddg → baidu), first non-empty wins, then Tavily as
 * optional paid fallback. Region-based ordering was removed; the chain order is fixed.
 *
 * Tests exercise: first-non-empty wins, fall-through on empty, Tavily fallback, all-empty.
 */
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

    private fun composite(
        tavily: WebSearchClient = EmptyClient(),
        exa: WebSearchClient = EmptyClient(),
        bing: WebSearchClient = EmptyClient(),
        ddg: WebSearchClient = EmptyClient(),
        baidu: WebSearchClient = EmptyClient(),
    ) = CompositeSearchClient(tavily, exa, bing, ddg, baidu)

    @Test
    fun `first non-empty engine in the chain wins`() = runTest {
        val exa = CountingClient(listOf(hit("exa")))
        val ddg = CountingClient(listOf(hit("ddg")))
        val c = composite(exa = exa, ddg = ddg)

        val result = c.search("test")

        assertEquals(listOf(hit("exa")), result)
        assertEquals(1, exa.calls)
        assertEquals(0, ddg.calls)
    }

    @Test
    fun `empty exa falls through to bing`() = runTest {
        val bing = CountingClient(listOf(hit("bing")))
        val c = composite(exa = EmptyClient(), bing = bing)

        val result = c.search("test")

        assertEquals(listOf(hit("bing")), result)
        assertEquals(1, bing.calls)
    }

    @Test
    fun `all free engines empty falls through to Tavily`() = runTest {
        val tavily = CountingClient(listOf(hit("tavily")))
        val c = composite(tavily = tavily)

        val result = c.search("test")

        assertEquals(listOf(hit("tavily")), result)
        assertEquals(1, tavily.calls)
    }

    @Test
    fun `Tavily empty too returns empty`() = runTest {
        val c = composite()

        assertEquals(emptyList<WebSearchHit>(), c.search("test"))
    }

    @Test
    fun `middle engine in chain returns before later engines are called`() = runTest {
        val bing = CountingClient(listOf(hit("bing")))
        val ddg = CountingClient(listOf(hit("ddg")))
        val baidu = CountingClient(listOf(hit("baidu")))
        val c = composite(exa = EmptyClient(), bing = bing, ddg = ddg, baidu = baidu)

        val result = c.search("test")

        assertEquals(listOf(hit("bing")), result)
        assertEquals(1, bing.calls)
        assertEquals(0, ddg.calls)
        assertEquals(0, baidu.calls)
    }

    @Test
    fun `all empty returns empty without throwing`() = runTest {
        val c = composite()
        assertEquals(emptyList<WebSearchHit>(), c.search("test"))
    }
}
