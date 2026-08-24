package com.sapphire.data.explore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Shared HTTP scaffolding for the keyless search engines: IO dispatch, execution, status
 * check, and the non-fatal collapse mandated by [WebSearchClient] (any failure → null →
 * the adapter returns empty). Engines keep only what genuinely differs per engine:
 * endpoint, headers, HTTP method, and parse.
 */
internal object SearchHttp {

    /** Desktop Chrome UA the HTML engines expect. One copy for all engines. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Executes [request] on IO and returns the non-blank body string, or null on any
     * failure (IO error, non-2xx status, blank body). Never throws.
     */
    suspend fun bodyOrNull(client: OkHttpClient, request: Request): String? =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { res ->
                    if (!res.isSuccessful) return@use null
                    res.body?.string()?.takeIf { it.isNotBlank() }
                }
            } catch (_: IOException) {
                null
            } catch (_: Throwable) {
                null
            }
        }
}
