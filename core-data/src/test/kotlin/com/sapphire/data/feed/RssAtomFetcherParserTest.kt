package com.sapphire.data.feed

import com.sapphire.domain.feed.FetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the REAL [RssAtomFetcher] parser against captured feed XML (Robolectric is
 * required: XmlPullParserFactory is an Android class stubbed out in plain JVM unit tests).
 * The FeedRefreshServiceTest suite substitutes the Fetcher entirely, so a parser
 * regression (CDATA handling, namespaced fields like dc:creator) is otherwise invisible.
 */
@RunWith(RobolectricTestRunner::class)
class RssAtomFetcherParserTest {

    private val fetcher = RssAtomFetcher(okhttp3.OkHttpClient())

    private fun loadFixture(name: String): String =
        javaClass.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun parses_hn_frontpage_with_cdata_titles() {
        val xml = loadFixture("/hn-frontpage.xml")
        val res = fetcher.parse(xml) as FetchResult.Success
        assertEquals(2, res.items.size)
        val first = res.items.first()
        assertEquals("Raspberry Pi Pico W as USB Wi-Fi Adapter", first.title)
        assertEquals("https://gitlab.com/baiyibai/pico-usb-wifi", first.canonicalUrl)
        assertEquals("byb", first.authorHandle)
        val publishedAt = first.publishedAt
        assertNotNull(publishedAt)
        assertTrue(publishedAt != null && publishedAt > 0L)
    }
}
