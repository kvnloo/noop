import GRDB
import XCTest
@testable import NoopLocalAccessCore

final class FreshnessAgeTests: XCTestCase {
    func testExtremeIntegerTimestampsHaveBoundedAge() throws {
        for (timestamp, expectedAge) in [(Int.min, Int.max), (Int.max, 0)] {
            let payload = try freshness(timestamp: timestamp)
            XCTAssertEqual(payload.objectValue?["ts"], .int(timestamp))
            XCTAssertEqual(payload.objectValue?["ageSeconds"], .int(expectedAge))
            _ = try NoopCLIQuery.encodeLine(payload)
        }
    }

    func testOrdinaryPastAndFutureAgesRemainCompatible() throws {
        let now = Int(Date().timeIntervalSince1970)
        let past = try freshness(timestamp: now - 60)
        let age = try XCTUnwrap(past.objectValue?["ageSeconds"]?.intValue)
        XCTAssertTrue((60...(Int(Date().timeIntervalSince1970) - now + 60)).contains(age))
        XCTAssertEqual(try freshness(timestamp: now + 3600).objectValue?["ageSeconds"], .int(0))
    }

    private func freshness(timestamp: Int) throws -> JSONValue {
        let url = try TemporaryDatabase.seeded()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let queue = try DatabaseQueue(path: url.path)
        try queue.write { db in
            try db.execute(sql: "DELETE FROM hrSample")
            try db.execute(sql: "DELETE FROM ppgHrSample")
            try db.execute(sql: "INSERT INTO hrSample(deviceId,ts,bpm) VALUES ('my-whoop',?,70)", arguments: [timestamp])
        }
        let dispatcher = NoopToolDispatcher(configuration: LocalAccessConfiguration(databasePath: url.path))
        let payload = try dispatcher.dispatch(name: "data_freshness")
        _ = try NoopCLIQuery.encodeLine(payload)
        return try XCTUnwrap(payload.objectValue?["latestHeartRateSample"])
    }
}
