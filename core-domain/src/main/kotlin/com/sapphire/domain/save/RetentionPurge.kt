package com.sapphire.domain.save

/**
 * S07 retention purge port (architecture §7). Runs the 30-day rolling retention: deletes
 * FeedItems that are READ, not saved, and fetched before the cutoff epoch-ms. CASCADE
 * sweeps their `read_log` and `llm_cache` rows.
 *
 * Implementations live in the data layer; the domain layer owns the retention rule
 * (cutoff derivation) and passes the concrete cutoff epoch in.
 */
interface RetentionPurge {

    /**
     * Delete all read + unsaved items fetched before [cutoffEpochMs]. Returns the number
     * of FeedItem rows purged (for worker logging / observability).
     */
    suspend fun purgeOlderThan(cutoffEpochMs: Long): Int
}
