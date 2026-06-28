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

    private fun rssItem(body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"
             xmlns:content="http://purl.org/rss/1.0/modules/content/"
             xmlns:media="http://search.yahoo.com/mrss/"
             xmlns:dc="http://purl.org/dc/elements/1.1/">
          <channel><title>t</title><link>l</link><description>d</description>
            <item>
              <title>Title</title>
              <link>https://x/post</link>
              $body
              <pubDate>Thu, 03 Oct 2024 10:00:00 GMT</pubDate>
            </item>
          </channel>
        </rss>"""

    private fun mediaUrlOf(xml: String): String? =
        ((fetcher.parse(xml) as FetchResult.Success).items.firstOrNull()?.mediaUrl)

    @Test
    fun extracts_media_from_enclosure() {
        val xml = rssItem(
            """<enclosure url="https://x.com/cover.jpg" length="1234" type="image/jpeg" />""",
        )
        assertEquals("https://x.com/cover.jpg", mediaUrlOf(xml))
    }

    @Test
    fun extracts_media_from_media_content() {
        val xml = rssItem(
            """<media:content url="https://x.com/pic.png" medium="image" />""",
        )
        assertEquals("https://x.com/pic.png", mediaUrlOf(xml))
    }

    @Test
    fun extracts_media_from_media_thumbnail() {
        val xml = rssItem("""<media:thumbnail url="https://x.com/thumb.webp" />""")
        assertEquals("https://x.com/thumb.webp", mediaUrlOf(xml))
    }

    @Test
    fun falls_back_to_first_img_in_content_encoded() {
        val xml = rssItem(
            """<content:encoded><![CDATA[<p><img src="https://x.com/body.jpg" alt="c"/></p>]]></content:encoded>""",
        )
        assertEquals("https://x.com/body.jpg", mediaUrlOf(xml))
    }

    @Test
    fun enclosure_beats_media_content_and_thumbnail_and_img() {
        val xml = rssItem(
            """
            <media:content url="https://x.com/second.png" medium="image" />
            <media:thumbnail url="https://x.com/third.webp" />
            <enclosure url="https://x.com/first.jpg" type="image/jpeg" />
            <content:encoded><![CDATA[<img src="https://x.com/fourth.jpg"/>]]></content:encoded>
            """.trimIndent(),
        )
        assertEquals("https://x.com/first.jpg", mediaUrlOf(xml))
    }

    @Test
    fun audio_enclosure_is_ignored_and_falls_through_to_img() {
        val xml = rssItem(
            """
            <enclosure url="https://x.com/ep.mp3" length="9" type="audio/mpeg" />
            <content:encoded><![CDATA[<img src="https://x.com/shownote.jpg"/>]]></content:encoded>
            """.trimIndent(),
        )
        assertEquals("https://x.com/shownote.jpg", mediaUrlOf(xml))
    }

    @Test
    fun media_content_with_url_does_not_clobber_body_content() {
        // Regression: previously media:content (relaxed name "content") was read via
        // nextText() and overwrote the body. Now the url attr path leaves bodyRaw intact.
        val xml = rssItem(
            """<media:content url="https://x.com/img.png" medium="image" />
            <content:encoded><![CDATA[<p>real body</p>]]></content:encoded>""",
        )
        val item = (fetcher.parse(xml) as FetchResult.Success).items.first()
        assertEquals("https://x.com/img.png", item.mediaUrl)
        assertEquals("<p>real body</p>", item.bodyRaw)
    }

    @Test
    fun atom_entry_extracts_image_enclosure_link() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>t</title>
          <entry>
            <title>Title</title>
            <link href="https://x.com/post" />
            <link rel="enclosure" type="image/jpeg" href="https://x.com/atom.jpg" />
            <published>2024-10-03T10:00:00Z</published>
          </entry>
        </feed>"""
        val item = (fetcher.parse(xml) as FetchResult.Success).items.first()
        assertEquals("https://x.com/atom.jpg", item.mediaUrl)
        assertEquals("https://x.com/post", item.canonicalUrl)
    }
 }
