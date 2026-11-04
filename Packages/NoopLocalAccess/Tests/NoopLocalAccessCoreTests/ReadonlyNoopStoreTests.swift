import GRDB
import XCTest
@testable import NoopLocalAccessCore

final class ReadonlyNoopStoreTests: XCTestCase {
    func testReadsSeededNoopStoreWithoutOpeningWritableHandle() throws {
        let url = try TemporaryDatabase.seeded()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        XCTAssertEqual(try store.latestHRSampleTs(deviceId: "my-whoop"), 102)
        XCTAssertEqual(try store.latestRRIntervalTs(deviceId: "my-whoop"), 101)
        XCTAssertNil(try store.latestEventTs(deviceId: "my-whoop"))
        XCTAssertEqual(try store.latestSleepSessionTs(deviceId: "my-whoop"), 2000)
        XCTAssertEqual(try store.metricKeys(deviceId: "my-whoop"), ["hrv"])

        let daily = try store.dailyMetrics(deviceId: "my-whoop", from: "2026-06-01", to: "2026-06-30")
        XCTAssertEqual(daily.map(\.day), ["2026-06-10"])
        XCTAssertEqual(daily.first?.recovery, 67)

        let stats = try store.storageStats()
        XCTAssertEqual(stats.decodedRows, 4)
        XCTAssertEqual(stats.rawBatches, 1)
        XCTAssertEqual(stats.rawBytes, 12)
    }

    func testSourceWatermarksFilterUnusableRRAndRespectAbsentTablesAndDevice() throws {
        let rr = try ReadonlyNoopStore(path: try TemporaryDatabase.withRRQualityRows().path)
        // 105 is SpO2 IBI and 106 is time-suspect; both must lose to valid 104.
        XCTAssertEqual(try rr.latestRRIntervalTs(deviceId: "my-whoop"), 104)
        XCTAssertNil(try rr.latestRRIntervalTs(deviceId: "unknown-device"))

        let events = try ReadonlyNoopStore(path: try TemporaryDatabase.withEvents().path)
        XCTAssertEqual(try events.latestEventTs(deviceId: "my-whoop"), 103)
        XCTAssertNil(try events.latestEventTs(deviceId: "unknown-device"))

        let withoutRR = try ReadonlyNoopStore(path: try TemporaryDatabase.withoutRRInterval().path)
        XCTAssertNil(try withoutRR.latestRRIntervalTs(deviceId: "my-whoop"))
        let withoutSleep = try ReadonlyNoopStore(path: try TemporaryDatabase.withoutSleepSession().path)
        XCTAssertNil(try withoutSleep.latestSleepSessionTs(deviceId: "my-whoop"))
        XCTAssertNil(try withoutSleep.latestSleepSessionTs(deviceId: "unknown-device"))
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
