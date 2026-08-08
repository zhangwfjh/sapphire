package com.sapphire.domain.browser

import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for the browser-service base URL (the RSSHub-shaped self-hosted
 * render endpoint used by the agent's `fetch_page` tool). Implementations live in core-data.
 *
 * - [baseUrl] is a hot-path sync snapshot used by [BrowserClient] once per render.
 * - [observeBaseUrl] is a hot flow so the Settings UI reacts to runtime edits.
 * - Empty base URL = no browser service configured → the agent degrades to readability-only
 *   fetch → search-snippet fallback. A missing browser service never hard-fails an agent run.
 */
interface BrowserConfig {
    /** Configured browser-service base URL (must end with '/'), or "" if unconfigured. */
    fun baseUrl(): String
    fun observeBaseUrl(): Flow<String>
    suspend fun setBaseUrl(baseUrl: String)
}
