import XCTest
@testable import Strand

/// Regressions for the #2523 shared Oura notification stream: unsolicited replays cannot double-store
/// a night while NOOP is idle, and a shared final summary must not cut off an owned batch tail.
final class OuraHistoryAdmissionTests: XCTestCase {
    private let replay = ["night 0x60 +1s", "night 0x60 +2s", "night 0x60 +3s"]

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
        let admission = OuraHistoryAdmission { delay in timer = now + delay }
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
