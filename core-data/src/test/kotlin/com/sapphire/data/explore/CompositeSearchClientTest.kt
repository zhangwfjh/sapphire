package com.sapphire.data.explore

import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CompositeSearchClient — keyless search routing. Tries engines in a fixed chain
 * (exa → bing → ddg → baidu); first non-empty wins, all failures collapse to empty.
 */
class CompositeSearchClientTest {

    private fun hit(tag: String) = WebSearchHit(title = tag, url = "https://$tag", content = "")

    private class CountingClient(private val hits: List<WebSearchHit>) : WebSearchClient {
        var calls = 0
        override suspend fun search(query: String): List<WebSearchHit> {
            calls++
            return hits
        }
    }

    private class EmptyClient : WebSearchClient {
        override suspend fun search(query: String) = emptyList<WebSearchHit>()
    }

    private fun composite(
        exa: WebSearchClient = EmptyClient(),
        bing: WebSearchClient = EmptyClient(),
        ddg: WebSearchClient = EmptyClient(),
        baidu: WebSearchClient = EmptyClient(),
    ) = CompositeSearchClient(exa, bing, ddg, baidu)

    @Test
    fun `first non-empty engine in the chain wins`() = runTest {
        val exa = CountingClient(listOf(hit("exa")))
        val c = composite(exa = exa)

        val result = c.search("test")

        assertEquals(listOf(hit("exa")), result)
        assertEquals(1, exa.calls)
    }

    @Test
    fun `empty exa falls through to bing`() = runTest {
        val bing = CountingClient(listOf(hit("bing")))
        val c = composite(bing = bing)

        val result = c.search("test")

        assertEquals(listOf(hit("bing")), result)
        assertEquals(1, bing.calls)
    }

    @Test
    fun `middle engine in chain returns before later engines are called`() = runTest {
        val ddg = CountingClient(listOf(hit("ddg")))
        val baidu = CountingClient(listOf(hit("baidu")))
        val c = composite(ddg = ddg, baidu = baidu)

        val result = c.search("test")

        assertEquals(listOf(hit("ddg")), result)
        assertEquals(1, ddg.calls)
        assertEquals(0, baidu.calls)
    }

    @Test
    fun `last engine in the chain answers when all earlier are empty`() = runTest {
        val baidu = CountingClient(listOf(hit("baidu")))
        val c = composite(baidu = baidu)

        val result = c.search("test")

        assertEquals(listOf(hit("baidu")), result)
        assertEquals(1, baidu.calls)
    }

    @Test
    fun `all empty returns empty without throwing`() = runTest {
        val c = composite()

        assertEquals(emptyList<WebSearchHit>(), c.search("test"))
    }
}
