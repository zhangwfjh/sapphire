package com.sapphire.data.feed

import com.sapphire.data.db.FeedDao
import com.sapphire.data.db.FeedItemEntity
import com.sapphire.domain.feed.FetchResult
import com.sapphire.domain.feed.Fetcher
import com.sapphire.domain.feed.FeedItemCandidate
import com.sapphire.domain.model.HealthState
import com.sapphire.domain.model.SourceKind
import com.sapphire.domain.util.FeedItemId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Holds the fetcher for each supported [SourceKind]. A plain holder avoids Dagger map
 * multibinding (whose variance handling across modules is fiddly); the [Fetcher]s are still
 * injected individually and indexed at construct time.
 *
 * Tests build one via [forTesting] with fake fetchers; production uses the `@Inject`
 * constructor with the real RSS/JSON fetchers.
 */
class FetcherRegistry private constructor(
    private val byKind: Map<SourceKind, Fetcher>,
) {
    @Inject constructor(
        rssAtom: RssAtomFetcher,
        jsonFeed: JsonFeedFetcher,
    ) : this(mapOf(
        SourceKind.RSS to rssAtom,
        SourceKind.ATOM to rssAtom,
        SourceKind.JSON to jsonFeed,
    ))

    fun forKind(kind: SourceKind): Fetcher? = byKind[kind]

    companion object {
        /** Build a registry from an explicit kind→fetcher map (tests). */
        fun forTesting(entries: Map<SourceKind, Fetcher>): FetcherRegistry =
            FetcherRegistry(entries)
    }
}

/**
 * Ingest pipeline (non-agent path):
 *
 *   Source ──► Fetcher(kind) ──► [FeedItemCandidate] ──► hash + categoryId + fetchedAt
 *                  ──► FeedItemEntity ──► INSERT OR IGNORE (PK = hash_uuid)
 *
 * Dedup is the cheap hash layer only (global id). Semantic embedding dedup (τ≈0.88)
 * runs *only* on the AGENT_SEARCH path — RSS/social items are already curated by their
 * source, reranking them would be noise.
 *
 * Source health is stamped on every fetch: OK on success, FAILED on persistent parse/4xx.
 * Transient (network/5xx) leaves health untouched — the route may have just blipped.
 */
class FeedRefreshService @Inject constructor(
    private val fetchers: FetcherRegistry,
    private val feedDao: FeedDao,
    private val sources: SourceFeedQuery,
) {

    /**
     * Aggregated across all sources in one refresh pass. [sourceCount],
     * [skippedNoFetcher], and [fetchedSources] let the caller distinguish the three
     * "0 new" cases: no sources configured, all skipped (AGENT with no fetcher),
     * or fetched-but-empty.
     */
    data class RefreshOutcome(
        val totalNew: Int,
        val errors: List<String>,
        val sourceCount: Int = 0,
        val fetchedSources: Int = 0,
        val skippedNoFetcher: Int = 0,
    )

    /**
     * Aggregated refresh — concurrent twin of [refreshStreaming]. Fans out one coroutine
     * per source on IO (total wall-time ≈ the slowest source, not the sum) and folds the
     * per-source outcomes into a single [RefreshOutcome]. Kept for callers that want a
     * one-shot aggregate rather than a live event stream.
     */
    suspend fun refreshAll(): RefreshOutcome = coroutineScope {
        val all = try {
            withContext(Dispatchers.IO) { sources.allSources() }
        } catch (t: Throwable) {
            return@coroutineScope RefreshOutcome(
                totalNew = 0,
                errors = listOf(t.message ?: "source query failed"),
                sourceCount = 0,
            )
        }
        if (all.isEmpty()) return@coroutineScope RefreshOutcome(
            totalNew = 0, errors = emptyList(), sourceCount = 0,
        )

        val results = all.map { source ->
            async(Dispatchers.IO) {
                val event = try {
                    fetchSourceEvent(source)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    StreamEvent.SourceError(source.id, t.message ?: "unknown error")
                }
                FetchAttempt(fetchers.forKind(source.kind) != null, source.url, event)
            }
        }.awaitAll()

        var totalNew = 0
        var fetchedSources = 0
        var skippedNoFetcher = 0
        val errors = mutableListOf<String>()
        for (r in results) {
            when (r.event) {
                is StreamEvent.SourceDone -> {
                    totalNew += r.event.newCount
                    if (r.hadFetcher) fetchedSources++ else skippedNoFetcher++
                }
                is StreamEvent.SourceError -> errors += "${r.url}: ${r.event.message}"
                StreamEvent.AllDone -> Unit
            }
        }
        RefreshOutcome(
            totalNew = totalNew,
            errors = errors,
            sourceCount = all.size,
            fetchedSources = fetchedSources,
            skippedNoFetcher = skippedNoFetcher,
        )
    }

    private data class FetchAttempt(
        val hadFetcher: Boolean,
        val url: String,
        val event: StreamEvent,
    )

    /**
     * Streaming variant: fetches sources concurrently (bounded) and emits a [StreamEvent]
     * per source as it completes, so newly fetched items appear in the live timeline
     * immediately rather than after the whole pass finishes. Silent — the caller surfaces
     * no "N new" snackbar; the timeline itself is the feedback.
     *
     * Concurrency is capped to avoid hammering a single host or exhausting memory; each
     * source's persist + health update is still sequential within its own coroutine.
     */
    sealed interface StreamEvent {
        /** A source finished; [newCount] items were newly inserted. */
        data class SourceDone(val sourceId: String, val newCount: Int) : StreamEvent
        /** A source failed (transient or persistent). */
        data class SourceError(val sourceId: String, val message: String) : StreamEvent
        /** All sources have been processed. */
        data object AllDone : StreamEvent
    }

    fun refreshStreaming(): kotlinx.coroutines.flow.Flow<StreamEvent> =
        kotlinx.coroutines.flow.channelFlow {
            // channelFlow gives a ProducerScope whose send() is thread-safe, so the
            // concurrent source workers below can each call send() from their own
            // coroutine without violating the cold-Flow single-emitter invariant that
            // a plain `flow {}` builder enforces. This is the canonical structure for
            // fanning out concurrent producers into a single output stream.
            val all = try {
                withContext(Dispatchers.IO) { sources.allSources() }
            } catch (t: Throwable) {
                // Cannot even read the source table — emit a synthetic error and stop.
                send(StreamEvent.SourceError("__query__", t.message ?: "source query failed"))
                send(StreamEvent.AllDone)
                return@channelFlow
            }
            if (all.isEmpty()) {
                send(StreamEvent.AllDone)
                return@channelFlow
            }
            // Launch one coroutine per source on IO; each maps its outcome to a
            // StreamEvent and sends it. A throw in any worker is contained to that
            // source (mapped to SourceError) so siblings keep going.
            val jobs = all.map { source ->
                launch(Dispatchers.IO) {
                    val event = try {
                        fetchSourceEvent(source)
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        StreamEvent.SourceError(source.id, t.message ?: "unknown error")
                    }
                    send(event)
                }
            }
            jobs.joinAll()
            send(StreamEvent.AllDone)
        }

    /** Fetch one source and map its outcome to a [StreamEvent] (does not emit). */
    private suspend fun fetchSourceEvent(source: com.sapphire.data.db.SourceEntity): StreamEvent {
        val fetcher = fetchers.forKind(source.kind)
            ?: return StreamEvent.SourceDone(source.id, 0)
        val now = System.currentTimeMillis()
        return when (val res = fetcher.fetch(source.url, source.configJson)) {
            is FetchResult.Success -> {
                val newCount = persist(source.id, source.categoryId, res.items, now)
                sources.updateSourceFetchState(source.id, HealthState.OK, now, errorAt = null)
                StreamEvent.SourceDone(source.id, newCount)
            }
            is FetchResult.TransientError -> StreamEvent.SourceError(source.id, res.message)
            is FetchResult.PersistentFailure -> {
                sources.updateSourceFetchState(source.id, HealthState.FAILED, now, errorAt = now)
                StreamEvent.SourceError(source.id, res.message)
            }
        }
    }

    /**
     * Maps candidates to entities, dedups by hash, inserts. Returns the count of newly
     * inserted rows (IGNORE'd PK conflicts don't count).
     */
    private suspend fun persist(
        sourceId: String,
        categoryId: String,
        candidates: List<FeedItemCandidate>,
        now: Long,
    ): Int {
        if (candidates.isEmpty()) return 0
        val entities = candidates.map { c ->
            val url = c.canonicalUrl
            val hash = if (!url.isNullOrBlank()) {
                FeedItemId.fromUrl(sourceId, url)
            } else {
                FeedItemId.fromTitleAndPublishedAt(sourceId, c.title, c.publishedAt)
            }
            FeedItemEntity(
                hashUuid = hash,
                sourceId = sourceId,
                categoryId = categoryId,
                title = c.title,
                summary = c.summary,
                bodyRaw = c.bodyRaw,
                authorHandle = c.authorHandle,
                publishedAt = c.publishedAt,
                fetchedAt = now,
                platformTag = c.platformTag,
                mediaUrl = c.mediaUrl,
                url = c.canonicalUrl,
            )
        }
        val rowIds = feedDao.insertItems(entities)
        return rowIds.count { it > 0L }
    }
}
