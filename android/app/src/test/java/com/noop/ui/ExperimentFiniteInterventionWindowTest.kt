package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Synthetic outcome days only. Mirrors ExperimentFiniteInterventionWindowTests.swift.
 * These test a selection rule, not a physiological or causal-effect estimate.
 */
class ExperimentFiniteInterventionWindowTest {
    private val measured = mapOf(
        "2026-09-05" to 60.0, // Before start.
        "2026-09-06" to 71.0,
        "2026-09-07" to Double.NaN,
        "2026-09-08" to 72.0,
        "2026-09-09" to Double.NEGATIVE_INFINITY,
        "2026-09-10" to 73.0,
        "2026-09-11" to Double.POSITIVE_INFINITY,
        "2026-09-12" to 75.0, // After the experiment's end.
    )

    @Test fun baselineSkipsInvalidAndLoggedDaysBeforeTakingLatestSlots() {
        assertEquals(
            listOf("2026-09-06", "2026-09-10"),
            experimentValidBaselineDays(measured, setOf("2026-09-08"), "2026-09-12", 2),
        )
    }

    @Test fun baselineOnlyInvalidOrLoggedObservationsIsEmpty() {
        assertEquals(
            emptyList<String>(),
            experimentValidBaselineDays(measured,
                setOf("2026-09-05", "2026-09-06", "2026-09-08", "2026-09-10"), "2026-09-12", 10),
        )
    }

    @Test fun invalidObservationsDoNotConsumeDurationSlots() {
        assertEquals(
            listOf("2026-09-06", "2026-09-08"),
            experimentValidObservationDays(measured, "2026-09-06", "2026-09-10", 2),
        )
    }

    @Test fun windowBoundsAreInclusiveAndChronological() {
        assertEquals(
            listOf("2026-09-08", "2026-09-10"),
            experimentValidObservationDays(measured, "2026-09-08", "2026-09-10", 10),
        )
    }

    @Test fun noFiniteObservationsReturnsEmptyWindow() {
        val invalid = mapOf(
            "2026-09-06" to Double.NaN,
            "2026-09-07" to Double.POSITIVE_INFINITY,
            "2026-09-08" to Double.NEGATIVE_INFINITY,
        )
        assertEquals(
            emptyList<String>(),
            experimentValidObservationDays(invalid, "2026-09-06", "2026-09-10", 3),
        )
    }

    @Test fun nonpositiveLimitNormalizesToOne() {
        assertEquals(
            listOf("2026-09-06"),
            experimentValidObservationDays(measured, "2026-09-06", "2026-09-10", 0),
        )
    }

    @Test fun unloggedFiniteDaysStillConsumeWindowSlots() {
        val loggedDays = setOf("2026-09-10")
        val window = experimentValidObservationDays(measured, "2026-09-06", "2026-09-10", 2)
        assertEquals(listOf("2026-09-06", "2026-09-08"), window)
        assertEquals(emptyList<String>(), window.filter { it in loggedDays })
    }

    @Test fun loggedInterventionValuesRemainFinite() {
        val loggedDays = setOf("2026-09-06", "2026-09-07", "2026-09-09", "2026-09-10")
        val window = experimentValidObservationDays(measured, "2026-09-06", "2026-09-10", 3)
        val values = window.filter { it in loggedDays }.map { measured.getValue(it) }
        assertEquals(listOf("2026-09-06", "2026-09-08", "2026-09-10"), window)
        assertEquals(listOf(71.0, 73.0), values)
        assertEquals(72.0, values.average(), 0.0)
    }
}
