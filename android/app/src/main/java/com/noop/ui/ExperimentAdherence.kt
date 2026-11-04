package com.noop.ui

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Journal Yes answers measure follow-through even when the wearable has no usable outcome. */
internal object ExperimentAdherence {
    fun fraction(
        yesDays: Set<String>, startedDay: String, today: String, durationDays: Int,
    ): Double {
        val start = canonicalDay(startedDay) ?: return 0.0
        val end = canonicalDay(today) ?: return 0.0
        if (end.isBefore(start)) return 0.0
        val elapsed = (ChronoUnit.DAYS.between(start, end) + 1)
            .coerceAtMost(durationDays.coerceAtLeast(1).toLong())
        val completed = yesDays.count { key ->
            val day = canonicalDay(key)
            day != null && !day.isBefore(start) && ChronoUnit.DAYS.between(start, day) < elapsed
        }
        return completed.toDouble() / elapsed
    }

    /** Prevent late-arriving wearable samples from extending an already-ended plan. */
    fun includes(day: String, startedDay: String, today: String, durationDays: Int): Boolean {
        val start = canonicalDay(startedDay) ?: return false
        val end = canonicalDay(today) ?: return false
        val observed = canonicalDay(day) ?: return false
        return !observed.isBefore(start) && !observed.isAfter(end) &&
            ChronoUnit.DAYS.between(start, observed) < durationDays.coerceAtLeast(1)
    }
    /** Same ASCII yyyy-MM-dd, positive-year contract as the Swift calendar gate. */
    private fun canonicalDay(key: String): LocalDate? {
        if (key.length != 10 || key[4] != '-' || key[7] != '-' ||
            key.indices.any { i -> i != 4 && i != 7 && key[i] !in '0'..'9' }) return null
        return runCatching { LocalDate.parse(key) }.getOrNull()?.takeIf { it.year > 0 }
    }
}
