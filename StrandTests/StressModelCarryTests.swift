import XCTest
import WhoopStore
@testable import Strand

/// StressModel carry (#543). The Today "Stress" metric derives against a 30-day RHR/HRV baseline. Today's
/// own daily row is often vitals-less until the overnight is analyzed (especially right after an app update
/// relaunches and re-runs the analyze pass), and every OTHER Today vital carries last night's value — Stress
/// used to be the one card that didn't, so it dropped to "Calibrating" while the rest showed numbers. These
/// pin the carry: score the newest day that actually has RHR/HRV. Twin of the Android StressModelTest.
final class StressModelCarryTests: XCTestCase {

    private func day(_ d: String, rhr: Int?, hrv: Double?) -> DailyMetric {
        DailyMetric(day: d, totalSleepMin: nil, efficiency: nil, deepMin: nil, remMin: nil,
                    lightMin: nil, disturbances: nil, restingHr: rhr, avgHrv: hrv, recovery: nil,
                    strain: nil, exerciseCount: nil)
    }

    // 31 days that all carry RHR + HRV: a full 30-day baseline plus one more scorable day.
    private var baseline: [DailyMetric] {
        (1...30).map { day(String(format: "2026-06-%02d", $0), rhr: 55, hrv: 60) }
            + [day("2026-07-01", rhr: 55, hrv: 60)]
    }

    func testVitalsLessTodayCarriesInsteadOfCalibrating() {
        // Control: today HAS vitals → builds (unchanged).
        XCTAssertNotNil(StressModel(days: baseline + [day("2026-07-02", rhr: 58, hrv: 45)], stored: []))
        // The fix: today has NO RHR/HRV yet (the post-update window) but a prior day does → carries, not nil.
        XCTAssertNotNil(StressModel(days: baseline + [day("2026-07-02", rhr: nil, hrv: nil)], stored: []),
                        "a vitals-less today must carry the last day with RHR/HRV, not calibrate")
    }

    func testNoVitalsAnywhereStillCalibrates() {
        // Genuine cold start: no day has RHR/HRV and nothing stored → honestly calibrating (nil).
        let days = (1...5).map { day("2026-07-0\($0)", rhr: nil, hrv: nil) }
        XCTAssertNil(StressModel(days: days, stored: []))
    }

    func testStoredStressOnLatestVitalsLessDayIsUsedNotSkipped() {
        // An imported latest day carries a STORED stress value but no RHR/HRV (e.g. a Xiaomi / Garmin /
        // WHOOP export). The original gate honoured it; the carry must NOT skip it back to an older vitals
        // day. The stored 2.5 must win over any derived carry.
        let days = baseline + [day("2026-07-02", rhr: nil, hrv: nil)]
        let model = StressModel(days: days, stored: [(day: "2026-07-02", value: 2.5)])
        XCTAssertNotNil(model)
        XCTAssertEqual(model?.score ?? -1, 2.5, accuracy: 0.001,
                       "the latest day's stored stress must win over a carry")
    }
    /// #2818: a Charge reset must not compare new HRV/RHR with the discarded era.
    func testEpochCutsAreIndependentAndKeepHistoricalDayContext() throws {
        let old = (14...18).map {
            day(String(format: "2026-07-%02d", $0), rhr: 55, hrv: 90)
        }
        let recent = [
            day("2026-07-19", rhr: 59, hrv: 77),
            day("2026-07-20", rhr: 60, hrv: 78),
            day("2026-07-21", rhr: 61, hrv: 79),
            day("2026-07-22", rhr: 60, hrv: 78),
        ]
        let all = old + recent + [day("2026-07-23", rhr: 59, hrv: 82)]
        let legacy = try XCTUnwrap(StressModel(days: all, stored: []))
        // HRV reset Jul 18 noon -> Jul 19 kept. RHR reset Jul 20 noon -> Jul 21 kept.
        let recalibrated = try XCTUnwrap(StressModel(
            days: all, stored: [], hrvBaselineEpoch: 1_784_376_000,
            recoveryBaselineEpoch: 1_784_548_800
        ))
        XCTAssertEqual(legacy.hrvDelta ?? .nan, -8.0 / 3.0, accuracy: 1e-9)
        XCTAssertEqual(legacy.rhrDelta ?? .nan, 16.0 / 9.0, accuracy: 1e-9)
        XCTAssertEqual(recalibrated.hrvDelta ?? .nan, 4.0, accuracy: 1e-9)
        XCTAssertEqual(recalibrated.rhrDelta ?? .nan, -1.5, accuracy: 1e-9)
        XCTAssertLessThan(recalibrated.score, 1.0)

        // An historical, pre-reset scored day retains its own preceding nights.
        let historical = Array(old.prefix(4))
        let before = try XCTUnwrap(StressModel(days: historical, stored: []))
        let withFutureReset = try XCTUnwrap(StressModel(
            days: historical, stored: [], hrvBaselineEpoch: 1_784_376_000,
            recoveryBaselineEpoch: 1_784_548_800
        ))
        XCTAssertEqual(before.hrvDelta ?? .nan, withFutureReset.hrvDelta ?? .nan, accuracy: 1e-9)
        XCTAssertEqual(before.rhrDelta ?? .nan, withFutureReset.rhrDelta ?? .nan, accuracy: 1e-9)
    }
}
