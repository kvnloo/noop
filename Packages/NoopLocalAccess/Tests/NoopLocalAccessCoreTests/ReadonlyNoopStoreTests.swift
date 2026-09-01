import GRDB
import XCTest
@testable import NoopLocalAccessCore

final class ReadonlyNoopStoreTests: XCTestCase {
    func testReadsSeededNoopStoreWithoutOpeningWritableHandle() throws {
        let url = try TemporaryDatabase.seeded()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        XCTAssertEqual(try store.latestHRSampleTs(deviceId: "my-whoop"), 102)
        let buckets = try store.hrBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 1)
        XCTAssertEqual(buckets.map(\.ts), [100, 101, 102])
        XCTAssertEqual(buckets.map(\.bpm), [70, 72, 73.2])
        XCTAssertEqual(try store.metricKeys(deviceId: "my-whoop"), ["hrv"])
        let sleep = try store.sleepSessions(deviceId: "my-whoop", from: 0, to: 10_000, limit: 10)
        XCTAssertEqual(sleep.count, 1)
        XCTAssertNil(sleep.first?.stagesJSON)

        let daily = try store.dailyMetrics(deviceId: "my-whoop", from: "2026-06-01", to: "2026-06-30")
        XCTAssertEqual(daily.map(\.day), ["2026-06-10"])
        XCTAssertEqual(daily.first?.recovery, 67)

        let stats = try store.storageStats()
        XCTAssertEqual(stats.decodedRows, 4)
        XCTAssertEqual(stats.rawBatches, 1)
        XCTAssertEqual(stats.rawBytes, 12)
        XCTAssertEqual(try store.events(deviceId: "my-whoop", kind: "ALPHA", from: 0, to: 1000, limit: 10), [])
        let legacyRR = try store.rrIntervals(deviceId: "my-whoop", from: 0, to: 1000, limit: 10)
        XCTAssertEqual(legacyRR.map(\.ts), [101])
        XCTAssertEqual(legacyRR.map(\.rrMs), [850])
        XCTAssertEqual(legacyRR.map(\.seq), [Int?](repeating: nil, count: 1))
        XCTAssertEqual(legacyRR.map(\.ord), [Int?](repeating: nil, count: 1))
    }

    func testReadsEventRowsByKindWithoutOpeningWritableHandle() throws {
        let url = try TemporaryDatabase.withEvents()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        let alpha = try store.events(deviceId: "my-whoop", kind: "ALPHA", from: 100, to: 102, limit: 10)
        XCTAssertEqual(alpha.map(\.ts), [100, 101, 102])
        XCTAssertEqual(alpha.map(\.kind), ["ALPHA", "ALPHA", "ALPHA"])
        XCTAssertEqual(alpha.map(\.payloadJSON), [#"{"ok":true,"n":1}"#, "not-json", #"{"later":true}"#])
        XCTAssertEqual(try store.events(deviceId: "my-whoop", kind: "NO_SUCH_KIND", from: 0, to: 1000, limit: 10), [])

        let suffix = try store.events(deviceId: "my-whoop", kind: "ALPHA", from: 100, to: 102, limit: 2)
        XCTAssertEqual(suffix.map(\.ts), [101, 102])

        let stats = try store.storageStats()
        XCTAssertEqual(stats.decodedRows, 9)
    }

    func testReadsRRIntervalsMatchingWhoopStoreFilters() throws {
        let url = try TemporaryDatabase.withRRIntervals()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        let rows = try store.rrIntervals(deviceId: "my-whoop", from: 100, to: 103, limit: 10)
        XCTAssertEqual(rows.map(\.ts), [100, 101, 101, 103])
        XCTAssertEqual(rows.map(\.rrMs), [800, 790, 810, 840])
        XCTAssertEqual(rows.map(\.seq), [Optional(0), Optional(0), Optional(1), Optional(0)])
        XCTAssertEqual(rows.map(\.ord), [Optional(0), Optional(0), Optional(1), Optional(0)])

        let suffix = try store.rrIntervals(deviceId: "my-whoop", from: 100, to: 103, limit: 2)
        XCTAssertEqual(suffix.map(\.ts), [101, 103])
        XCTAssertEqual(suffix.map(\.rrMs), [810, 840])

        let missing = try ReadonlyNoopStore(path: try TemporaryDatabase.withoutRRInterval().path)
        XCTAssertEqual(try missing.rrIntervals(deviceId: "my-whoop", from: 0, to: 1000, limit: 10), [])
    }

    func testForeignNoopLikeDatabaseIsRejectedWithoutQuarantine() throws {
        let url = try TemporaryDatabase.foreignNoopLike()

        XCTAssertThrowsError(try ReadonlyNoopStore(path: url.path)) { error in
            guard case .databaseUnavailable(let message) = error as? LocalAccessError else {
                return XCTFail("Expected LocalAccessError.databaseUnavailable")
            }
            XCTAssertTrue(message.contains("without GRDB migration metadata"))
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
    }
}
