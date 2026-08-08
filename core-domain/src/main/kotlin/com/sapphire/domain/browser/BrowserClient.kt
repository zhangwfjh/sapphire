package com.sapphire.domain.browser

/**
 * A page-rendering port for the agent's `fetch_page` tool. Pure-domain contract; the C2
 * HTTP implementation lives in core-data. Mirrors [com.sapphire.domain.explore.WebSearchClient]:
 * non-fatal — returns [RenderResult.failed] on any error or when no service is configured,
 * so the agent loop degrades gracefully rather than crashing.
 *
 * The default C2 implementation POSTs to a user-configured browser-service base URL (the
 * RSSHub self-hosting pattern); a future C1 on-device-WebView impl slots behind the same
 * interface.
 */
interface BrowserClient {
    suspend fun render(request: RenderRequest): RenderResult
}

data class RenderRequest(
    val url: String,
    val waitUntil: WaitUntil = WaitUntil.NETWORKIDLE,
    val extractLinks: Boolean = true,
)

/** How long the render service waits before extracting content. Maps to the service's wait param. */
enum class WaitUntil { DOMCONTENTLOADED, LOAD, NETWORKIDLE }

data class RenderResult(
    val ok: Boolean,
    val title: String? = null,
    /** Cleaned visible text — what the LLM reads. Truncated by the caller before feeding back. */
    val text: String? = null,
    val links: List<LinkRef> = emptyList(),
    val error: String? = null,
) {
    companion object {
        fun failed(reason: String) = RenderResult(ok = false, error = reason)
        fun notConfigured() = RenderResult(ok = false, error = "browser service not configured")
    }
}

data class LinkRef(val text: String, val url: String)
