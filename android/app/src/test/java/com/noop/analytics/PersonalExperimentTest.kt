package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalExperimentTest {
    private fun snapshot(controlDays: Set<String>) =
        PersonalExperiment.snapshot(
            outcomeDays = mapOf(
                "2026-01-01" to 20.0, "2026-01-02" to 40.0, "2026-01-03" to 100.0,
                "2026-01-04" to 60.0, "2026-01-05" to 80.0,
            ),
            loggedDays = setOf("2026-01-01", "2026-01-05"),
            controlDays = controlDays,
            startedDay = "2026-01-04", today = "2026-01-05", daysSinceStart = 1,
            durationDays = 14, baselineDays = 14,
        )

    @Test
    fun baselineMatchesSwiftOracle() {
        val result = snapshot(controlDays = setOf("2026-01-02", "2026-01-04"))
        val actual = listOf(
            result.baselineCount, result.baselineMean,
            result.interventionCount, result.interventionMean,
            result.delta, result.compliance, result.loggedToday,
            result.daysElapsed, result.durationDays, result.confidence.name.lowercase(),
        ).joinToString("|")
        // Verbatim stdout from swiftc -O PersonalExperiment.swift main.swift -o oracle && ./oracle
        // over the matching fixture in PersonalExperimentTests.swift.
        assertEquals("1|40.0|1|80.0|40.0|50.0|true|2|14|low", actual)
    }

    @Test
    fun noPreStartControlsLeavesBaselineAndDeltaUnavailable() {
        val result = snapshot(controlDays = setOf("2026-01-04"))
        assertEquals(0, result.baselineCount)
        assertNull(result.baselineMean)
        assertNull(result.delta)
        assertEquals(80.0, result.interventionMean)
    }
}
