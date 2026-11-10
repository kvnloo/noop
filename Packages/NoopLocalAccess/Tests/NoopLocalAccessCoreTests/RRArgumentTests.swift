import XCTest
@testable import NoopLocalAccessCore

final class RRArgumentTests: XCTestCase {
    func testOutOfRangeNumbersDoNotTrapIntegerConversion() {
        for value in [Double.infinity, -Double.infinity, Double.nan, 1e100, -1e100, Double(Int.max)] {
            XCTAssertNil(JSONValue.double(value).intValue)
        }
        XCTAssertEqual(JSONValue.double(1.9).intValue, 1)
        XCTAssertEqual(JSONValue.double(Double(Int.min)).intValue, Int.min)
    }

    func testInvalidExplicitTimestampsCannotFallBackToRecentWindow() throws {
        let url = try TemporaryDatabase.withRRIntervals()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let dispatcher = NoopToolDispatcher(configuration: LocalAccessConfiguration(databasePath: url.path))
        for value: JSONValue in [.null, .bool(true), .string("bad"), .double(100.5), .double(1e100)] {
            XCTAssertThrowsError(try dispatcher.dispatch(name: "rr_series", arguments: ["from_ts": value, "to_ts": value])) { error in
                guard case .invalidParams = error as? LocalAccessError else {
                    return XCTFail("Expected invalidParams, got \(error)")
                }
            }
        }
        let payload = try dispatcher.dispatch(name: "rr_series", arguments: ["from_ts": .double(100), "to_ts": .double(103)])
        XCTAssertEqual(payload.objectValue?["returned"], .int(4))
    }
}
