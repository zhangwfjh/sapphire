package com.sapphire.domain.save

/**
 * S07 retention policy (architecture §7). Pure time arithmetic — kept out of the worker so
 * the cutoff rule is unit-testable without a scheduler.
 *
 * PRD §4.3: "automated 30-day rolling retention policy." Items fetched more than
 * [RETENTION_DAYS] ago are eligible for purge (if also READ + not saved).
 */
object RetentionPolicy {

    const val RETENTION_DAYS = 30
    private val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    /**
     * The fetchedAt cutoff: any item fetched before this epoch-ms is purge-eligible
     * (pending the read + unsaved conditions enforced in the DAO query).
     */
    fun cutoffEpochMs(nowEpochMs: Long, retentionDays: Int = RETENTION_DAYS): Long =
        nowEpochMs - retentionDays * MILLIS_PER_DAY
}
