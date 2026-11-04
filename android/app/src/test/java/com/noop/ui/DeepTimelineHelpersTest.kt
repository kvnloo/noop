package com.noop.ui

import org.junit.Assert.assertEquals
import com.noop.data.SkinTempSample
import com.noop.protocol.DeviceFamily
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure Deep Timeline helpers (#575) shared with the macOS Repository.timelineSeries:
 * the adaptive bucket-vs-raw decision (so the chart never draws ~86k points), the in-process
 * bucketer, and the zoom/pan window math. Mirrors the Swift DeepTimelineFacadeTests pure cases.
 */
class DeepTimelineHelpersTest {

    // MARK: - Adaptive resolution

    @Test fun dayScalePicksCoarseBuckets() {
        val bucket = timelineBucketSeconds(86_400L, targetPoints = 600)
        assertTrue("a full day must downsample, not read raw seconds", bucket > 1L)
        assertTrue(bucket >= 120L)
    }

    @Test fun smallWindowPicksRawSeconds() {
        assertEquals(1L, timelineBucketSeconds(120L, 600))
        assertEquals(1L, timelineBucketSeconds(300L, 600))
        assertEquals(1L, timelineBucketSeconds(600L, 600)) // boundary: ideal == 1, still raw
    }

    @Test fun bucketSnapsToFriendlyStepAndIsMonotonic() {
        assertEquals(15L, timelineBucketSeconds(7_200L, 600)) // ideal 12 → 15
        val day = timelineBucketSeconds(86_400L, 600)
        val twoHour = timelineBucketSeconds(7_200L, 600)
        assertTrue("a wider window must not read finer than a narrower one", day >= twoHour)
    }

    // MARK: - In-process bucketer

    @Test fun downsampleMeanBinsOnGridAscending() {
        val pts = listOf(
            TimelinePoint(1_000L, 10.0),
            TimelinePoint(1_001L, 20.0), // same 60s bucket
            TimelinePoint(1_061L, 30.0), // next 60s bucket
        )
        val out = downsampleTimeline(pts, 60L)
        assertEquals(2, out.size)
        assertEquals(15.0, out[0].value, 0.001) // mean of 10,20
        assertEquals(30.0, out[1].value, 0.001)
        assertTrue(out[0].ts < out[1].ts)
    }

    // MARK: - Per-device WHOOP 4.0 skin temp (#2884)

    @Test fun skinTempAnchorWindowMatchesThe21DayScoringWindow() {
        val bounds = timelineSkinAnchorWindow(nowSeconds = 8_683_200L, offsetSec = -21_600L)
        assertEquals(6_825_600L, bounds.first)
        assertEquals(8_726_400L, bounds.last)
    }

    @Test fun zoomUsesTheHistoryAnchorNotItsVisibleTwoSamples() {
        val visible = listOf(
            SkinTempSample(deviceId = "my-whoop", ts = 100L, raw = 1005),
            SkinTempSample(deviceId = "my-whoop", ts = 101L, raw = 1143),
        )
        // 100 older worn readings, two visible; neither visible-only median nor the global 826
        // reproduces the nightly value. The off-wrist floor and saturation must not move the anchor.
        val windowRaws = List(100) { 995 } + listOf(509, 2047, 1005, 1143)
        val points = timelineSkinTempPoints(visible, DeviceFamily.WHOOP4, windowRaws)
        assertEquals(33.5, points[0].value, 1e-9)
        assertEquals(40.4, points[1].value, 1e-9)
        assertEquals(100L, points[0].ts)
        assertEquals(101L, points[1].ts)
    }

    @Test fun sparseFourKeepsGlobalAnchorAndFiveKeepsCentidegrees() {
        val four = listOf(SkinTempSample(deviceId = "a", ts = 1L, raw = 1005))
        assertEquals(41.95, timelineSkinTempPoints(four, DeviceFamily.WHOOP4, listOf(995))[0].value, 1e-9)
        val five = listOf(SkinTempSample(deviceId = "b", ts = 2L, raw = 3057))
        assertEquals(30.57, timelineSkinTempPoints(five, DeviceFamily.WHOOP5, List(100) { 995 })[0].value, 1e-9)
    }

    // MARK: - Zoom / pan window math

    @Test fun zoomInShrinksSpanAndStaysInBounds() {
        val bounds = 0L..86_400L
        val zoomed = zoomedWindow(bounds, scale = 4f, anchorFraction = 0.5f, bounds = bounds)
        assertTrue("zoom in must shrink the window", (zoomed.last - zoomed.first) < 86_400L)
        assertTrue(zoomed.first >= bounds.first)
        assertTrue(zoomed.last <= bounds.last)
    }

    @Test fun zoomFlooredAtMinSpan() {
        val bounds = 0L..86_400L
        val zoomed = zoomedWindow(bounds, scale = 100_000f, anchorFraction = 0.5f, bounds = bounds, minSpan = 60L)
        assertTrue("must not zoom past the 60s floor", (zoomed.last - zoomed.first) >= 60L)
    }

    @Test fun panShiftsWindowAndClampsToBounds() {
        val bounds = 0L..86_400L
        val base = 0L..3_600L
        // Pan right past the end clamps so the window's end never exceeds bounds.
        val panned = pannedWindow(base, deltaSeconds = 1_000_000L, bounds = bounds)
        assertEquals(3_600L, panned.last - panned.first) // span preserved
        assertEquals(86_400L, panned.last)               // clamped to the day's end
        // Pan left past the start clamps to 0.
        val left = pannedWindow(3_000L..6_600L, deltaSeconds = -1_000_000L, bounds = bounds)
        assertEquals(0L, left.first)
    }
}
