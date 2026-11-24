import XCTest
@testable import Strand

/// Swift twin of Android WorkoutRealtimeLeaseTest: same owners, edges and idempotence.
final class WorkoutRealtimeLeaseTests: XCTestCase {
    func testMenuBarOnlyTogglesItsOwnRequestWhileRecording() {
        var owners = 0
        var stops = 0
        func request() { owners += 1 }
        func release() {
            owners -= 1
            if owners == 0 { stops += 1 }
        }

        let workout = WorkoutRealtimeLease(onBegin: request, onEnd: release)
        workout.begin()
        var menu = MenuBarRealtimeRequest()
        XCTAssertFalse(menu.isHeld) // Button offers Start even though the recording armed HR.

        menu = menu.toggled(onStart: request, onStop: release)
        XCTAssertTrue(menu.isHeld)
        XCTAssertEqual(owners, 2)

        menu = menu.toggled(onStart: request, onStop: release)
        XCTAssertFalse(menu.isHeld)
        XCTAssertEqual(owners, 1)
        XCTAssertEqual(stops, 0)

        workout.end()
        XCTAssertEqual(owners, 0)
        XCTAssertEqual(stops, 1)
    }

    func testWorkoutOutlivesBothVisibleRealtimeScreens() {
        var owners = 0
        var commands: [String] = []
        func request() {
            if owners == 0 { commands.append("arm") }
            owners += 1
        }
        func release() {
            owners = max(0, owners - 1)
            if owners == 0 { commands.append("disarm") }
        }

        request() // Live tab
        let workout = WorkoutRealtimeLease(onBegin: request, onEnd: release)
        workout.begin() // recording
        request() // workout sheet
        XCTAssertEqual(owners, 3)

        release() // workout sheet disappears
        release() // Live tab disappears
        XCTAssertEqual(owners, 1)
        XCTAssertEqual(commands, ["arm"])

        workout.end() // last owner
        XCTAssertEqual(owners, 0)
        XCTAssertEqual(commands, ["arm", "disarm"])
    }

    func testRestoreAndEndAreIdempotent() {
        var starts = 0
        var stops = 0
        let workout = WorkoutRealtimeLease(onBegin: { starts += 1 }, onEnd: { stops += 1 })
        workout.begin()
        workout.begin()
        XCTAssertEqual(starts, 1)
        workout.end()
        workout.end()
        XCTAssertEqual(stops, 1)

        workout.begin()
        XCTAssertEqual(starts, 2)
        workout.end()
        XCTAssertEqual(stops, 2)
    }
}
