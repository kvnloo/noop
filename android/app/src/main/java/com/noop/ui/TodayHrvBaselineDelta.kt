package com.noop.ui

import com.noop.analytics.BaselineState
import com.noop.analytics.Baselines
import com.noop.data.DailyMetric
import kotlin.math.abs
import kotlin.math.roundToInt

/** Today HRV against Charge's already-folded baseline, not a second average of the display history. */
internal fun todayHrvBaselineDeltaPct(
    row: DailyMetric?,
    baseline: BaselineState?,
    hrvEpoch: Double,
): Int? {
    val hrv = row?.avgHrv ?: return null
    if (!hrv.isFinite() || hrv <= 0.0 || baseline?.usable != true) return null
    val anchor = baseline.baseline
    if (!anchor.isFinite() || anchor <= 0.0) return null
    // A carried or selected night from the discarded epoch cannot describe the new baseline.
    val firstCountedDay = Baselines.firstCountedDay(hrvEpoch)
    if (firstCountedDay != null && row.day < firstCountedDay) return null
    // Match Swift's inclusive measurement-method mismatch guard and rounding (ties away from zero).
    if (hrv < 0.5 * anchor || hrv > 2.0 * anchor) return null
    val pct = (hrv - anchor) / anchor * 100.0
    val magnitude = abs(pct).roundToInt()
    return if (pct < 0.0) -magnitude else magnitude
}
