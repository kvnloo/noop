package com.noop.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identical numeric oracles to StrandTests/GpsSplitMathTests.swift. */
class GpsSplitMathTest {
    private fun p(metres: Double, ms: Long, segment: Int = 0) =
        GpsSplitMath.Point(metres, ms, segment)

    private fun s(metres: Double, elapsed: Double, duration: Double, complete: Boolean) =
        GpsSplitMath.Split(metres, elapsed, duration, complete)

    private fun expect(actual: List<GpsSplitMath.Split>, expected: List<GpsSplitMath.Split>) {
        assertEquals(expected.size, actual.size)
        for ((got, want) in actual.zip(expected)) {
            assertEquals(want.distanceMetres, got.distanceMetres, 1e-9)
            assertEquals(want.elapsedSeconds, got.elapsedSeconds, 1e-9)
            assertEquals(want.durationSeconds, got.durationSeconds, 1e-9)
            assertEquals(want.complete, got.complete)
        }
    }

    @Test fun severalBoundariesInOneSegmentAndIncompleteTail() {
        expect(GpsSplitMath.calculate(listOf(p(0.0, 0), p(3_200.0, 960_000)), 1_000.0), listOf(
            s(1_000.0, 300.0, 300.0, true),
            s(1_000.0, 600.0, 300.0, true),
            s(1_000.0, 900.0, 300.0, true),
            s(200.0, 960.0, 60.0, false),
        ))
    }

    @Test fun exactBoundariesHaveNoPhantomTail() {
        expect(GpsSplitMath.calculate(listOf(p(0.0, 0), p(1_000.0, 300_000),
            p(2_000.0, 610_000)), 1_000.0), listOf(
            s(1_000.0, 300.0, 300.0, true),
            s(1_000.0, 610.0, 310.0, true),
        ))
    }

    @Test fun crossingInterpolatesWithinUnevenSegment() {
        expect(GpsSplitMath.calculate(listOf(p(0.0, 0), p(750.0, 150_000),
            p(1_250.0, 450_000)), 1_000.0), listOf(
            s(1_000.0, 300.0, 300.0, true),
            s(250.0, 450.0, 150.0, false),
        ))
    }

    @Test fun pauseSeamUsesActiveClockWithoutBridging() {
        // A long wall-clock pause is already removed from activeElapsedMs by the timed-route source.
        expect(GpsSplitMath.calculate(listOf(p(0.0, 0), p(900.0, 300_000),
            p(900.0, 300_000, 1), p(1_100.0, 360_000, 1),
            p(1_500.0, 480_000, 1)), 1_000.0), listOf(
            s(1_000.0, 330.0, 330.0, true),
            s(500.0, 480.0, 150.0, false),
        ))
    }

    @Test fun pauseSeamCannotCreateUnobservedDistance() {
        assertTrue(GpsSplitMath.calculate(listOf(p(0.0, 0), p(900.0, 300_000),
            p(1_500.0, 360_000, 1)), 1_000.0).isEmpty())
    }

    @Test fun rejectsZeroNegativeAndReorderedActiveTime() {
        val invalid = listOf(
            listOf(p(0.0, 0), p(1_000.0, 0)),
            listOf(p(0.0, -1), p(1_000.0, 300_000)),
            listOf(p(0.0, 300_000), p(1_000.0, 200_000)),
            listOf(p(0.0, 0), p(1_000.0, 300_000), p(1_200.0, 300_000)),
        )
        for (points in invalid) {
            assertTrue(GpsSplitMath.calculate(points, 1_000.0).isEmpty())
        }
    }

    @Test fun rejectsReorderedDistanceAndSegments() {
        assertTrue(GpsSplitMath.calculate(listOf(p(0.0, 0), p(1_000.0, 300_000),
            p(900.0, 400_000)), 1_000.0).isEmpty())
        assertTrue(GpsSplitMath.calculate(listOf(p(0.0, 0, 1), p(1_000.0, 300_000, 0)),
            1_000.0).isEmpty())
    }

    @Test fun invalidInputsFailClosed() {
        for (stride in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(GpsSplitMath.calculate(listOf(p(0.0, 0), p(1_000.0, 300_000)), stride).isEmpty())
        }
        assertTrue(GpsSplitMath.calculate(listOf(p(0.0, 0), p(Double.NaN, 300_000)),
            1_000.0).isEmpty())
        assertTrue(GpsSplitMath.calculate(listOf(p(0.0, 0), p(Double.POSITIVE_INFINITY, 300_000)),
            1_000.0).isEmpty())
        assertTrue(GpsSplitMath.calculate(listOf(p(1.0, 0), p(1_000.0, 300_000)),
            1_000.0).isEmpty())
    }

    @Test fun lastIncompleteSplitWithoutFullBoundary() {
        expect(GpsSplitMath.calculate(listOf(p(0.0, 0), p(400.0, 180_000)), 1_000.0), listOf(
            s(400.0, 180.0, 180.0, false),
        ))
    }

    @Test fun mileStrideIsOnlyAUnitChoice() {
        expect(GpsSplitMath.calculate(listOf(p(0.0, 0), p(3_218.688, 1_200_000)),
            1_609.344), listOf(
            s(1_609.344, 600.0, 600.0, true),
            s(1_609.344, 1_200.0, 600.0, true),
        ))
    }

    @Test fun tenExactMilesEndWithACompleteSplit() {
        expect(GpsSplitMath.calculate(listOf(p(0.0, 0), p(16_093.44, 6_000_000)),
            1_609.344), (1..10).map { s(1_609.344, it * 600.0, 600.0, true) })
    }

    @Test fun decimalStrideHasNoAccumulatedPhantomTail() {
        expect(GpsSplitMath.calculate(listOf(p(0.0, 0), p(1.0, 10_000)), 0.1),
            (1..10).map { s(0.1, it.toDouble(), 1.0, true) })
    }

    @Test fun oneUlpShortOfBoundaryRemainsIncomplete() {
        val end = Math.nextDown(1.0)
        val expected = (1..9).map { s(0.1, it.toDouble(), 1.0, true) } +
            s(end - 0.9, 10.0, 1.0, false)
        expect(GpsSplitMath.calculate(listOf(p(0.0, 0), p(end, 10_000)), 0.1), expected)
    }

    @Test fun stationaryDuplicateTimeIsNotMovement() {
        expect(GpsSplitMath.calculate(listOf(p(0.0, 0), p(0.0, 0),
            p(1_500.0, 600_000)), 1_000.0), listOf(
            s(1_000.0, 400.0, 400.0, true),
            s(500.0, 600.0, 200.0, false),
        ))
    }

    @Test fun emptyStationaryAndExcessiveOutput() {
        assertTrue(GpsSplitMath.calculate(emptyList(), 1_000.0).isEmpty())
        assertTrue(GpsSplitMath.calculate(listOf(p(0.0, 0)), 1_000.0).isEmpty())
        assertTrue(GpsSplitMath.calculate(listOf(p(0.0, 0), p(0.0, 90_000)), 1_000.0).isEmpty())
        assertTrue(GpsSplitMath.calculate(listOf(p(0.0, 0), p(11_000.0, 3_300_000)), 1.0).isEmpty())
    }
}
