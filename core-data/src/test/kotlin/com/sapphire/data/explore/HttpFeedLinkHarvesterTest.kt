package com.sapphire.data.explore

import com.sapphire.domain.explore.WebSearchHit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * HttpFeedLinkHarvester — deterministic extract + verify, all HTTP via MockWebServer and
 * a path-routed dispatcher (page fetches and feed fetches share one host, exactly like a
 * real SERP hit). Covers: alternate/anchor/well-known extraction, direct feed-shaped
 * hits, kind sniffing (rss/atom/json), title/description from the feed itself, per-host
 * capping, SERP-rank ordering, and non-fatal collapse on non-feed responses.
 */
class HttpFeedLinkHarvesterTest {

    private lateinit var server: MockWebServer
    private val requestedPaths = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestedPaths.add(request.path.orEmpty())
                return route(request.path.orEmpty())
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun harvester() = HttpFeedLinkHarvester(OkHttpClient())

    private fun hit(path: String) = WebSearchHit(title = "hit $path", url = server.url(path).toString(), content = "")

    private fun url(path: String) = server.url(path).toString()

    private fun route(path: String): MockResponse = when (path.substringBefore('?')) {
        "/blog/" -> html(
            """
            <html><head>
              <link rel="alternate" type="application/rss+xml" href="/blog/feed.xml">
              <link rel="alternate" type="application/atom+xml" href="/blog/atom.xml">
            </head><body></body></html>
            """.trimIndent(),
        )
        "/blog/feed.xml" -> xml("""<rss version="2.0"><channel><title>AI &amp; ML Feed</title><description>machine learning</description></channel></rss>""")
        "/blog/atom.xml" -> xml("""<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom Feed</title><subtitle>atoms</subtitle></feed>""")
        "/json/feed.json" -> MockResponse().setBody(
            """{"version":"https://jsonfeed.org/version/1","title":"JSON Feed","items":[{"id":"1"}]}""",
        )
        "/links/" -> html("""<html><body><a href="/links/rss">Subscribe</a></body></html>""")
        "/links/rss" -> xml("""<rss version="2.0"><channel><title>Links Feed</title></channel></rss>""")
        "/bare/" -> html("""<html><head><title>A blog with no feed links</title></head><body></body></html>""")
        "/feed" -> xml("""<rss version="2.0"><channel><title>Probed Feed</title></channel></rss>""")
        "/p1/" -> html("""<html><head><link rel="alternate" type="application/rss+xml" href="/p1/a"></head></html>""")
        "/p1/a" -> xml("""<rss version="2.0"><channel><title>First</title></channel></rss>""")
        "/p2/" -> html("""<html><head><link rel="alternate" type="application/rss+xml" href="/p2/b"></head></html>""")
        "/p2/b" -> xml("""<rss version="2.0"><channel><title>Second</title></channel></rss>""")
        "/direct.xml" -> xml("""<rss version="2.0"><channel><title>Direct Hit</title></channel></rss>""")
        "/htmlfake.xml" -> html("""<html><body><p>talks about &lt;rss&gt; feeds</p></body></html>""")
        else -> MockResponse().setResponseCode(404).setBody("not found")
    }

    private fun xml(body: String) = MockResponse()
        .setHeader("Content-Type", "application/xml")
        .setBody("""<?xml version="1.0" encoding="UTF-8"?>$body""")

    private fun html(body: String) = MockResponse()
        .setHeader("Content-Type", "text/html")
        .setBody(body)

    @Test
    fun `link alternates are extracted, verified, and titled from the feed itself`() = runTest {
        val results = harvester().harvest(listOf(hit("/blog/")))

        assertEquals(2, results.size)
        assertEquals(url("/blog/feed.xml"), results[0].url)
        assertEquals("rss", results[0].kind)
        assertEquals("AI & ML Feed", results[0].title) // &amp; entity decoded
        assertEquals("machine learning", results[0].description)
        assertEquals(url("/blog/atom.xml"), results[1].url)
        assertEquals("atom", results[1].kind)
        assertEquals("Atom Feed", results[1].title)
        assertEquals("atoms", results[1].description)
    }

    @Test
    fun `json feed alternate is sniffed as json`() = runTest {
        // Path-routed inline: any non-feed path serves the page declaring a JSON Feed
        // alternate; /feed.json serves the feed itself.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/feed.json") route("/json/feed.json")
                else html("""<html><head><link rel="alternate" type="application/feed+json" href="/feed.json"></head></html>""")
        }

        val results = HttpFeedLinkHarvester(OkHttpClient())
            .harvest(listOf(WebSearchHit("t", url("/page/"), "")))

        assertEquals(1, results.size)
        assertEquals("json", results[0].kind)
        assertEquals("JSON Feed", results[0].title)
    }

    @Test
    fun `feed-shaped anchor hrefs are harvested when no alternate exists`() = runTest {
        val results = harvester().harvest(listOf(hit("/links/")))

        assertEquals(1, results.size)
        assertEquals(url("/links/rss"), results[0].url)
        assertEquals("Links Feed", results[0].title)
    }

    @Test
    fun `page with no candidates probes well-known feed paths`() = runTest {
        val results = harvester().harvest(listOf(hit("/bare/")))

        assertEquals(1, results.size)
        assertEquals(url("/feed"), results[0].url)
        assertEquals("Probed Feed", results[0].title)
    }

    @Test
    fun `feed-shaped hit url is fetched as a page and verified as a direct candidate`() = runTest {
        requestedPaths.clear()

        val results = harvester().harvest(listOf(hit("/direct.xml")))

        assertEquals(1, results.size)
        assertEquals("Direct Hit", results[0].title)
        // Page fetch + verification both hit the feed URL — the fetch is the point:
        // feed-shaped SERP hits are often directory indexes whose anchors are the payload.
        assertEquals(listOf("/direct.xml", "/direct.xml"), requestedPaths)
    }

    @Test
    fun `feed-shaped directory hit is harvested and its anchor feeds verified`() = runTest {
        // Real-world regression: chinanews.com.cn/rss/ — a feed-SHAPED hit that is an
        // HTML index listing many .xml feeds. The direct candidate sniff-drops as HTML,
        // the anchors verify.
        val index = (1..4).joinToString("\n") {
            """<a href="/rss/$it.xml">https://example.com/rss/$it.xml</a>"""
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/rss/" -> html("""<html><body><ul>$index</ul></body></html>""")
                else -> xml("""<rss version="2.0"><channel><title>Feed ${request.path}</title></channel></rss>""")
            }
        }

        val results = HttpFeedLinkHarvester(OkHttpClient())
            .harvest(listOf(WebSearchHit("dir", url("/rss/"), "")))

        // 4 anchor candidates, host cap 3 → three verified feeds; the index itself drops.
        assertEquals(3, results.size)
        assertEquals(url("/rss/1.xml"), results[0].url)
        assertEquals("Feed /rss/1.xml", results[0].title)
    }

    @Test
    fun `results keep SERP rank order across pages`() = runTest {
        val results = harvester().harvest(listOf(hit("/p2/"), hit("/p1/")))

        assertEquals(2, results.size)
        // p2 is SERP rank 0, p1 rank 1 — rank order wins, not feed title order.
        assertEquals("Second", results[0].title)
        assertEquals("First", results[1].title)
    }

    @Test
    fun `non-feed responses are dropped during verification`() = runTest {
        // /htmlfake.xml is feed-shaped but serves HTML (sniff rejects); /gone.xml is a
        // feed-shaped 404 — both must collapse to empty, not throw, not pass through.
        val results = harvester().harvest(listOf(hit("/htmlfake.xml"), hit("/gone.xml")))

        assertTrue(results.isEmpty())
    }

    @Test
    fun `per-host cap limits one directory page to three feeds`() = runTest {
        val five = (1..5).joinToString(" ") {
            """<link rel="alternate" type="application/rss+xml" href="/dir/$it.xml">"""
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/dir/") html("""<html><head>$five</head></html>""")
                else xml("""<rss version="2.0"><channel><title>Feed ${request.path}</title></channel></rss>""")
        }

        val results = HttpFeedLinkHarvester(OkHttpClient())
            .harvest(listOf(WebSearchHit("dir", url("/dir/"), "")))

        assertEquals(3, results.size)
    }

    @Test
    fun `feed-shaped hit outranks generic filler for the page budget`() = runTest {
        // Regression shape from a real 'china' search: one excellent feed-directory hit
        // buried under more generic hits than the page budget (8). Signal ranking must
        // spend a slot on the directory, not lose it to filler.
        val filler = (1..9).map { WebSearchHit("generic $it", url("/g$it/"), "") }
        val directory = WebSearchHit("china rss index", url("/rss/"), "")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/rss/" -> html(
                    """<html><body>
                    <a href="/rss/a.xml">a</a><a href="/rss/b.xml">b</a><a href="/rss/c.xml">c</a>
                    </body></html>""",
                )
                request.path.orEmpty().endsWith(".xml") ->
                    xml("""<rss version="2.0"><channel><title>Feed ${request.path}</title></channel></rss>""")
                else -> MockResponse().setResponseCode(404).setBody("not found")
            }
        }

        val results = HttpFeedLinkHarvester(OkHttpClient())
            .harvest(filler + directory)

        assertEquals(3, results.size)
        assertEquals(url("/rss/a.xml"), results[0].url)
    }

    @Test
    fun `unreachable direct hit passes through with curated metadata`() = runTest {
        // Real-world shape: rssfinder returns BBC/FT/Guardian feeds, but from a network
        // where those hosts are blocked the fetch dies with IOException — that must not
        // censor a curated feed. Port 1 on localhost is never bound → connect refused.
        val blocked = WebSearchHit(
            title = "BBC News - China",
            url = "http://127.0.0.1:1/news/world/asia/china/rss.xml",
            content = "BBC News - China",
        )

        val results = harvester().harvest(listOf(blocked))

        assertEquals(1, results.size)
        assertEquals("BBC News - China", results[0].title)
        assertEquals("http://127.0.0.1:1/news/world/asia/china/rss.xml", results[0].url)
        assertEquals("rss", results[0].kind)
        assertEquals("BBC News - China", results[0].description)
    }

    @Test
    fun `feed body at a non-feed-shaped path becomes a direct candidate`() = runTest {
        // FT shape: https://www.ft.com/world/asia-pacific/china?format=rss — the feed is
        // keyed by QUERY, so the path heuristic misses it; the body sniff must not.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                xml("""<rss version="2.0"><channel><title>FT China</title></channel></rss>""")
        }

        val results = HttpFeedLinkHarvester(OkHttpClient())
            .harvest(listOf(WebSearchHit("FT China", url("/world/asia-pacific/china?page=2&format=rss"), "ft")))

        assertEquals(1, results.size)
        assertEquals("FT China", results[0].title)
        assertEquals("rss", results[0].kind)
    }

    @Test
    fun `empty hits yield empty results`() = runTest {
        assertTrue(harvester().harvest(emptyList()).isEmpty())
    }
}
