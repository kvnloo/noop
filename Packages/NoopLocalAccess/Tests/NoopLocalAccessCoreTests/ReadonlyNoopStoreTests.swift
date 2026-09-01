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
        XCTAssertEqual(try store.latestWorkoutTs(deviceId: "my-whoop"), 4800)
        XCTAssertNil(try store.latestBatteryTs(deviceId: "my-whoop"))
        XCTAssertNil(try store.latestStepTs(deviceId: "my-whoop"))
        XCTAssertNil(try store.latestRespTs(deviceId: "my-whoop"))
        XCTAssertNil(try store.latestSkinTempTs(deviceId: "my-whoop"))
        XCTAssertNil(try store.latestSpo2Ts(deviceId: "my-whoop"))
        XCTAssertNil(try store.latestGravityTs(deviceId: "my-whoop"))
        XCTAssertNil(try store.latestSleepStateTs(deviceId: "my-whoop"))
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
        XCTAssertEqual(try store.latestEventTs(deviceId: "my-whoop"), 103)
        XCTAssertEqual(try store.events(deviceId: "my-whoop", kind: "NO_SUCH_KIND", from: 0, to: 1000, limit: 10), [])

        let suffix = try store.events(deviceId: "my-whoop", kind: "ALPHA", from: 100, to: 102, limit: 2)
        XCTAssertEqual(suffix.map(\.ts), [101, 102])

        let stats = try store.storageStats()
        XCTAssertEqual(stats.decodedRows, 9)
    }

    func testReadsDistinctEventKindsWithoutOpeningWritableHandle() throws {
        let url = try TemporaryDatabase.withEvents()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        XCTAssertEqual(try store.eventKinds(deviceId: "my-whoop", limit: 10), ["ALPHA", "BETA"])
        XCTAssertEqual(try store.eventKinds(deviceId: "my-whoop", limit: 1), ["ALPHA"])
        XCTAssertEqual(try store.eventKinds(deviceId: "other-device", limit: 10), ["ALPHA"])
        XCTAssertEqual(try store.eventKinds(deviceId: "no-such", limit: 10), [])

        let missing = try ReadonlyNoopStore(path: try TemporaryDatabase.seeded().path)
        XCTAssertEqual(try missing.eventKinds(deviceId: "my-whoop", limit: 10), [])
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

        XCTAssertEqual(try store.latestRRIntervalTs(deviceId: "my-whoop"), 103)

        let missing = try ReadonlyNoopStore(path: try TemporaryDatabase.withoutRRInterval().path)
        XCTAssertEqual(try missing.rrIntervals(deviceId: "my-whoop", from: 0, to: 1000, limit: 10), [])
        XCTAssertNil(try missing.latestRRIntervalTs(deviceId: "my-whoop"))

        let noSleep = try ReadonlyNoopStore(path: try TemporaryDatabase.withoutSleepSession().path)
        XCTAssertNil(try noSleep.latestSleepSessionTs(deviceId: "my-whoop"))
        XCTAssertEqual(try noSleep.latestHRSampleTs(deviceId: "my-whoop"), 102)
        XCTAssertEqual(try noSleep.latestWorkoutTs(deviceId: "my-whoop"), 4800)

        let noWorkout = try ReadonlyNoopStore(path: try TemporaryDatabase.withoutWorkout().path)
        XCTAssertNil(try noWorkout.latestWorkoutTs(deviceId: "my-whoop"))
        XCTAssertEqual(try noWorkout.latestHRSampleTs(deviceId: "my-whoop"), 102)
        XCTAssertEqual(try noWorkout.latestSleepSessionTs(deviceId: "my-whoop"), 2000)
    }

    func testReadsSpo2BucketsAndReturnsEmptyWhenTableMissing() throws {
        let url = try TemporaryDatabase.withSpo2Samples()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        let buckets = try store.spo2Buckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 1)
        XCTAssertEqual(buckets.map(\.ts), [100, 101, 102])
        XCTAssertEqual(buckets.map(\.red), [97.0, 98.0, 96.0])
        XCTAssertEqual(buckets.map(\.ir), [50.0, 51.0, 49.0])

        let grouped = try store.spo2Buckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 2)
        XCTAssertEqual(grouped.map(\.ts), [100, 102])
        XCTAssertEqual(grouped.map(\.red), [97.5, 96.0])
        XCTAssertEqual(grouped.map(\.ir), [50.5, 49.0])

        let missing = try ReadonlyNoopStore(path: try TemporaryDatabase.seeded().path)
        XCTAssertEqual(try missing.spo2Buckets(deviceId: "my-whoop", from: 0, to: 1000, bucketSeconds: 1), [])
        XCTAssertNil(try missing.latestSpo2Ts(deviceId: "my-whoop"))
        XCTAssertEqual(try store.latestSpo2Ts(deviceId: "my-whoop"), 102)
    }


    func testReadsSkinTempBucketsAndReturnsEmptyWhenTableMissing() throws {
        let url = try TemporaryDatabase.withSkinTempSamples()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        let buckets = try store.skinTempBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 1)
        XCTAssertEqual(buckets.map(\.ts), [100, 101, 102])
        XCTAssertEqual(buckets.map(\.raw), [3057.0, 3060.0, 3040.0])

        let grouped = try store.skinTempBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 2)
        XCTAssertEqual(grouped.map(\.ts), [100, 102])
        XCTAssertEqual(grouped.map(\.raw), [3058.5, 3040.0])

        let missing = try ReadonlyNoopStore(path: try TemporaryDatabase.seeded().path)
        XCTAssertEqual(try missing.skinTempBuckets(deviceId: "my-whoop", from: 0, to: 1000, bucketSeconds: 1), [])
        XCTAssertNil(try missing.latestSkinTempTs(deviceId: "my-whoop"))
        XCTAssertEqual(try store.latestSkinTempTs(deviceId: "my-whoop"), 102)
    }

    func testReadsRespBucketsAndReturnsEmptyWhenTableMissing() throws {
        let url = try TemporaryDatabase.withRespSamples()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        let buckets = try store.respBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 1)
        XCTAssertEqual(buckets.map(\.ts), [100, 101, 102])
        XCTAssertEqual(buckets.map(\.raw), [1200.0, 1300.0, 1100.0])

        let grouped = try store.respBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 2)
        XCTAssertEqual(grouped.map(\.ts), [100, 102])
        XCTAssertEqual(grouped.map(\.raw), [1250.0, 1100.0])

        let missing = try ReadonlyNoopStore(path: try TemporaryDatabase.seeded().path)
        XCTAssertEqual(try missing.respBuckets(deviceId: "my-whoop", from: 0, to: 1000, bucketSeconds: 1), [])
        XCTAssertNil(try missing.latestRespTs(deviceId: "my-whoop"))
        XCTAssertEqual(try store.latestRespTs(deviceId: "my-whoop"), 102)
    }

    func testReadsStepBucketsAndReturnsEmptyWhenTableMissing() throws {
        let url = try TemporaryDatabase.withStepSamples()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        let buckets = try store.stepBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 1)
        XCTAssertEqual(buckets.map(\.ts), [100, 101, 102])
        XCTAssertEqual(buckets.map(\.counter), [1200.0, 1300.0, 1100.0])

        let grouped = try store.stepBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 2)
        XCTAssertEqual(grouped.map(\.ts), [100, 102])
        XCTAssertEqual(grouped.map(\.counter), [1250.0, 1100.0])

        let missing = try ReadonlyNoopStore(path: try TemporaryDatabase.seeded().path)
        XCTAssertEqual(try missing.stepBuckets(deviceId: "my-whoop", from: 0, to: 1000, bucketSeconds: 1), [])
        XCTAssertNil(try missing.latestStepTs(deviceId: "my-whoop"))
        XCTAssertEqual(try store.latestStepTs(deviceId: "my-whoop"), 102)
    }

    func testReadsGravityBucketsAndReturnsEmptyWhenTableMissing() throws {
        let url = try TemporaryDatabase.withGravitySamples()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        let buckets = try store.gravityBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 1)
        XCTAssertEqual(buckets.map(\.ts), [100, 101, 102])
        XCTAssertEqual(buckets.map(\.x), [1.0, 3.0, 5.0])
        XCTAssertEqual(buckets.map(\.y), [2.0, 4.0, 6.0])
        XCTAssertEqual(buckets.map(\.z), [9.0, 7.0, 5.0])

        let grouped = try store.gravityBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 2)
        XCTAssertEqual(grouped.map(\.ts), [100, 102])
        XCTAssertEqual(grouped.map(\.x), [2.0, 5.0])
        XCTAssertEqual(grouped.map(\.y), [3.0, 6.0])
        XCTAssertEqual(grouped.map(\.z), [8.0, 5.0])

        let missing = try ReadonlyNoopStore(path: try TemporaryDatabase.seeded().path)
        XCTAssertEqual(try missing.gravityBuckets(deviceId: "my-whoop", from: 0, to: 1000, bucketSeconds: 1), [])
        XCTAssertNil(try missing.latestGravityTs(deviceId: "my-whoop"))
        XCTAssertEqual(try store.latestGravityTs(deviceId: "my-whoop"), 102)
    }

    func testReadsBatteryBucketsAndReturnsEmptyWhenTableMissing() throws {
        let url = try TemporaryDatabase.withBatterySamples()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        let buckets = try store.batteryBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 1)
        XCTAssertEqual(buckets.map(\.ts), [100, 101, 102])
        XCTAssertEqual(buckets.map(\.soc), [80.0, 78.0, 76.0])
        XCTAssertEqual(buckets.map(\.mv), [3900.0, 3850.0, 3800.0])

        let grouped = try store.batteryBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 2)
        XCTAssertEqual(grouped.map(\.ts), [100, 102])
        XCTAssertEqual(grouped.map(\.soc), [79.0, 76.0])
        XCTAssertEqual(grouped.map(\.mv), [3875.0, 3800.0])

        let missing = try ReadonlyNoopStore(path: try TemporaryDatabase.seeded().path)
        XCTAssertEqual(try missing.batteryBuckets(deviceId: "my-whoop", from: 0, to: 1000, bucketSeconds: 1), [])
        XCTAssertNil(try missing.latestBatteryTs(deviceId: "my-whoop"))
        XCTAssertEqual(try store.latestBatteryTs(deviceId: "my-whoop"), 102)
    }

    func testReadsSleepStateBucketsAndReturnsEmptyWhenTableMissing() throws {
        let url = try TemporaryDatabase.withSleepStateSamples()
        let store = try ReadonlyNoopStore(path: url.path)

        XCTAssertTrue(try store.isReadOnlyForTest())
        let buckets = try store.sleepStateBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 1)
        XCTAssertEqual(buckets.map(\.ts), [100, 101, 102])
        XCTAssertEqual(buckets.map(\.state), [0.0, 2.0, 3.0])

        let grouped = try store.sleepStateBuckets(deviceId: "my-whoop", from: 100, to: 102, bucketSeconds: 2)
        XCTAssertEqual(grouped.map(\.ts), [100, 102])
        XCTAssertEqual(grouped.map(\.state), [1.0, 3.0])

        let missing = try ReadonlyNoopStore(path: try TemporaryDatabase.seeded().path)
        XCTAssertEqual(try missing.sleepStateBuckets(deviceId: "my-whoop", from: 0, to: 1000, bucketSeconds: 1), [])
        XCTAssertNil(try missing.latestSleepStateTs(deviceId: "my-whoop"))
        XCTAssertEqual(try store.latestSleepStateTs(deviceId: "my-whoop"), 102)
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
