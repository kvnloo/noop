import XCTest
@testable import Strand

/// Regressions for the #2523 shared Oura notification stream: unsolicited replays cannot double-store
/// a night while NOOP is idle, and a shared final summary must not cut off an owned batch tail.
final class OuraHistoryAdmissionTests: XCTestCase {
    private let replay = ["night 0x60 +1s", "night 0x60 +2s", "night 0x60 +3s"]

    func testContinuousRecordsCannotExtendDrainPastDeadline() {
        var now = 0.0
        var timer = 0.0
        var closedAt: TimeInterval?
        let admission = OuraHistoryAdmission(now: { now }, onDeadline: { _ in closedAt = now }) {
            timer = now + $0
        }
        func advance(to time: TimeInterval) {
            now = time
            if timer <= now, admission.takeIdleTimeout(whileFetching: true) { closedAt = now }
        }

        admission.startRequest()
        for arrival in 0..<300 {
            advance(to: Double(arrival))
            XCTAssertEqual(admission.admit(replay, whileFetching: true), replay)
        }
        advance(to: 300)
        XCTAssertEqual(closedAt, 300)
        advance(to: 301)
        XCTAssertEqual(admission.admit(replay, whileFetching: true), [])
    }

    func testRecordsAtDeadlineAreRejectedEvenIfTimerHasNotRun() {
        var now = 0.0
        var closed = false
        let admission = OuraHistoryAdmission(now: { now }, onDeadline: { _ in closed = true })
        admission.startRequest()
        now = 300
        XCTAssertEqual(admission.admit(replay, whileFetching: true), [])
        XCTAssertTrue(closed)
    }

    func testContinuationDoesNotResetDeadline() {
        var now = 1_000.0
        var delay = 0.0
        let admission = OuraHistoryAdmission(now: { now }) { delay = $0 }
        admission.startRequest()
        now += 298
        XCTAssertTrue(admission.handleSummary(moreData: true, whileFetching: true))
        XCTAssertTrue(admission.continueRequest())
        XCTAssertEqual(delay, 2)
        now += 2
        XCTAssertFalse(admission.continueRequest())
    }

    func testUnattributedFinalAfterRecordsFallsBackToInactivity() {
        var now = 0.0
        var timer = 0.0
        let admission = OuraHistoryAdmission(now: { now }) { timer = now + $0 }
        admission.startRequest()
        now = 1
        XCTAssertEqual(admission.admit(replay, whileFetching: true), replay)
        now = 2
        XCTAssertFalse(admission.handleSummary(moreData: false, whileFetching: true))
        XCTAssertEqual(timer, 11, "the final cannot shorten or extend inactivity")
    }

    func testUnsolicitedReplayAfterOurDrainIsIgnored() {
        let admission = OuraHistoryAdmission()
        admission.startRequest()
        admission.endRequest()
        XCTAssertEqual(admission.admit(replay, whileFetching: false), [])
        XCTAssertEqual(admission.admit(replay, whileFetching: true), [],
                       "a foreign GetEvents reply cannot reopen an ended NOOP drain")
    }

    func testOwnedDrainAcceptsAllRecordsInOrder() {
        let admission = OuraHistoryAdmission()
        admission.startRequest()
        XCTAssertEqual(admission.admit(replay, whileFetching: true), replay)
        XCTAssertEqual(admission.admit(replay, whileFetching: false), [],
                       "a streaming/unauthenticated phase cannot persist banked history")
    }

    func testForeignFinalDoesNotCutOffDelayedOwnedTail() {
        var now = 0.0
        var timer: TimeInterval?
        var timedOut = false
        let admission = OuraHistoryAdmission(now: { now }) { delay in timer = now + delay }
        func advance(to time: TimeInterval) {
            now = time
            if let deadline = timer, deadline <= now {
                timer = nil
                timedOut = admission.takeIdleTimeout(whileFetching: true)
            }
        }

        admission.startRequest()
        XCTAssertEqual(admission.admit(replay, whileFetching: true), replay)
        XCTAssertFalse(admission.handleSummary(moreData: false, whileFetching: true))
        advance(to: 1.501)
        let tail = ["tail"]
        XCTAssertEqual(admission.admit(tail, whileFetching: true), tail)
        advance(to: 10)
        XCTAssertFalse(timedOut)
        XCTAssertEqual(admission.admit([] as [String], whileFetching: true), [])
        advance(to: 11.501)
        XCTAssertTrue(timedOut)
        XCTAssertEqual(admission.admit(tail, whileFetching: true), [])
    }

    func testFinalBeforeAnyRecordsDoesNotRescheduleRequestTimeout() {
        var delays: [TimeInterval] = []
        let admission = OuraHistoryAdmission { delays.append($0) }
        admission.startRequest()
        XCTAssertFalse(admission.handleSummary(moreData: false, whileFetching: true))
        XCTAssertEqual(delays, [10])
    }

    func testOnlyMoreDataSummaryDuringOwnedFetchCanContinue() {
        let admission = OuraHistoryAdmission()
        XCTAssertFalse(admission.handleSummary(moreData: true, whileFetching: true))
        admission.startRequest()
        XCTAssertFalse(admission.handleSummary(moreData: true, whileFetching: false))
        XCTAssertTrue(admission.handleSummary(moreData: true, whileFetching: true))
        XCTAssertFalse(admission.handleSummary(moreData: false, whileFetching: true))
    }
}
