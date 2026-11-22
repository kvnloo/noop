import XCTest
import StrandAnalytics

final class PersonalExperimentTests: XCTestCase {
    private func snapshot(controlDays: Set<String>) -> PersonalExperiment.Snapshot {
        PersonalExperiment.snapshot(
            outcomeDays: ["2026-01-01": 20, "2026-01-02": 40, "2026-01-03": 100,
                          "2026-01-04": 60, "2026-01-05": 80],
            loggedDays: ["2026-01-01", "2026-01-05"],
            controlDays: controlDays,
            startedDay: "2026-01-04", today: "2026-01-05", daysSinceStart: 1,
            durationDays: 14, baselineDays: 14
        )
    }

    // The same fixture drives the compiled Swift oracle in the Kotlin test.
    func testBaselineUsesOnlyExplicitNoDays() {
        let result = snapshot(controlDays: ["2026-01-02", "2026-01-04"])
        XCTAssertEqual(result.baselineCount, 1)
        XCTAssertEqual(result.baselineMean, 40)
        XCTAssertEqual(result.interventionCount, 1)
        XCTAssertEqual(result.interventionMean, 80)
        XCTAssertEqual(result.delta, 40)
        XCTAssertEqual(result.compliance, 50)
        XCTAssertTrue(result.loggedToday)
        XCTAssertEqual(result.daysElapsed, 2)
        XCTAssertEqual(result.durationDays, 14)
        XCTAssertEqual(result.confidence, .low)
    }

    func testNoPreStartControlsLeavesBaselineAndDeltaUnavailable() {
        let result = snapshot(controlDays: ["2026-01-04"])
        XCTAssertEqual(result.baselineCount, 0)
        XCTAssertNil(result.baselineMean)
        XCTAssertNil(result.delta)
        XCTAssertEqual(result.interventionMean, 80)
    }
}
