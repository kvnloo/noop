package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Synthetic Journal observations only. Mirrors ExperimentBaselineControlsTests.swift.
 * No human biometrics, treatment claims, or actual N-of-1 results.
 */
class ExperimentBaselineControlsTest {
    private val outcomes = mapOf(
        "2026-09-01" to 70.0, // explicitly No
        "2026-09-02" to 99.0, // unanswered; NOT a control
        "2026-09-03" to 75.0, // explicitly No
        "2026-09-04" to 65.0, // answered Yes; NOT a control
        "2026-09-05" to 79.0, // explicitly No
        "2026-09-06" to 84.0, // after start; NOT a baseline
    )
    private val noDays = setOf("2026-09-01", "2026-09-03", "2026-09-05", "2026-09-06")

    @Test fun onlyExplicitNoDaysBeforeStartAreControls() {
        assertEquals(
            listOf("2026-09-01", "2026-09-03", "2026-09-05"),
            experimentExplicitNoBaselineDays(outcomes, noDays, "2026-09-06", 14),
        )
    }

    @Test fun noExplicitAnswersMeansNoBaseline() {
        assertEquals(emptyList<String>(), experimentExplicitNoBaselineDays(
            outcomes, emptySet(), "2026-09-06", 14,
        ))
    }

    @Test fun baselineTakesLastMatchingDaysOnly() {
        assertEquals(
            listOf("2026-09-03", "2026-09-05"),
            experimentExplicitNoBaselineDays(outcomes, noDays, "2026-09-06", 2),
        )
    }

    @Test fun noOutcomeMeansNoControlObservation() {
        assertEquals(emptyList<String>(), experimentExplicitNoBaselineDays(
            mapOf("2026-09-02" to 99.0), noDays, "2026-09-06", 14,
        ))
    }

    @Test fun nonFiniteBaselineValuesAreNeverCountedAsObservations() {
        val damaged = outcomes + ("2026-09-03" to Double.NaN) +
            ("2026-09-05" to Double.POSITIVE_INFINITY)
        assertEquals(
            listOf("2026-09-01"),
            experimentExplicitNoBaselineDays(damaged, noDays, "2026-09-06", 14),
        )
    }

    @Test fun nonFiniteInterventionDaysDoNotConsumeValidObservationSlots() {
        val measured = mapOf(
            "2026-09-06" to 71.0,
            "2026-09-07" to Double.NaN,
            "2026-09-08" to 72.0,
            "2026-09-09" to Double.NEGATIVE_INFINITY,
            "2026-09-10" to 73.0,
        )
        assertEquals(
            listOf("2026-09-06", "2026-09-08"),
            experimentValidObservationDays(measured, "2026-09-06", "2026-09-10", 2),
        )
    }
}
