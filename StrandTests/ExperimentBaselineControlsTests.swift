import XCTest
@testable import Strand

/// Synthetic Journal observations only; no human biometrics or causal claims.
/// Mirrors ExperimentBaselineControlsTest.kt's vectors and day-key semantics.
final class ExperimentBaselineControlsTests: XCTestCase {
    private let outcomes: [String: Double] = [
        "2026-09-01": 70, // explicitly No
        "2026-09-02": 99, // never answered; NOT a control
        "2026-09-03": 75, // explicitly No
        "2026-09-04": 65, // answered Yes; NOT a control
        "2026-09-05": 79, // explicitly No
        "2026-09-06": 84, // after experiment start; NOT baseline
    ]
    private let noDays: Set<String> = [
        "2026-09-01", "2026-09-03", "2026-09-05", "2026-09-06",
    ]

    func testOnlyExplicitNoDaysBeforeStartAreControls() {
        XCTAssertEqual(
            experimentExplicitNoBaselineDays(
                outcomeDays: outcomes, explicitNoDays: noDays,
                before: "2026-09-06", limit: 14),
            ["2026-09-01", "2026-09-03", "2026-09-05"])
    }

    func testNoExplicitAnswersMeansNoBaseline() {
        XCTAssertEqual(
            experimentExplicitNoBaselineDays(
                outcomeDays: outcomes, explicitNoDays: [],
                before: "2026-09-06", limit: 14), [])
    }

    func testBaselineTakesLastMatchingDaysOnly() {
        XCTAssertEqual(
            experimentExplicitNoBaselineDays(
                outcomeDays: outcomes, explicitNoDays: noDays,
                before: "2026-09-06", limit: 2),
            ["2026-09-03", "2026-09-05"])
    }

    func testNoOutcomeMeansNoControlObservation() {
        XCTAssertEqual(
            experimentExplicitNoBaselineDays(
                outcomeDays: ["2026-09-02": 99], explicitNoDays: noDays,
                before: "2026-09-06", limit: 14), [])
    }

    func testNonFiniteBaselineValuesAreNeverCountedAsObservations() {
        var damaged = outcomes
        damaged["2026-09-03"] = .nan
        damaged["2026-09-05"] = .infinity
        XCTAssertEqual(
            experimentExplicitNoBaselineDays(
                outcomeDays: damaged, explicitNoDays: noDays,
                before: "2026-09-06", limit: 14),
            ["2026-09-01"])
    }

    func testNonFiniteInterventionDaysDoNotConsumeValidObservationSlots() {
        let measured: [String: Double] = [
            "2026-09-06": 71, "2026-09-07": .nan,
            "2026-09-08": 72, "2026-09-09": -.infinity,
            "2026-09-10": 73,
        ]
        XCTAssertEqual(
            experimentValidObservationDays(
                outcomeDays: measured, from: "2026-09-06",
                through: "2026-09-10", limit: 2),
            ["2026-09-06", "2026-09-08"])
    }
}
