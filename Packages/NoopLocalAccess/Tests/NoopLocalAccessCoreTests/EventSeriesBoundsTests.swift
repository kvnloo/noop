import GRDB
import NoopLocalAccessCore
import XCTest

final class EventSeriesBoundsTests: XCTestCase {
    func testDirectReadCapsRowsAndReportsTruncation() throws {
        let access = try accessWithEvents(1...2_001)
        let result = try access.eventSeries(
            kind: "CAP_TEST", hours: 6, fromTs: 1, toTs: 2_001, limit: 2_002, deviceId: nil
        )

        XCTAssertEqual(result.objectValue?["returned"], .int(2_000))
        XCTAssertEqual(result.objectValue?["truncated"], .bool(true))
        guard case .array(let points) = result.objectValue?["points"] else {
            return XCTFail("Expected event points")
        }
        XCTAssertEqual(points.count, 2_000)
        XCTAssertEqual(points.first?.objectValue?["ts"], .int(2))
        XCTAssertEqual(points.last?.objectValue?["ts"], .int(2_001))
    }

    func testDirectReadClampsTrailingHours() throws {
        let now = Int(Date().timeIntervalSince1970)
        let recent = now - 3_600
        let access = try accessWithEvents([now - 25 * 3_600, recent])
        let result = try access.eventSeries(
            kind: "CAP_TEST", hours: 48, fromTs: nil, toTs: nil, limit: 10, deviceId: nil
        )

        let range = try XCTUnwrap(result.objectValue?["range"]?.objectValue)
        XCTAssertEqual(range["hours"], .int(24))
        let from = try XCTUnwrap(range["fromTs"]?.intValue)
        let to = try XCTUnwrap(range["toTs"]?.intValue)
        XCTAssertEqual(to - from, 24 * 3_600)
        XCTAssertEqual(result.objectValue?["returned"], .int(1))
        guard case .array(let points) = result.objectValue?["points"] else {
            return XCTFail("Expected event points")
        }
        XCTAssertEqual(points.first?.objectValue?["ts"], .int(recent))
    }

    func testDirectReadRejectsInvertedRange() throws {
        let access = try accessWithEvents([100])
        XCTAssertThrowsError(try access.eventSeries(
            kind: "CAP_TEST", hours: 6, fromTs: 101, toTs: 100, limit: 10, deviceId: nil
        )) { error in
            guard case LocalAccessError.invalidParams = error else {
                return XCTFail("Expected invalidParams, got \(error)")
            }
        }
    }

    func testDirectReadRejectsExplicitRangeOver24Hours() throws {
        let access = try accessWithEvents([100])
        let boundary = try access.eventSeries(
            kind: "CAP_TEST", hours: 6, fromTs: 100, toTs: 100 + 24 * 3_600, limit: 10, deviceId: nil
        )
        XCTAssertEqual(boundary.objectValue?["returned"], .int(1))

        for (from, to) in [(100, 101 + 24 * 3_600), (Int.min, Int.max)] {
            XCTAssertThrowsError(try access.eventSeries(
                kind: "CAP_TEST", hours: 6, fromTs: from, toTs: to, limit: 10, deviceId: nil
            )) { error in
                guard case LocalAccessError.invalidParams = error else {
                    return XCTFail("Expected invalidParams, got \(error)")
                }
            }
        }
    }

    private func accessWithEvents(_ timestamps: some Sequence<Int>) throws -> NoopDataAccess {
        let url = try TemporaryDatabase.withEvents()
        addTeardownBlock { try FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let queue = try DatabaseQueue(path: url.path)
        try queue.write { db in
            for ts in timestamps {
                try db.execute(
                    sql: "INSERT INTO event(deviceId, ts, kind, payloadJSON) VALUES ('my-whoop', ?, 'CAP_TEST', '{}')",
                    arguments: [ts]
                )
            }
        }
        return try NoopDataAccess.open(configuration: LocalAccessConfiguration(databasePath: url.path))
    }
}
