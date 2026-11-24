package com.noop.ui

import com.noop.analytics.BaselineStatus
import com.noop.analytics.ChargeBaselines
import com.noop.data.DailyMetric
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayHrvBaselineDeltaTest {
    @Test
    fun comparisonMatchesSwiftAfterChargeRecalibration() {
        fun night(day: Int, hrv: Double?) =
            DailyMetric("synthetic-noop", "2026-01-${day.toString().padStart(2, '0')}", avgHrv = hrv)

        // Swift TodayHrvBaselineDeltaTests: the discarded 90 ms nights would put 82 below
        // average; Charge has instead re-learned 78 ms from five nights after the reset.
        val epoch = Instant.parse("2026-01-08T12:00:00Z").epochSecond.toDouble()
        val oldNights = (1..8).map { night(it, 90.0) }
        val newNights = (9..13).map { night(it, 78.0) }
        fun fold(rows: List<DailyMetric>, reset: Double = epoch) = ChargeBaselines.resolve(
            imported = emptyList(), own = rows, anchorDay = "2026-01-14",
            hrvEpoch = reset, recoveryEpoch = reset,
        ).hrv

        val baseline = fold(oldNights + newNights)
        val today = night(14, 82.0)
        assertEquals(5, todayHrvBaselineDeltaPct(today, baseline, epoch))
        assertEquals(-5, todayHrvBaselineDeltaPct(night(14, 74.0), baseline, epoch))
        assertEquals(0, todayHrvBaselineDeltaPct(night(14, 78.0), baseline, epoch))
        assertNull(todayHrvBaselineDeltaPct(night(8, 90.0), baseline, epoch))
        assertEquals(0, todayHrvBaselineDeltaPct(night(9, 78.0), baseline, epoch))
        assertNull(todayHrvBaselineDeltaPct(today, null, epoch))
        assertNull(todayHrvBaselineDeltaPct(today, fold(oldNights + newNights.take(3)), epoch))
        assertNull(todayHrvBaselineDeltaPct(today, baseline.copy(status = BaselineStatus.STALE), epoch))
        for (invalid in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(todayHrvBaselineDeltaPct(night(14, invalid), baseline, epoch))
            assertNull(todayHrvBaselineDeltaPct(today, baseline.copy(baseline = invalid), epoch))
        }
        assertNull(todayHrvBaselineDeltaPct(night(14, null), baseline, epoch))

        // Exactly representable half ties and ratio endpoints, using another Charge fold.
        val tieBaseline = fold((9..13).map { night(it, 100.0) })
        assertEquals(13, todayHrvBaselineDeltaPct(night(14, 112.5), tieBaseline, epoch))
        assertEquals(-13, todayHrvBaselineDeltaPct(night(14, 87.5), tieBaseline, epoch))
        assertEquals(100, todayHrvBaselineDeltaPct(night(14, 200.0), tieBaseline, epoch))
        assertEquals(-50, todayHrvBaselineDeltaPct(night(14, 50.0), tieBaseline, epoch))
        assertNull(todayHrvBaselineDeltaPct(night(14, 200.1), tieBaseline, epoch))
        assertNull(todayHrvBaselineDeltaPct(night(14, 49.9), tieBaseline, epoch))
    }
}
