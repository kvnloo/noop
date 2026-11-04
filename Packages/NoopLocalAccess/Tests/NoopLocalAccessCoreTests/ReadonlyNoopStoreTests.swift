import GRDB
import XCTest
@testable import NoopLocalAccessCore

final class ReadonlyNoopStoreTests: XCTestCase {
    func testReadsSeededNoopStoreWithoutOpeningWritableHandle() throws {
        let url = try TemporaryDatabase.seeded()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        XCTAssertEqual(try store.latestHRSampleTs(deviceId: "my-whoop"), 102)
        XCTAssertEqual(try store.metricKeys(deviceId: "my-whoop"), ["hrv"])

        let daily = try store.dailyMetrics(deviceId: "my-whoop", from: "2026-06-01", to: "2026-06-30")
        XCTAssertEqual(daily.map(\.day), ["2026-06-10"])
        XCTAssertEqual(daily.first?.recovery, 67)

        let stats = try store.storageStats()
        XCTAssertEqual(stats.decodedRows, 4)
        XCTAssertEqual(stats.rawBatches, 1)
        XCTAssertEqual(stats.rawBytes, 12)
        let legacyRR = try store.rrIntervals(deviceId: "my-whoop", from: 0, to: 1000, limit: 10)
        XCTAssertEqual(legacyRR.map(\.ts), [101])
        XCTAssertEqual(legacyRR.map(\.rrMs), [850])
        XCTAssertNil(legacyRR[0].seq)
        XCTAssertNil(legacyRR[0].ord)
    }

    func testRRReadIsReadOnlyAndExcludesSuspectAndSpo2Rows() throws {
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
        XCTAssertEqual(try store.rrIntervals(deviceId: "other-device", from: 100, to: 103, limit: 10).count, 1)

        let missing = try ReadonlyNoopStore(path: TemporaryDatabase.withoutRRInterval().path)
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
