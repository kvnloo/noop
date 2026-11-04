package com.noop.ui

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StressModel.build carry (#543). The Today "Stress" metric derives against a 30-day RHR/HRV baseline.
 * Today's own daily row is often vitals-less until the overnight is analyzed (especially right after an
 * app update relaunches and re-runs the analyze pass), and every OTHER Today vital carries last night's
 * value — Stress used to be the one card that didn't, so it dropped to "Calibrating" while the rest showed
 * numbers. These pin the carry: score the newest day that actually has RHR/HRV. Twin of the Swift
 * StressModelCarryTests.
 */
class StressModelTest {

    private fun day(d: String, rhr: Int?, hrv: Double?) =
        DailyMetric(deviceId = "my-whoop", day = d, restingHr = rhr, avgHrv = hrv)

    // 31 days that all carry RHR + HRV: a full 30-day baseline plus one more scorable day.
    private val baseline = (1..30).map { day("2026-06-%02d".format(it), rhr = 55, hrv = 60.0) } +
        day("2026-07-01", rhr = 55, hrv = 60.0)

    @Test
    fun vitalsLessTodayCarriesInsteadOfCalibrating() {
        // Control: today HAS vitals -> builds (unchanged behaviour).
        assertNotNull(StressModel.build(baseline + day("2026-07-02", rhr = 58, hrv = 45.0), emptyMap()))
        // The fix: today has NO RHR/HRV yet (the post-update window) but a prior day does -> carries, not null.
        assertNotNull(
            "a vitals-less today must carry the last day with RHR/HRV, not calibrate",
            StressModel.build(baseline + day("2026-07-02", rhr = null, hrv = null), emptyMap()),
        )
    }

    @Test
    fun noVitalsAnywhereStillCalibrates() {
        // Genuine cold start: no day has RHR/HRV and nothing stored -> honestly calibrating (null).
        val days = (1..5).map { day("2026-07-0$it", rhr = null, hrv = null) }
        assertNull(StressModel.build(days, emptyMap()))
    }

    @Test
    fun storedStressOnLatestVitalsLessDayIsUsedNotSkipped() {
        // An imported latest day carries a STORED stress value but no RHR/HRV (e.g. a Xiaomi / Garmin /
        // WHOOP export). The original gate honoured it; the carry must NOT skip it back to an older vitals
        // day. The stored 2.5 must win over any derived carry.
        val days = baseline + day("2026-07-02", rhr = null, hrv = null)
        val model = StressModel.build(days, mapOf("2026-07-02" to 2.5))
        assertNotNull(model)
        assertEquals("the latest day's stored stress must win over a carry", 2.5, model!!.score, 0.001)
    }
    /** #2818: independent Charge epoch cuts and historical readback parity with Swift. */
    @Test
    fun epochCutsAreIndependentAndKeepHistoricalDayContext() {
        val old = (14..18).map { day("2026-07-%02d".format(it), rhr = 55, hrv = 90.0) }
        val recent = listOf(
            day("2026-07-19", rhr = 59, hrv = 77.0),
            day("2026-07-20", rhr = 60, hrv = 78.0),
            day("2026-07-21", rhr = 61, hrv = 79.0),
            day("2026-07-22", rhr = 60, hrv = 78.0),
        )
        val all = old + recent + day("2026-07-23", rhr = 59, hrv = 82.0)
        val legacy = StressModel.build(all, emptyMap())!!
        val recalibrated = StressModel.build(
            all, emptyMap(), hrvBaselineEpoch = 1_784_376_000.0,
            recoveryBaselineEpoch = 1_784_548_800.0,
        )!!
        assertEquals(-8.0 / 3.0, legacy.hrvDelta!!, 1e-9)
        assertEquals(16.0 / 9.0, legacy.rhrDelta!!, 1e-9)
        assertEquals(4.0, recalibrated.hrvDelta!!, 1e-9)
        assertEquals(-1.5, recalibrated.rhrDelta!!, 1e-9)
        assertTrue(recalibrated.score < 1.0)

        val historical = old.take(4)
        val before = StressModel.build(historical, emptyMap())!!
        val withFutureReset = StressModel.build(
            historical, emptyMap(), hrvBaselineEpoch = 1_784_376_000.0,
            recoveryBaselineEpoch = 1_784_548_800.0,
        )!!
        assertEquals(before.hrvDelta!!, withFutureReset.hrvDelta!!, 1e-9)
        assertEquals(before.rhrDelta!!, withFutureReset.rhrDelta!!, 1e-9)
    }
}
