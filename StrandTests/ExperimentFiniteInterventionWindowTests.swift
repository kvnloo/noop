import XCTest
@testable import Strand

/// Synthetic outcome days only. Mirrors ExperimentFiniteInterventionWindowTest.kt.
/// These test a selection rule, not a physiological or causal-effect estimate.
final class ExperimentFiniteInterventionWindowTests: XCTestCase {
    private let measured: [String: Double] = [
        "2026-09-05": 60, // Before start.
        "2026-09-06": 71,
        "2026-09-07": .nan,
        "2026-09-08": 72,
        "2026-09-09": -.infinity,
        "2026-09-10": 73,
        "2026-09-11": .infinity,
        "2026-09-12": 75, // After the experiment's end.
    ]

    func testInvalidObservationsDoNotConsumeDurationSlots() {
        XCTAssertEqual(
            experimentValidObservationDays(
                outcomeDays: measured, from: "2026-09-06",
                through: "2026-09-10", limit: 2),
            ["2026-09-06", "2026-09-08"])
    }

    func testWindowBoundsAreInclusiveAndChronological() {
        XCTAssertEqual(
            experimentValidObservationDays(
                outcomeDays: measured, from: "2026-09-08",
                through: "2026-09-10", limit: 10),
            ["2026-09-08", "2026-09-10"])
    }

    func testNoFiniteObservationsReturnsEmptyWindow() {
        let invalid: [String: Double] = [
            "2026-09-06": .nan,
            "2026-09-07": .infinity,
            "2026-09-08": -.infinity,
        ]
        XCTAssertEqual(
            experimentValidObservationDays(
                outcomeDays: invalid, from: "2026-09-06",
                through: "2026-09-10", limit: 3), [])
    }

    func testNonpositiveLimitNormalizesToOne() {
        XCTAssertEqual(
            experimentValidObservationDays(
                outcomeDays: measured, from: "2026-09-06",
                through: "2026-09-10", limit: 0),
            ["2026-09-06"])
    }

    func testUnloggedFiniteDaysStillConsumeWindowSlots() {
        let loggedDays: Set<String> = ["2026-09-10"]
        let window = experimentValidObservationDays(
            outcomeDays: measured, from: "2026-09-06",
            through: "2026-09-10", limit: 2)
        XCTAssertEqual(window, ["2026-09-06", "2026-09-08"])
        XCTAssertEqual(window.filter { loggedDays.contains($0) }, [])
    }

    func testLoggedInterventionValuesRemainFinite() {
        let loggedDays: Set<String> = [
            "2026-09-06", "2026-09-07", "2026-09-09", "2026-09-10",
        ]
        let window = experimentValidObservationDays(
            outcomeDays: measured, from: "2026-09-06",
            through: "2026-09-10", limit: 3)
        let values = window.filter { loggedDays.contains($0) }
            .compactMap { measured[$0] }
        XCTAssertEqual(window, ["2026-09-06", "2026-09-08", "2026-09-10"])
        XCTAssertEqual(values, [71, 73])
        XCTAssertEqual(values.reduce(0.0, +) / Double(values.count), 72.0)
    }
}
