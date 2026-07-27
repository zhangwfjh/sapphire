package com.sapphire.domain.agent

import com.sapphire.domain.model.AgentFrequency
import java.util.Calendar
import java.util.Date

/**
 * Cadence display + next-run math — pure logic, unit-tested in [CadenceTest].
 * Ported from the verified implementation in `design/agents.html`
 * (`cadenceLabel` / `nextRunText` / `RUNS_PER_MONTH`).
 *
 * Hourly cadences show a phase origin ("Every 4h · from 07:00") and compute the
 * next concrete slot by walking forward from today's origin in N-hour steps —
 * the result is a real clock time + a countdown, not a vague "in N hours".
 */

/** Display label for an agent's cadence (list/detail pills). */
fun cadenceLabel(freq: AgentFrequency, time: String): String {
    val t = normalized(time)
    return when {
        freq.isHourly -> "Every ${freq.intervalHours}h · from $t"
        freq == AgentFrequency.DAILY -> "Daily · ≈$t"
        freq == AgentFrequency.WEEKDAY -> "Weekdays · ≈$t"
        freq == AgentFrequency.WEEKLY -> "Weekly · Mon ≈$t"
        else -> "Daily"
    }
}

/**
 * Approximate next-run text. For hourly cadences the countdown is always ≤ the
 * interval. `nowMs` is a parameter so the hourly walk is deterministic in tests.
 */
fun nextRunText(freq: AgentFrequency, time: String, nowMs: Long): String {
    val t = normalized(time)
    if (freq.isHourly) {
        val stepH = freq.intervalHours ?: return "≈ soon"
        val (oh, om) = parseHhMm(t)
        val next = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, oh)
            set(Calendar.MINUTE, om)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val stepMs = stepH * 3_600_000L
        if (next.timeInMillis <= nowMs) next.timeInMillis += stepMs
        while (next.timeInMillis <= nowMs) next.timeInMillis += stepMs
        val diffMs = next.timeInMillis - nowMs
        val diffH = diffMs / 3_600_000L
        val diffM = (diffMs % 3_600_000L) / 60_000L
        val diffStr = if (diffH > 0) "${diffH}h ${diffM}m" else "${diffM}m"
        val hh = next.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = next.get(Calendar.MINUTE).toString().padStart(2, '0')
        return "≈ $hh:$mm (in $diffStr)"
    }
    return when (freq) {
        AgentFrequency.DAILY -> "tomorrow ≈$t"
        AgentFrequency.WEEKDAY -> "next weekday ≈$t"
        AgentFrequency.WEEKLY -> "next Mon ≈$t"
        else -> "tomorrow ≈$t"
    }
}

/** Approximate runs per month — drives the cost estimate. */
fun runsPerMonth(freq: AgentFrequency): Int = when (freq) {
    AgentFrequency.HOURLY_1 -> 720
    AgentFrequency.HOURLY_2 -> 360
    AgentFrequency.HOURLY_4 -> 180
    AgentFrequency.HOURLY_6 -> 120
    AgentFrequency.HOURLY_8 -> 90
    AgentFrequency.HOURLY_12 -> 60
    AgentFrequency.DAILY -> 30
    AgentFrequency.WEEKDAY -> 22
    AgentFrequency.WEEKLY -> 4
}

private fun normalized(time: String): String =
    if (time.isBlank()) "07:00" else time

private fun parseHhMm(time: String): Pair<Int, Int> {
    val parts = time.split(":")
    return parts[0].toInt() to (parts.getOrNull(1)?.toInt() ?: 0)
}

