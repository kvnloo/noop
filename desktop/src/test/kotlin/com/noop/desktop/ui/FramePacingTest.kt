package com.noop.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FramePacingTest {
    @Test
    fun constantIntervalsProduceExpectedCadence() {
        val intervals = LongArray(120) { 1_851_286L }

        val result = calculateFrameStats(intervals, TARGET_REFRESH_HZ)

        assertEquals(120, result.sampleCount)
        assertTrue(result.measuredHz in 540.0..540.3)
        assertTrue(result.p99Milliseconds in 1.851..1.852)
    }

    @Test
    fun reportsIntervalsBeyondTargetBudget() {
        val budget = (1_000_000_000.0 / TARGET_REFRESH_HZ).toLong()
        val intervals = LongArray(100) { if (it < 25) budget + 1 else budget - 1 }

        val result = calculateFrameStats(intervals, TARGET_REFRESH_HZ)

        assertEquals(25.0, result.overBudgetPercent, absoluteTolerance = 0.001)
    }
}
