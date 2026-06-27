package com.sapphire.domain.util

import java.security.MessageDigest

/**
 * Cache key for `LlmCache` (architecture §3): `SHA-256(itemId, op, modelVersion)`, hex.
 *
 * This makes reader ops idempotent across re-opens — the same (item, op, model) tuple
 * always resolves to the same row, so a second open is a cache hit and never re-spends
 * a token (PRD §4.2 lazy compute + cache). `modelVersion` is folded in so a model swap
 * invalidates stale payloads without a migration.
 *
 * Pure (no Android deps) so it's unit-testable alongside [FeedItemId].
 */
object LlmCacheKey {

    /**
     * @param op stable op identifier, e.g. `"classification"`, `"summary"`, `"translate:zh"`.
     *   Caller controls granularity — translate is keyed per target language.
     * @param modelVersion the model id (or a version tag) that produced the payload.
     */
    fun compute(itemId: String, op: String, modelVersion: String): String {
        val input = itemId.trim() + "\u0001" + op.trim() + "\u0002" + modelVersion.trim()
        return hash(input)
    }

    private fun hash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val out = CharArray(bytes.size * 2)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out[i++] = HEX[v ushr 4]
            out[i++] = HEX[v and 0x0F]
        }
        return String(out)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
