package com.sapphire.data.explore

import com.sapphire.domain.explore.FeedLinkHarvester
import com.sapphire.domain.explore.FeedSearchResult
import com.sapphire.domain.explore.WebSearchHit
import com.sapphire.domain.util.normalizeSourceUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * OkHttp + jsoup [FeedLinkHarvester]. Two parallel fan-out waves, both non-fatal:
 *
 * 1. **Extract** — for each SERP hit (top [PAGE_LIMIT]): a feed-shaped hit URL becomes
 *    a direct candidate (verification confirms it), AND the page itself is fetched
 *    (bounded to [PAGE_BYTES]) — feed-shaped SERP hits are frequently *directory index*
 *    pages (`…/rss/`, `…/feeds/`) whose anchor lists are the real payload. From the page,
 *    candidates are harvested in signal order — `<link rel="alternate">` with an
 *    rss/atom/json type, then anchor hrefs with feed-shaped paths. Only when neither the
 *    direct shape nor the page exposed anything are the well-known feed paths probed
 *    (`/feed`, `/rss.xml`, `/atom.xml`, `/feeds/posts/default`). Candidates carry the
 *    hit's SERP position as rank.
 * 2. **Verify** — every shortlisted candidate (deduped by [normalizeSourceUrl], capped
 *    per host and globally) is fetched (bounded to [SNIFF_BYTES]) and content-sniffed:
 *    the first `<rss`/`<feed` root before any HTML tag makes an RSS/Atom feed, a `{`
 *    payload with `items`/`feed_url` makes a JSON Feed; anything else is dropped. Title
 *    and description come from the feed itself.
 *
 * The shared [OkHttpClient] has 15s/30s connect/read timeouts sized for single calls;
 * this class derives a [CALL_TIMEOUT_SECONDS]-capped client so one slow page cannot hold
 * the fan-out hostage.
 */
class HttpFeedLinkHarvester @Inject constructor(
    client: OkHttpClient,
) : FeedLinkHarvester {

    private val client: OkHttpClient = client.newBuilder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * A candidate feed URL plus the SERP rank of the page it came from. [direct] marks
     * the hit URL itself when it is feed-shaped (or its fetched body sniffs as a feed) —
     * the source's own endorsement, exempt from the per-host cap and eligible for
     * unreachable pass-through. [curatedTitle]/[curatedDescription] carry the source's
     * own metadata, used when the feed cannot be fetched to verify.
     */
    private data class Candidate(
        val url: String,
        val rank: Int,
        val direct: Boolean = false,
        val curatedTitle: String? = null,
        val curatedDescription: String? = null,
    )

    /** Bounded fetch outcome — distinguishes "not a feed" from "cannot check". */
    private sealed interface Fetched {
        class Body(val text: String) : Fetched
        data object HttpFailure : Fetched
        data object Unreachable : Fetched
    }

    /** Verified result paired with its rank for final ordering. */
    private data class Ranked(val rank: Int, val result: FeedSearchResult)
    override suspend fun harvest(hits: List<WebSearchHit>): List<FeedSearchResult> =
        withContext(Dispatchers.IO) {
            // Spend the page budget on the highest-signal hits first: feed-shaped hit
            // URLs (a directory like `…/rss/`, or a bare feed) yield the most verified
            // feeds, so they must not lose fetch slots to generic SERP filler. Stable
            // sort keeps original SERP order within a signal tier; ranks are reindexed
            // so final result order follows signal first, SERP position second.
            val pages = hits
                .sortedByDescending { feedSignal(it) }
                .take(PAGE_LIMIT)
            val candidates = coroutineScope {
                pages.mapIndexed { rank, hit ->
                    async { runCatching { candidatesFor(hit, rank) }.getOrDefault(emptyList()) }
                }.awaitAll().flatten()
            }
            val shortlist = shortlist(candidates)
            if (shortlist.isEmpty()) return@withContext emptyList()
            coroutineScope {
                shortlist.map { candidate ->
                    async { runCatching { verify(candidate) }.getOrNull() }
                }.awaitAll()
            }
                .filterNotNull()
                .sortedBy { it.rank } // stable: keeps in-page order among equal ranks
                .take(RESULT_LIMIT)
                .map { it.result }
        }

    // ---------- wave 1: extraction ----------

    private fun candidatesFor(hit: WebSearchHit, rank: Int): List<Candidate> {
        val hitUrl = runCatching { hit.url.toHttpUrl() }.getOrNull() ?: return emptyList()
        if (hitUrl.scheme !in listOf("http", "https")) return emptyList()

        // A SERP hit that already looks like a feed is still fetched as a page: many
        // such hits are directory indexes (`…/rss/`) listing real feeds, and a plain
        // "maybe-feed" URL is cheap to verify later either way.
        val direct = if (FEED_PATH.containsMatchIn(hitUrl.encodedPath)) {
            listOf(Candidate(hit.url, rank, direct = true, curatedTitle = hit.title, curatedDescription = hit.content))
        } else {
            emptyList()
        }

        val html = when (val page = fetchBounded(hit.url, PAGE_BYTES)) {
            is Fetched.Body -> page.text
            // Unreachable/HTTP-error page fetch: fall back to whatever the hit itself
            // endorses (its URL shape); nothing more can be extracted here.
            Fetched.HttpFailure, Fetched.Unreachable -> return direct
        }

        // A "page" whose body sniffs as a feed (e.g. `…/china?format=rss` — a feed with
        // no feed-shaped PATH): the hit URL is the feed itself.
        if (sniffKind(html.take(SNIFF_WINDOW)) != null) {
            return listOf(Candidate(hit.url, rank, direct = true, curatedTitle = hit.title, curatedDescription = hit.content))
        }
        val doc = runCatching { Jsoup.parse(html, hit.url) }.getOrNull() ?: return direct

        val extracted = buildList {
            // Highest signal: the page's own declared feeds.
            doc.select("link[rel=alternate]").forEach { link ->
                val type = link.attr("type").lowercase()
                if (type.contains("rss") || type.contains("atom") || type.contains("json")) {
                    add(link.absUrl("href"))
                }
            }
            // Then feed-shaped links anywhere in the body (directories, blogrolls).
            doc.select("a[href]").forEach { a ->
                val abs = a.absUrl("href")
                val path = runCatching { abs.toHttpUrl().encodedPath }.getOrNull() ?: return@forEach
                if (FEED_PATH.containsMatchIn(path)) add(abs)
            }
        }
            .filter { it.startsWith("http") }

        return when {
            extracted.isNotEmpty() -> extracted.map { Candidate(it, rank) } + direct
            // Page fetched fine but declared nothing — probe the well-known paths
            // (host-root), but only when the hit itself gave us nothing to verify.
            direct.isEmpty() -> WELL_KNOWN_PATHS.map { path ->
                Candidate(hitUrl.newBuilder().encodedPath(path).build().toString(), rank)
            }
            else -> direct
        }
    }

    /**
     * Dedup by normalized URL, cap extracted/probed candidates per host (one directory
     * must not monopolize), cap globally. Direct candidates bypass the host cap.
     */
    private fun shortlist(candidates: List<Candidate>): List<Candidate> {
        val deduped = candidates
            .filter { normalizeSourceUrl(it.url).isNotBlank() }
            .distinctBy { normalizeSourceUrl(it.url) }
        val perHost = mutableMapOf<String, Int>()
        return deduped.filter { candidate ->
            if (candidate.direct) return@filter true
            val host = normalizeSourceUrl(candidate.url).substringBefore('/')
            val count = perHost.getOrDefault(host, 0)
            if (count >= HOST_CAP) return@filter false
            perHost[host] = count + 1
            true
        }.take(CANDIDATE_LIMIT)
    }

    /**
     * How likely a SERP hit is to yield subscribable feeds, used to spend the page
     * budget well: 2 = feed-shaped URL path (a feed or a feed directory index), 1 =
     * rss/feed/subscribe mentioned in the host, title, or snippet, 0 = anything else.
     */
    private fun feedSignal(hit: WebSearchHit): Int {
        val path = runCatching { hit.url.toHttpUrl().encodedPath }.getOrNull() ?: return 0
        if (FEED_PATH.containsMatchIn(path)) return 2
        val haystack = "${hit.url} ${hit.title} ${hit.content}".lowercase()
        return if ("rss" in haystack || "feed" in haystack || "subscribe" in haystack) 1 else 0
    }

    // ---------- wave 2: verification ----------

    private fun verify(candidate: Candidate): Ranked? {
        return when (val fetched = fetchBounded(candidate.url, SNIFF_BYTES)) {
            is Fetched.Body -> {
                val head = fetched.text.take(SNIFF_WINDOW)
                val kind = sniffKind(head) ?: return null // fetched fine, but NOT a feed
                Ranked(
                    rank = candidate.rank,
                    result = FeedSearchResult(
                        title = extractTitle(head).ifBlank {
                            candidate.curatedTitle ?: fallbackTitle(candidate.url)
                        },
                        url = candidate.url,
                        kind = kind,
                        description = extractDescription(head)?.take(DESCRIPTION_CAP)
                            ?: candidate.curatedDescription?.take(DESCRIPTION_CAP),
                    ),
                )
            }
            // Definitively rejected: the host answered and said no.
            Fetched.HttpFailure -> null
            // Cannot check (blocked/unreachable network path). A direct, curated hit
            // passes through with its own metadata — unreachable here does not mean
            // not a feed, and the source already vouches for it. Health-state flags
            // it later if it is genuinely dead.
            Fetched.Unreachable -> {
                val title = candidate.curatedTitle ?: return null
                Ranked(
                    rank = candidate.rank,
                    result = FeedSearchResult(
                        title = title,
                        url = candidate.url,
                        kind = inferKind(candidate.url),
                        description = candidate.curatedDescription?.take(DESCRIPTION_CAP),
                    ),
                )
            }
        }
    }

    /** URL-shape kind guess for feeds whose content could not be fetched. */
    private fun inferKind(url: String): String = when {
        "atom" in url.lowercase() -> "atom"
        "json" in url.lowercase() -> "json"
        else -> "rss"
    }

    /**
     * Content sniff on the response head. A feed's root element must appear before any
     * HTML structural tag — that ordering check rejects HTML pages (sitemaps, error
     * pages, RSS *about* pages) even though they may contain the literal "<feed".
     */
    private fun sniffKind(head: String): String? {
        val htmlIdx = listOf("<html", "<body", "<div", "<!doctype", "<script")
            .map { head.indexOf(it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull() ?: Int.MAX_VALUE
        val rssIdx = head.indexOf("<rss", ignoreCase = true)
        val atomIdx = head.indexOf("<feed", ignoreCase = true)
        return when {
            rssIdx in 0 until htmlIdx -> "rss"
            atomIdx in 0 until htmlIdx -> "atom"
            head.trimStart().startsWith("{") &&
                (head.contains("\"items\"") || head.contains("\"feed_url\"")) -> "json"
            else -> null
        }
    }

    private fun extractTitle(head: String): String {
        XML_TITLE.find(head)?.groupValues?.getOrNull(1)
            ?.let { return cleanFeedText(it) }
        JSON_TITLE.find(head)?.groupValues?.getOrNull(1)
            ?.let { return it.replace("\\\"", "\"").replace("\\\\", "\\") }
        return ""
    }

    private fun extractDescription(head: String): String? =
        XML_DESCRIPTION.find(head)?.groupValues?.getOrNull(1)?.let { cleanFeedText(it) }

    /** Unwrap CDATA and decode entities — feed titles are frequently `&amp;`-encoded. */
    private fun cleanFeedText(raw: String): String {
        val unwrapped = raw.trim().removePrefix("<![CDATA[").removeSuffix("]]>")
        return Parser.unescapeEntities(unwrapped, false).trim()
    }

    /** Same host+path derivation as the URL-paste shortcut, for feeds with no parsable title. */
    private fun fallbackTitle(raw: String): String {
        val url = runCatching { raw.toHttpUrl() }.getOrNull() ?: return raw
        val path = url.encodedPath.takeIf { it.isNotEmpty() && it != "/" } ?: ""
        return url.host.lowercase() + path
    }

    // ---------- shared HTTP ----------

    /** GET [url], reading at most [limit] bytes, distinguishing failure modes. */
    private fun fetchBounded(url: String, limit: Int): Fetched {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SearchHttp.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@use Fetched.HttpFailure
                val stream = res.body?.byteStream() ?: return@use Fetched.HttpFailure
                // Manual bounded read — InputStream.readNBytes is Java 11 API, absent on
                // Android API < 33 (minSdk 29); it would fail as a silent NoSuchMethodError
                // and every candidate would look unreachable.
                val buffer = ByteArray(limit)
                var offset = 0
                while (offset < buffer.size) {
                    val read = stream.read(buffer, offset, buffer.size - offset)
                    if (read < 0) break
                    offset += read
                }
                Fetched.Body(String(buffer, 0, offset, Charsets.UTF_8))
            }
        } catch (_: IOException) {
            Fetched.Unreachable
        }
    }

    private companion object {
        const val PAGE_LIMIT = 8
        const val RESULT_LIMIT = 8
        const val HOST_CAP = 3
        const val CANDIDATE_LIMIT = 14
        const val CALL_TIMEOUT_SECONDS = 10L
        const val PAGE_BYTES = 256 * 1024
        const val SNIFF_BYTES = 64 * 1024
        const val DESCRIPTION_CAP = 200

        /** Characters of the fetched feed examined for sniffing/title/description. */
        const val SNIFF_WINDOW = 2048

        /** Feed-shaped URL paths: /feed, /rss, /atom segments or .rss/.xml/.atom files. */
        val FEED_PATH = Regex("""(^|/)(feeds?|rss|atom)(/|$)|\.(rss|xml|atom)$""", RegexOption.IGNORE_CASE)

        val WELL_KNOWN_PATHS = listOf("/feed", "/rss.xml", "/atom.xml", "/feeds/posts/default")

        // [\s\S] because feed titles may wrap lines inside CDATA blocks.
        val XML_TITLE = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
        val XML_DESCRIPTION = Regex("""<(?:subtitle|description)[^>]*>([\s\S]*?)</(?:subtitle|description)>""", RegexOption.IGNORE_CASE)
        val JSON_TITLE = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    }
}
