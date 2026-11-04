import XCTest
@testable import Strand

/// Regressions for the #2523 shared Oura notification stream: unsolicited replays cannot double-store
/// a night, while NOOP's own batch tail must survive the ring's early final-summary notification.
final class OuraHistoryAdmissionTests: XCTestCase {
    private let replay = ["night 0x60 +1s", "night 0x60 +2s", "night 0x60 +3s"]

    func testUnsolicitedReplayAfterOurDrainIsIgnored() {
        var admission = OuraHistoryAdmission()
        admission.startRequest()
        admission.endRequest()
        XCTAssertEqual(admission.admit(replay, whileFetching: false), [])
        XCTAssertEqual(admission.admit(replay, whileFetching: true), [],
                       "a foreign GetEvents reply cannot reopen an ended NOOP drain")
    }

    func testOwnedDrainAcceptsAllRecordsInOrder() {
        var admission = OuraHistoryAdmission()
        admission.startRequest()
        XCTAssertEqual(admission.admit(replay, whileFetching: true), replay)
        XCTAssertEqual(admission.admit(replay, whileFetching: false), [],
                       "a streaming/unauthenticated phase cannot persist banked history")
    }

    func testEarlyFinalSummaryKeepsBatchTailUntilQuiet() {
        var admission = OuraHistoryAdmission()
        admission.startRequest()
        admission.noteFinalSummary()
        XCTAssertTrue(admission.finalSummaryPending)
        XCTAssertEqual(admission.admit(replay, whileFetching: true), replay,
                       "the final summary precedes valid tail records")
        admission.endRequest()
        XCTAssertFalse(admission.finalSummaryPending)
        XCTAssertEqual(admission.admit(replay, whileFetching: false), [])
    }

    func testNewRequestClearsStaleFinalSummary() {
        var admission = OuraHistoryAdmission()
        admission.startRequest()
        admission.noteFinalSummary()
        admission.startRequest()
        XCTAssertFalse(admission.finalSummaryPending)
        XCTAssertEqual(admission.admit(replay, whileFetching: true), replay)
        admission.noteFinalSummary()
        admission.noteContinuationSummary()
        XCTAssertFalse(admission.finalSummaryPending)
    }

    func testForeignFinalSummaryCannotOpenAdmission() {
        var admission = OuraHistoryAdmission()
        admission.noteFinalSummary()
        XCTAssertFalse(admission.fetchOutstanding)
        XCTAssertFalse(admission.finalSummaryPending)
        XCTAssertEqual(admission.admit(replay, whileFetching: true), [])
    }
}
