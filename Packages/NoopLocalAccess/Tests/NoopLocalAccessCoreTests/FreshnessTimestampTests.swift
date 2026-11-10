import GRDB
import XCTest
@testable import NoopLocalAccessCore

final class FreshnessTimestampTests: XCTestCase {
    func testInvalidTimestampStorageDoesNotHideLatestInteger() throws {
        for table in ["rrInterval", "event", "sleepSession", "hrSample", "ppgHrSample"] {
            let column = table == "sleepSession" ? "endTs" : "ts"
            let url = try TemporaryDatabase.seeded()
            defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
            let queue = try DatabaseQueue(path: url.path)
            try queue.write { db in
                for name in ["rrInterval", "event", "sleepSession", "hrSample", "ppgHrSample"] {
                    try db.execute(sql: "DROP TABLE IF EXISTS \(name)")
                }
                try db.execute(sql: "CREATE TABLE \(table)(deviceId TEXT, \(column) INTEGER)")
                try db.execute(sql: "INSERT INTO \(table) VALUES ('my-whoop',100), ('my-whoop',101), ('my-whoop','broken'), ('my-whoop',102.5), ('my-whoop',X'0102'), ('my-whoop',NULL), ('other-device',999)")
            }
            let configuration = LocalAccessConfiguration(databasePath: url.path)
            let key = table == "rrInterval" ? "latestRrInterval" : table == "event" ? "latestEvent" : table == "sleepSession" ? "latestSleepSession" : "latestHeartRateSample"
            let payload = try NoopToolDispatcher(configuration: configuration).dispatch(name: "data_freshness")
            XCTAssertEqual(payload.objectValue?[key]?.objectValue?["ts"], .int(101), table)
            _ = try NoopCLIQuery.encodeLine(payload)
            try queue.write { db in
                try db.execute(sql: "DELETE FROM \(table) WHERE typeof(\(column)) = 'integer'")
            }
            let empty = try NoopToolDispatcher(configuration: configuration).dispatch(name: "data_freshness")
            XCTAssertEqual(empty.objectValue?[key], .null, table)
        }
    }

    func testHeartRateUnionFiltersInvalidValuesFromBothSources() throws {
        let url = try TemporaryDatabase.seeded()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let queue = try DatabaseQueue(path: url.path)
        try queue.write { db in
            try db.execute(sql: "INSERT INTO hrSample(deviceId,ts,bpm) VALUES ('my-whoop','broken',70)")
            try db.execute(sql: "INSERT INTO ppgHrSample(deviceId,ts,bpm,conf) VALUES ('my-whoop',X'0102',70,1), ('my-whoop',200,70,1)")
        }
        let store = try ReadonlyNoopStore(path: url.path)
        XCTAssertEqual(try store.latestHRSampleTs(deviceId: "my-whoop"), 200)
        XCTAssertTrue(try store.isReadOnlyForTest())
    }
}
