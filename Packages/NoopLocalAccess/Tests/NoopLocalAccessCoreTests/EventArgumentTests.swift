import XCTest
@testable import NoopLocalAccessCore

final class EventArgumentTests: XCTestCase {
    func testMalformedExplicitBoundsAreRejectedBeforeOpeningDatabase() {
        let dispatcher = NoopToolDispatcher(configuration: LocalAccessConfiguration(databasePath: "/unused"))
        let invalid: [JSONValue] = [.null, .bool(true), .string("bad"), .array([]), .double(1.5)]
        for value in invalid {
            XCTAssertThrowsError(try dispatcher.dispatch(name: "event_series", arguments: [
                "kind": .string("ALPHA"), "from_ts": value, "to_ts": value,
            ])) { error in
                guard case LocalAccessError.invalidParams = error else {
                    return XCTFail("Expected invalidParams for \(value), got \(error)")
                }
            }
        }
    }

    func testOutOfRangeNumbersCannotTrapIntegerConversion() {
        for value in [Double.infinity, -Double.infinity, Double.nan, 1e100, -1e100, Double(Int.max)] {
            XCTAssertNil(JSONValue.double(value).intValue)
        }
        XCTAssertEqual(JSONValue.double(1.9).intValue, 1)
        XCTAssertEqual(JSONValue.double(-1.9).intValue, -1)
        XCTAssertEqual(JSONValue.double(Double(Int.min)).intValue, Int.min)
    }

    func testIntegralDoubleBoundsKeepExplicitWindow() throws {
        let dispatcher = NoopToolDispatcher(configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withEvents().path))
        let value = try dispatcher.dispatch(name: "event_series", arguments: [
            "kind": .string("ALPHA"), "from_ts": .double(100), "to_ts": .double(102),
        ])
        XCTAssertEqual(value.objectValue?["returned"], .int(3))
    }

    func testHugeTimestampReturnsMCPInvalidParamsRatherThanCrashing() throws {
        let server = NoopMCPServer(configuration: LocalAccessConfiguration(databasePath: "/unused"))
        let response = try XCTUnwrap(try server.handle(RPCRequest(id: .int(1), method: "tools/call", params: .object([
            "name": .string("event_series"),
            "arguments": .object(["kind": .string("ALPHA"), "from_ts": .double(1e100), "to_ts": .double(1e100)]),
        ]))))
        XCTAssertEqual(response.objectValue?["error"]?.objectValue?["code"], .int(-32602))
    }
}
