import XCTest
@testable import Strand

/// Oracle cases also run in the Android twin; no sensor measurements are required.
final class ExperimentAdherenceTests: XCTestCase {
    func testJournalCompletionsDoNotDependOnOutcomeSamples() {
        XCTAssertEqual(
            ExperimentAdherence.fraction(yesDays: ["2026-10-19"],
                                         startedDay: "2026-10-19", today: "2026-10-19",
                                         durationDays: 14),
            1.0, accuracy: 1e-12
        )
        XCTAssertEqual(
            ExperimentAdherence.fraction(yesDays: ["2026-10-16", "2026-10-18"],
                                         startedDay: "2026-10-15", today: "2026-10-19",
                                         durationDays: 14),
            0.4, accuracy: 1e-12
        )
    }

    func testBoundedWindowDoesNotExceedOneHundredPercent() {
        XCTAssertEqual(
            ExperimentAdherence.fraction(
                yesDays: ["2026-10-01", "2026-10-16", "2026-10-21", "2026-10-22"],
                startedDay: "2026-10-15", today: "2026-10-25", durationDays: 7),
            2.0 / 7.0, accuracy: 1e-12
        )
        XCTAssertEqual(
            ExperimentAdherence.fraction(
                yesDays: ["2026-10-01", "2026-10-02", "2026-10-03", "2026-10-09"],
                startedDay: "2026-10-01", today: "2026-10-19", durationDays: 3),
            1.0, accuracy: 1e-12
        )
    }

    func testCivilDaysAcrossDstAndNewYear() {
        XCTAssertEqual(
            ExperimentAdherence.fraction(
                yesDays: ["2026-10-31", "2026-11-03"],
                startedDay: "2026-10-30", today: "2026-11-03", durationDays: 14),
            0.4, accuracy: 1e-12
        )
        XCTAssertEqual(
            ExperimentAdherence.fraction(
                yesDays: ["2026-12-31", "2027-01-02"],
                startedDay: "2026-12-30", today: "2027-01-02", durationDays: 14),
            0.5, accuracy: 1e-12
        )
    }

    func testMissingInvalidOrFutureDatesAreNotCompletions() {
        XCTAssertEqual(ExperimentAdherence.fraction(
            yesDays: ["2026-10-19"], startedDay: "2026-10-20",
            today: "2026-10-19", durationDays: 14), 0)
        XCTAssertEqual(ExperimentAdherence.fraction(
            yesDays: ["2026-10-19"], startedDay: "2026-02-30",
            today: "2026-10-19", durationDays: 14), 0)
        XCTAssertEqual(ExperimentAdherence.fraction(
            yesDays: ["2026-10-19", "not-a-day"], startedDay: "2026-10-19",
            today: "2026-10-19", durationDays: 14), 1)
        XCTAssertEqual(ExperimentAdherence.fraction(
            yesDays: [], startedDay: "2026-10-19",
            today: "2026-10-19", durationDays: 14), 0)
    }

    func testSensorReadingsAfterDeadlineDoNotExtendExperiment() {
        let observedDays = ["2026-10-14", "2026-10-15", "2026-10-17",
                            "2026-10-21", "2026-10-22", "2026-10-30", "invalid"]
        let eligible = observedDays.filter {
            ExperimentAdherence.includes($0, startedDay: "2026-10-15",
                                         today: "2026-10-30", durationDays: 7)
        }
        XCTAssertEqual(eligible, ["2026-10-15", "2026-10-17", "2026-10-21"])
        XCTAssertFalse(ExperimentAdherence.includes(
            "2026-10-21", startedDay: "2026-10-15", today: "2026-10-19", durationDays: 7))
    }

}
