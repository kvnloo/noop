import XCTest
@testable import Strand

/// Canonical date identity, not a display-format alias, determines each completion.
/// Mirrored in ExperimentAdherenceCanonicalDateTest.kt. All inputs are synthetic.
final class ExperimentAdherenceCanonicalDateTests: XCTestCase {
    func testSignedDateAliasesCannotMultiplyACompletion() {
        let aliases: Set<String> = ["2026-+1-01", "2026-01-+1", "2026-+1-+1"]
        XCTAssertEqual(ExperimentAdherence.fraction(
            yesDays: aliases.union(["2026-01-01"]), startedDay: "2026-01-01",
            today: "2026-01-01", durationDays: 1), 1.0)
        // Reject evidence rather than merely clamping an invalid ratio to 100%.
        XCTAssertEqual(ExperimentAdherence.fraction(
            yesDays: aliases, startedDay: "2026-01-01",
            today: "2026-01-01", durationDays: 1), 0.0)
        for alias in aliases {
            XCTAssertFalse(ExperimentAdherence.includes(
                alias, startedDay: "2026-01-01", today: "2026-01-01", durationDays: 1), alias)
        }
    }

    func testMalformedDayKeysFailClosedInEveryArgument() {
        let invalid = ["2026-+1-01", "2026-01-+1", "+026-01-01", "0000-01-01",
                       "2026-1-01", "2026-01-001", "2026-01-01\n", " 2026-01-01",
                       "２０２６-01-01", "2026-01-٠١", "2026-02-29", "2026-04-31",
                       "2026-00-01", "2026-13-01", "2026-01-00", "2026-01-01T00:00:00Z"]
        for key in invalid {
            XCTAssertEqual(ExperimentAdherence.fraction(
                yesDays: [key], startedDay: "2026-01-01", today: "2026-12-31", durationDays: 365), 0, key)
            XCTAssertEqual(ExperimentAdherence.fraction(
                yesDays: ["2026-01-01"], startedDay: key, today: "2026-12-31", durationDays: 365), 0, key)
            XCTAssertEqual(ExperimentAdherence.fraction(
                yesDays: ["2026-01-01"], startedDay: "2026-01-01", today: key, durationDays: 365), 0, key)
            XCTAssertFalse(ExperimentAdherence.includes(key, startedDay: "2026-01-01",
                                                       today: "2026-12-31", durationDays: 365), key)
            XCTAssertFalse(ExperimentAdherence.includes("2026-01-01", startedDay: key,
                                                       today: "2026-12-31", durationDays: 365), key)
            XCTAssertFalse(ExperimentAdherence.includes("2026-01-01", startedDay: "2026-01-01",
                                                       today: key, durationDays: 365), key)
        }
    }

    func testValidLeapAndCalendarWindowsAreUnchanged() {
        let cases: [(String, String, Set<String>, Double)] = [
            ("1900-02-28", "1900-03-01", ["1900-03-01"], 0.5),
            ("2000-02-28", "2000-03-01", ["2000-02-29"], 1.0 / 3.0),
            ("2100-02-28", "2100-03-01", ["2100-03-01"], 0.5),
            ("2026-12-31", "2027-01-02", ["2026-12-31", "2027-01-02"], 2.0 / 3.0),
        ]
        for (start, today, yes, expected) in cases {
            XCTAssertEqual(ExperimentAdherence.fraction(yesDays: yes, startedDay: start,
                                                         today: today, durationDays: 14), expected, accuracy: 1e-12)
            for day in yes {
                XCTAssertTrue(ExperimentAdherence.includes(day, startedDay: start,
                                                          today: today, durationDays: 14))
            }
        }
    }

    func testMultipleAliasesCannotExpandThePrescribedWindow() {
        var keys = Set<String>()
        for day in 1...9 {
            keys.insert("2026-01-0\(day)")
            keys.insert("2026-+1-0\(day)")
            keys.insert("2026-01-+\(day)")
            keys.insert("2026-+1-+\(day)")
        }
        XCTAssertEqual(ExperimentAdherence.fraction(yesDays: keys, startedDay: "2026-01-01",
                                                     today: "2026-01-09", durationDays: 7), 1.0)
        let eligible = keys.filter {
            ExperimentAdherence.includes($0, startedDay: "2026-01-01", today: "2026-01-09", durationDays: 7)
        }
        XCTAssertEqual(eligible.count, 7)
        XCTAssertFalse(eligible.contains("2026-01-08"))
        XCTAssertFalse(eligible.contains("2026-01-09"))
    }
}
