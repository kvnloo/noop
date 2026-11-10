import GRDB
import XCTest
@testable import NoopLocalAccessCore

final class RRMetadataTests: XCTestCase {
    func testMalformedMetadataIsExcludedBeforeLimitAndTruncation() throws {
        for column in ["seq", "ord"] {
            for value in ["'broken'", "0.5", "X'0102'"] {
                let url = try database(malformedColumn: column, value: value)
                defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
                let store = try ReadonlyNoopStore(path: url.path)
                let rows = try store.rrIntervals(deviceId: "my-whoop", from: 0, to: 1000, limit: 2)
                XCTAssertEqual(rows.map(\.ts), [100, 101], "\(column)=\(value)")
                XCTAssertTrue(try store.isReadOnlyForTest())

                let arguments: [String: JSONValue] = ["from_ts": .int(0), "to_ts": .int(1000), "limit": .int(2)]
                let configuration = LocalAccessConfiguration(databasePath: url.path)
                let payload = try NoopToolDispatcher(configuration: configuration).dispatch(name: "rr_series", arguments: arguments)
                XCTAssertEqual(payload.objectValue?["returned"], .int(2))
                XCTAssertEqual(payload.objectValue?["truncated"], .bool(false))
                let cli = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(toolName: "rr_series", arguments: arguments, configuration: configuration))
                XCTAssertEqual(cli, payload)
                _ = try NoopCLIQuery.encodeLine(payload)

                let limited = try NoopToolDispatcher(configuration: configuration).dispatch(
                    name: "rr_series", arguments: arguments.merging(["limit": .int(1)]) { _, new in new }
                )
                XCTAssertEqual(limited.objectValue?["truncated"], .bool(true))
                guard case .array(let points) = limited.objectValue?["points"] else {
                    return XCTFail("Expected R-R points")
                }
                XCTAssertEqual(points.first?.objectValue?["ts"], .int(101))
            }
        }
    }

    func testNullMetadataIsRetained() throws {
        let url = try database(malformedColumn: "seq", value: "NULL")
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = try ReadonlyNoopStore(path: url.path)
        let rows = try store.rrIntervals(deviceId: "my-whoop", from: 0, to: 1000, limit: 10)
        XCTAssertEqual(rows.map(\.ts), [100, 101, 102])
        XCTAssertNil(rows[0].seq)
        XCTAssertNil(rows[0].ord)
        XCTAssertNil(rows[2].seq)
    }

    private func database(malformedColumn: String, value: String) throws -> URL {
        let url = try TemporaryDatabase.seeded()
        let queue = try DatabaseQueue(path: url.path)
        try queue.write { db in
            try db.execute(sql: "DROP TABLE rrInterval")
            try db.execute(sql: "CREATE TABLE rrInterval(deviceId TEXT, ts INTEGER, rrMs INTEGER, seq INTEGER, ord INTEGER)")
            try db.execute(sql: "INSERT INTO rrInterval VALUES ('my-whoop',100,800,NULL,NULL), ('my-whoop',101,810,1,1), ('my-whoop',102,820,2,2)")
            try db.execute(sql: "UPDATE rrInterval SET \(malformedColumn) = \(value) WHERE ts = 102")
        }
        return url
    }
}
