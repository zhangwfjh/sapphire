package com.sapphire.domain.save

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S07 retention cutoff math (architecture §7). Pure — no Android, no Room. Pins the
 * 30-day derivation so the worker's cutoff can't silently drift.
 */
class RetentionPolicyTest {

    private val dayMs = 24L * 60 * 60 * 1000

    @Test
    fun `cutoff is exactly 30 days before now at default retention`() {
        val now = 1_700_000_000_000L
        val cutoff = RetentionPolicy.cutoffEpochMs(now)
        assertEquals(now - 30 * dayMs, cutoff)
    }

    @Test
    fun `cutoff honors a custom retention window`() {
        val now = 1_700_000_000_000L
        val cutoff = RetentionPolicy.cutoffEpochMs(now, retentionDays = 7)
        assertEquals(now - 7 * dayMs, cutoff)
    }

    @Test
    fun `zero retention yields cutoff equal to now`() {
        val now = 1_700_000_000_000L
        assertEquals(now, RetentionPolicy.cutoffEpochMs(now, retentionDays = 0))
    }

    @Test
    fun `RETENTION_DAYS default is 30`() {
        assertEquals(30, RetentionPolicy.RETENTION_DAYS)
    }
}
