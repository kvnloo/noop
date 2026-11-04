package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic canonical-date fixtures mirrored in ExperimentAdherenceCanonicalDateTests.swift. */
class ExperimentAdherenceCanonicalDateTest {
    @Test fun signedDateAliasesCannotMultiplyACompletion() {
        val aliases = setOf("2026-+1-01", "2026-01-+1", "2026-+1-+1")
        assertEquals(1.0, ExperimentAdherence.fraction(
            aliases + "2026-01-01", "2026-01-01", "2026-01-01", 1), 0.0)
        assertEquals(0.0, ExperimentAdherence.fraction(
            aliases, "2026-01-01", "2026-01-01", 1), 0.0)
        for (alias in aliases) {
            assertFalse(alias, ExperimentAdherence.includes(alias, "2026-01-01", "2026-01-01", 1))
        }
    }

    @Test fun malformedDayKeysFailClosedInEveryArgument() {
        val invalid = listOf("2026-+1-01", "2026-01-+1", "+026-01-01", "0000-01-01",
            "2026-1-01", "2026-01-001", "2026-01-01\n", " 2026-01-01",
            "２０２６-01-01", "2026-01-٠١", "2026-02-29", "2026-04-31",
            "2026-00-01", "2026-13-01", "2026-01-00", "2026-01-01T00:00:00Z")
        for (key in invalid) {
            assertEquals(key, 0.0, ExperimentAdherence.fraction(setOf(key), "2026-01-01", "2026-12-31", 365), 0.0)
            assertEquals(key, 0.0, ExperimentAdherence.fraction(setOf("2026-01-01"), key, "2026-12-31", 365), 0.0)
            assertEquals(key, 0.0, ExperimentAdherence.fraction(setOf("2026-01-01"), "2026-01-01", key, 365), 0.0)
            assertFalse(key, ExperimentAdherence.includes(key, "2026-01-01", "2026-12-31", 365))
            assertFalse(key, ExperimentAdherence.includes("2026-01-01", key, "2026-12-31", 365))
            assertFalse(key, ExperimentAdherence.includes("2026-01-01", "2026-01-01", key, 365))
        }
    }

    @Test fun validLeapAndCalendarWindowsAreUnchanged() {
        val cases = listOf(
            Triple("1900-02-28" to "1900-03-01", setOf("1900-03-01"), 0.5),
            Triple("2000-02-28" to "2000-03-01", setOf("2000-02-29"), 1.0 / 3.0),
            Triple("2100-02-28" to "2100-03-01", setOf("2100-03-01"), 0.5),
            Triple("2026-12-31" to "2027-01-02", setOf("2026-12-31", "2027-01-02"), 2.0 / 3.0),
        )
        for ((window, yes, expected) in cases) {
            assertEquals(expected, ExperimentAdherence.fraction(yes, window.first, window.second, 14), 1e-12)
            for (day in yes) {
                assertTrue(ExperimentAdherence.includes(day, window.first, window.second, 14))
            }
        }
    }

    @Test fun multipleAliasesCannotExpandThePrescribedWindow() {
        val keys = buildSet {
            for (day in 1..9) {
                add("2026-01-0$day")
                add("2026-+1-0$day")
                add("2026-01-+$day")
                add("2026-+1-+$day")
            }
        }
        assertEquals(1.0, ExperimentAdherence.fraction(keys, "2026-01-01", "2026-01-09", 7), 0.0)
        val eligible = keys.filter { ExperimentAdherence.includes(it, "2026-01-01", "2026-01-09", 7) }
        assertEquals(7, eligible.size)
        assertFalse(eligible.contains("2026-01-08"))
        assertFalse(eligible.contains("2026-01-09"))
    }
}
