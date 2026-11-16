import XCTest
@testable import StrandAnalytics

/// Mirrored fixtures: android/.../JournalConsistencyTest.kt.
final class JournalConsistencyTests: XCTestCase {
    private let today = "2026-10-19"

    private func summary(_ days: [String], _ yes: [Bool], today: String? = nil,
                         window: Int = 7) -> JournalConsistency.Summary? {
        JournalConsistency.summarize(dayKeys: days, answeredYes: yes,
                                     today: today ?? self.today, windowDays: window)
    }

    func testBlankDaysAreNotFalse() {
        let s = summary([], [])!
        XCTAssertEqual(s.yesDays, 0)
        XCTAssertEqual(s.noDays, 0)
        XCTAssertEqual(s.unloggedDays, 7)
        XCTAssertNil(s.todayAnswer)
        XCTAssertEqual(s.currentYesStreak, 0)
    }

    func testExplicitYesNoAndMissingRemainDistinct() {
        let s = summary(["2026-10-16", "2026-10-17", "2026-10-19"],
                        [true, false, true], window: 4)!
        XCTAssertEqual([s.yesDays, s.noDays, s.unloggedDays], [2, 1, 1])
        XCTAssertEqual([s.currentYesStreak, s.longestYesStreak], [1, 1])
        XCTAssertEqual(s.todayAnswer, true)
    }

    func testUnloggedTodayRetainsYesterdaysRun() {
        let s = summary(["2026-10-17", "2026-10-18"], [true, true])!
        XCTAssertEqual([s.currentYesStreak, s.longestYesStreak], [2, 2])
        XCTAssertNil(s.todayAnswer)
    }

    func testExplicitNoTodayBreaksStreakWithoutErasingHistory() {
        let s = summary(["2026-10-17", "2026-10-18", "2026-10-19"],
                        [true, true, false])!
        XCTAssertEqual([s.currentYesStreak, s.longestYesStreak], [0, 2])
        XCTAssertEqual(s.todayAnswer, false)
        XCTAssertEqual(s.noDays, 1)
    }

    func testLaterEditWinsFutureAndInvalidRowsIgnored() {
        let s = summary(["2026-10-18", "2026-10-18", "2026-10-20",
                         "not-a-day", "2026-10-19"],
                        [true, false, true, true, true], window: 2)!
        XCTAssertEqual([s.yesDays, s.noDays, s.unloggedDays], [1, 1, 0])
        XCTAssertEqual([s.currentYesStreak, s.longestYesStreak], [1, 1])
    }

    func testLeapDayUsesCivilDaysNotSeconds() {
        let s = summary(["2024-02-28", "2024-02-29", "2024-03-01"],
                        [true, true, true], today: "2024-03-01")!
        XCTAssertEqual([s.currentYesStreak, s.longestYesStreak], [3, 3])
    }

    func testHistoricLongestNotLimitedToWindow() {
        let s = summary(["2026-10-01", "2026-10-02", "2026-10-03", "2026-10-19"],
                        [true, true, true, true])!
        XCTAssertEqual([s.currentYesStreak, s.longestYesStreak], [1, 3])
        XCTAssertEqual([s.yesDays, s.noDays, s.unloggedDays], [1, 0, 6])
    }

    func testWindowClampedAndPairedPrefixOnly() {
        let one = summary(["2026-10-19", "2026-10-18"], [true], window: 0)!
        XCTAssertEqual([one.windowDays, one.yesDays, one.unloggedDays], [1, 1, 0])
        let capped = summary([], [], window: 999)!
        XCTAssertEqual([capped.windowDays, capped.unloggedDays], [90, 90])
    }

    func testImpossibleCalendarDatesNeverBecomeObservations() {
        let s = summary(["2026-10-32", "2026-02-29", "2026-11-01"],
                        [true, true, false], today: "2026-11-01")!
        XCTAssertEqual([s.yesDays, s.noDays, s.unloggedDays], [0, 1, 6])
        XCTAssertEqual([s.currentYesStreak, s.longestYesStreak], [0, 0])
        XCTAssertEqual(s.todayAnswer, false)
        XCTAssertNil(summary([], [], today: "2026-02-29"))
        XCTAssertNil(summary([], [], today: "2026-1-1"))
    }

    func testBadTodayReturnsNoSummary() {
        XCTAssertNil(summary(["2026-10-19"], [true], today: "bad"))
    }
}
