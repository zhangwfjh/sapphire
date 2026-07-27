package com.sapphire.domain.agent

import com.sapphire.domain.model.AgentFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM cadence logic (design: 2026-07-27-agents-foundation-ui.md, Task 1).
 * Covers: scheduled labels, hourly phase label, hourly next-run countdown ≤
 * interval, scheduled next-run text, runs-per-month values, blank-time default.
 */
class CadenceTest {

    @Test fun `scheduled cadence labels carry the trigger time`() {
        assertEquals("Daily · ≈07:00", cadenceLabel(AgentFrequency.DAILY, "07:00"))
        assertEquals("Weekdays · ≈08:30", cadenceLabel(AgentFrequency.WEEKDAY, "08:30"))
        assertEquals("Weekly · Mon ≈18:30", cadenceLabel(AgentFrequency.WEEKLY, "18:30"))
    }

    @Test fun `hourly cadence label shows the phase origin`() {
        assertEquals("Every 4h · from 07:00", cadenceLabel(AgentFrequency.HOURLY_4, "07:00"))
        assertEquals("Every 1h · from 09:30", cadenceLabel(AgentFrequency.HOURLY_1, "09:30"))
    }

    @Test fun `blank time falls back to 07_00`() {
        assertEquals("Daily · ≈07:00", cadenceLabel(AgentFrequency.DAILY, ""))
        assertEquals("Every 4h · from 07:00", cadenceLabel(AgentFrequency.HOURLY_4, "  "))
    }

    @Test fun `scheduled next-run text`() {
        val now = 0L
        assertEquals("tomorrow ≈07:00", nextRunText(AgentFrequency.DAILY, "07:00", now))
        assertEquals("next weekday ≈08:30", nextRunText(AgentFrequency.WEEKDAY, "08:30", now))
        assertEquals("next Mon ≈18:30", nextRunText(AgentFrequency.WEEKLY, "18:30", now))
    }

    @Test fun `hourly next-run is a clock time with a countdown`() {
        // 2026-01-01 10:00:00 UTC — a fixed "now" well past the 07:00 origin.
        val now = 1767223200000L
        val text = nextRunText(AgentFrequency.HOURLY_4, "07:00", now)
        assertTrue("expected a HH:mm clock in '$text'", Regex("≈ \\d{2}:\\d{2} \\(in .*\\)").containsMatchIn(text))
        // The countdown must not exceed the 4h interval.
        val hours = Regex("in (\\d+)h").find(text)?.groupValues?.get(1)?.toInt()
        assertTrue("countdown $hours h must be < 4", hours != null && hours in 0..3)
    }

    @Test fun `hourly next-run shifts when phase origin changes`() {
        val now = 1767223200000L // 10:00 UTC, past both origins
        val from7 = nextRunText(AgentFrequency.HOURLY_4, "07:00", now)
        val from9 = nextRunText(AgentFrequency.HOURLY_4, "09:00", now)
        // Origin 07:00 → grid 03/07/11/15/19/23 → next is 11:00.
        // Origin 09:00 → grid 01/05/09/13/17/21 → next is 13:00.
        // (timezone-dependent on the JVM, so assert structure + countdown bound only)
        assertTrue(from7.startsWith("≈ "))
        assertTrue(from9.startsWith("≈ "))
        assertTrue(from7 != from9)
    }

    @Test fun `runs per month`() {
        assertEquals(720, runsPerMonth(AgentFrequency.HOURLY_1))
        assertEquals(180, runsPerMonth(AgentFrequency.HOURLY_4))
        assertEquals(60, runsPerMonth(AgentFrequency.HOURLY_12))
        assertEquals(30, runsPerMonth(AgentFrequency.DAILY))
        assertEquals(22, runsPerMonth(AgentFrequency.WEEKDAY))
        assertEquals(4, runsPerMonth(AgentFrequency.WEEKLY))
    }
}
