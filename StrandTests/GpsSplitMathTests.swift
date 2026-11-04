import XCTest
@testable import Strand

/// Identical numeric oracles to android/app/src/test/java/com/noop/location/GpsSplitMathTest.kt.
final class GpsSplitMathTests: XCTestCase {
    private func p(_ metres: Double, _ ms: Int64, _ segment: Int = 0) -> GpsSplitMath.Point {
        .init(distanceMetres: metres, activeElapsedMs: ms, segment: segment)
    }

    private func s(_ metres: Double, _ elapsed: Double, _ duration: Double,
                   _ complete: Bool) -> GpsSplitMath.Split {
        .init(distanceMetres: metres, elapsedSeconds: elapsed,
              durationSeconds: duration, complete: complete)
    }

    private func expect(_ actual: [GpsSplitMath.Split], _ expected: [GpsSplitMath.Split],
                        file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(actual.count, expected.count, file: file, line: line)
        guard actual.count == expected.count else { return }
        for (got, want) in zip(actual, expected) {
            XCTAssertEqual(got.distanceMetres, want.distanceMetres, accuracy: 1e-9, file: file, line: line)
            XCTAssertEqual(got.elapsedSeconds, want.elapsedSeconds, accuracy: 1e-9, file: file, line: line)
            XCTAssertEqual(got.durationSeconds, want.durationSeconds, accuracy: 1e-9, file: file, line: line)
            XCTAssertEqual(got.complete, want.complete, file: file, line: line)
        }
    }

    func testSeveralBoundariesInOneSegmentAndIncompleteTail() {
        expect(GpsSplitMath.calculate(points: [p(0, 0), p(3_200, 960_000)], splitMetres: 1_000), [
            s(1_000, 300, 300, true),
            s(1_000, 600, 300, true),
            s(1_000, 900, 300, true),
            s(200, 960, 60, false),
        ])
    }

    func testExactBoundariesHaveNoPhantomTail() {
        expect(GpsSplitMath.calculate(points: [p(0, 0), p(1_000, 300_000),
                                              p(2_000, 610_000)], splitMetres: 1_000), [
            s(1_000, 300, 300, true),
            s(1_000, 610, 310, true),
        ])
    }

    func testCrossingInterpolatesWithinUnevenSegment() {
        expect(GpsSplitMath.calculate(points: [p(0, 0), p(750, 150_000),
                                              p(1_250, 450_000)], splitMetres: 1_000), [
            s(1_000, 300, 300, true),
            s(250, 450, 150, false),
        ])
    }

    func testPauseSeamUsesActiveClockWithoutBridging() {
        // A long wall-clock pause is already removed from activeElapsedMs by the timed-route source.
        expect(GpsSplitMath.calculate(points: [p(0, 0), p(900, 300_000),
                                              p(900, 300_000, 1), p(1_100, 360_000, 1),
                                              p(1_500, 480_000, 1)], splitMetres: 1_000), [
            s(1_000, 330, 330, true),
            s(500, 480, 150, false),
        ])
    }

    func testPauseSeamCannotCreateUnobservedDistance() {
        XCTAssertTrue(GpsSplitMath.calculate(points: [p(0, 0), p(900, 300_000),
                                                        p(1_500, 360_000, 1)], splitMetres: 1_000).isEmpty)
    }

    func testRejectsZeroNegativeAndReorderedActiveTime() {
        let invalid: [[GpsSplitMath.Point]] = [
            [p(0, 0), p(1_000, 0)],
            [p(0, -1), p(1_000, 300_000)],
            [p(0, 300_000), p(1_000, 200_000)],
            [p(0, 0), p(1_000, 300_000), p(1_200, 300_000)],
        ]
        for points in invalid {
            XCTAssertTrue(GpsSplitMath.calculate(points: points, splitMetres: 1_000).isEmpty)
        }
    }

    func testRejectsReorderedDistanceAndSegments() {
        XCTAssertTrue(GpsSplitMath.calculate(points: [p(0, 0), p(1_000, 300_000),
                                                        p(900, 400_000)], splitMetres: 1_000).isEmpty)
        XCTAssertTrue(GpsSplitMath.calculate(points: [p(0, 0, 1), p(1_000, 300_000, 0)],
                                             splitMetres: 1_000).isEmpty)
    }

    func testInvalidInputsFailClosed() {
        for stride in [0.0, -1.0, Double.nan, Double.infinity] {
            XCTAssertTrue(GpsSplitMath.calculate(points: [p(0, 0), p(1_000, 300_000)],
                                                 splitMetres: stride).isEmpty)
        }
        XCTAssertTrue(GpsSplitMath.calculate(points: [p(0, 0), p(.nan, 300_000)],
                                             splitMetres: 1_000).isEmpty)
        XCTAssertTrue(GpsSplitMath.calculate(points: [p(0, 0), p(.infinity, 300_000)],
                                             splitMetres: 1_000).isEmpty)
        XCTAssertTrue(GpsSplitMath.calculate(points: [p(1, 0), p(1_000, 300_000)],
                                             splitMetres: 1_000).isEmpty)
    }

    func testLastIncompleteSplitWithoutFullBoundary() {
        expect(GpsSplitMath.calculate(points: [p(0, 0), p(400, 180_000)], splitMetres: 1_000), [
            s(400, 180, 180, false),
        ])
    }

    func testMileStrideIsOnlyAUnitChoice() {
        expect(GpsSplitMath.calculate(points: [p(0, 0), p(3_218.688, 1_200_000)],
                                      splitMetres: 1_609.344), [
            s(1_609.344, 600, 600, true),
            s(1_609.344, 1_200, 600, true),
        ])
    }

    func testStationaryDuplicateTimeIsNotMovement() {
        expect(GpsSplitMath.calculate(points: [p(0, 0), p(0, 0), p(1_500, 600_000)],
                                      splitMetres: 1_000), [
            s(1_000, 400, 400, true),
            s(500, 600, 200, false),
        ])
    }

    func testEmptyStationaryAndExcessiveOutput() {
        XCTAssertTrue(GpsSplitMath.calculate(points: [], splitMetres: 1_000).isEmpty)
        XCTAssertTrue(GpsSplitMath.calculate(points: [p(0, 0)], splitMetres: 1_000).isEmpty)
        XCTAssertTrue(GpsSplitMath.calculate(points: [p(0, 0), p(0, 90_000)],
                                             splitMetres: 1_000).isEmpty)
        XCTAssertTrue(GpsSplitMath.calculate(points: [p(0, 0), p(11_000, 3_300_000)],
                                             splitMetres: 1).isEmpty)
    }
}
