import XCTest
@testable import StrandAnalytics

/// Array-run oracle, independent of the production epoch-day map/set algorithm.
/// Mirrored in JournalConsistencyPropertyTest.kt; no clock, device or health data.
final class JournalConsistencyPropertyTests: XCTestCase {
    func testEveryTriStateWeekAgainstIndependentRunOracle() throws {
        let days = (13...19).map { "2026-10-\($0)" }
        let windows = [Int.min, 1, 3, 7, 14, 90, Int.max]
        // 3^7 combinations: nil = no row, false = explicit No, true = explicit Yes.
        for pattern in 0..<2187 {
            var digits = pattern
            let states: [Bool?] = (0..<7).map { _ in
                let digit = digits % 3
                digits /= 3
                return digit == 0 ? nil : digit == 2
            }
            let logged = states.indices.filter { states[$0] != nil }
            let keys = logged.map { days[$0] }
            let answers = logged.map { states[$0]! }
            let runs = states.split(whereSeparator: { $0 != true })
            let longest = runs.map(\.count).max() ?? 0
            let active = states[6] == nil ? Array(states.dropLast()) : states
            let current = active.reversed().prefix(while: { $0 == true }).count
            for requested in windows {
                let window = max(1, min(requested, 90))
                let recent = states.suffix(window)
                let yes = recent.filter { $0 == true }.count
                let no = recent.filter { $0 == false }.count
                let actual = try XCTUnwrap(JournalConsistency.summarize(
                    dayKeys: keys, answeredYes: answers, today: days[6], windowDays: requested
                ))
                let expected = JournalConsistency.Summary(
                    windowDays: window, yesDays: yes, noDays: no,
                    unloggedDays: window - yes - no, currentYesStreak: current,
                    longestYesStreak: longest, todayAnswer: states[6]
                )
                XCTAssertEqual(actual, expected, "pattern=\(pattern), window=\(requested)")
            }
        }
    }

    func testLaterEditsWinWithoutDependingOnChronologicalInputOrder() throws {
        let expected = try XCTUnwrap(JournalConsistency.summarize(
            dayKeys: ["2026-10-17", "2026-10-18", "2026-10-19"],
            answeredYes: [true, false, true], today: "2026-10-19"
        ))
        let actual = JournalConsistency.summarize(
            dayKeys: ["2026-10-19", "2026-10-18", "2026-10-17", "2026-10-20",
                      "2026-10-18", "2026-10-19", "2026-10-32", "2026-10-20"],
            answeredYes: [false, true, true, true, false, true, true, false],
            today: "2026-10-19"
        )
        XCTAssertEqual(actual, expected)
        // Replaying the same resolved rows must not double-count observations.
        XCTAssertEqual(JournalConsistency.summarize(
            dayKeys: ["2026-10-19", "2026-10-18", "2026-10-17",
                      "2026-10-19", "2026-10-18", "2026-10-17"],
            answeredYes: [true, false, true, true, false, true], today: "2026-10-19"
        ), expected)
    }

    func testGregorianCenturyAndYearBoundaryRuns() throws {
        let cases: [[String]] = [
            ["1900-02-28", "1900-03-01"],
            ["2000-02-28", "2000-02-29", "2000-03-01"],
            ["2100-02-28", "2100-03-01"],
            ["2400-02-28", "2400-02-29", "2400-03-01"],
            ["2026-12-31", "2027-01-01"],
            ["2026-03-07", "2026-03-08", "2026-03-09"],
            ["2026-10-31", "2026-11-01", "2026-11-02"],
        ]
        for days in cases {
            let actual = try XCTUnwrap(JournalConsistency.summarize(
                dayKeys: days, answeredYes: days.map { _ in true }, today: days.last!
            ))
            XCTAssertEqual(actual.yesDays, days.count, "\(days)")
            XCTAssertEqual(actual.currentYesStreak, days.count, "\(days)")
            XCTAssertEqual(actual.longestYesStreak, days.count, "\(days)")
        }
        for day in ["1900-02-29", "2100-02-29"] {
            XCTAssertNil(JournalConsistency.summarize(dayKeys: [], answeredYes: [], today: day))
        }
    }

    func testMalformedKeysCannotOverwriteAnExplicitAnswer() throws {
        let malformed = ["2026-00-19", "2026-13-19", "2026-10-00", "2026-04-31",
                         "2026-1-19", "2026-10-019", " 2026-10-19", "2026-10-19\n",
                         "2026/10/19", "２０２６-10-19", "2026-10-١٩", "2026-10-19T00:00:00Z"]
        for day in malformed {
            XCTAssertNil(JournalConsistency.summarize(dayKeys: [], answeredYes: [], today: day), day)
        }
        let actual = try XCTUnwrap(JournalConsistency.summarize(
            dayKeys: ["2026-10-19"] + malformed,
            answeredYes: [false] + malformed.map { _ in true }, today: "2026-10-19"
        ))
        XCTAssertEqual(actual.yesDays, 0)
        XCTAssertEqual(actual.noDays, 1)
        XCTAssertEqual(actual.unloggedDays, 6)
        XCTAssertEqual(actual.currentYesStreak, 0)
        XCTAssertEqual(actual.longestYesStreak, 0)
        XCTAssertEqual(actual.todayAnswer, false)
    }
}
