import GRDB
import Foundation
import XCTest
@testable import NoopLocalAccessCore

final class ReadonlyNoopStoreTests: XCTestCase {
    func testReadsSeededNoopStoreWithoutOpeningWritableHandle() throws {
        try TemporaryDatabase.withFixture(TemporaryDatabase.seeded) { url in
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
        }
    }

    func testForeignNoopLikeDatabaseIsRejectedWithoutQuarantine() throws {
        try TemporaryDatabase.withFixture(TemporaryDatabase.foreignNoopLike) { url in
            let originalBytes = try Data(contentsOf: url)

            XCTAssertThrowsError(try ReadonlyNoopStore(path: url.path)) { error in
                guard case .databaseUnavailable(let message) = error as? LocalAccessError else {
                    return XCTFail("Expected LocalAccessError.databaseUnavailable")
                }
                XCTAssertTrue(message.contains("without GRDB migration metadata"))
                XCTAssertLessThan(message.count, 200)
            }
            XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
            XCTAssertEqual(try Data(contentsOf: url), originalBytes)
        }
    }

    func testSupportedReadsDoNotModifyFixtureContentsOrSchema() throws {
        try TemporaryDatabase.withFixture(TemporaryDatabase.seeded) { url in
            let original = try TemporaryDatabase.snapshot(at: url)
            let store = try ReadonlyNoopStore(path: url.path)

            XCTAssertTrue(try store.isReadOnlyForTest())
            XCTAssertEqual(
                try store.dailyMetrics(deviceId: "my-whoop", from: "2026-06-01", to: "2026-06-30").map(\.day),
                ["2026-06-10"]
            )
            XCTAssertEqual(try store.sleepSessions(deviceId: "my-whoop", from: 0, to: 10_000, limit: 10).map(\.startTs), [1000])
            XCTAssertEqual(try store.metricSeries(deviceId: "my-whoop", key: "hrv", from: "2026-06-01", to: "2026-06-30").map(\.value), [72])
            XCTAssertEqual(try store.metricKeys(deviceId: "my-whoop"), ["hrv"])
            XCTAssertEqual(try store.appleDaily(deviceId: "apple-health", from: "2026-06-01", to: "2026-06-30").first?.steps, 8000)
            XCTAssertEqual(try store.workouts(deviceId: "my-whoop", from: 0, to: 10_000, limit: 10).map(\.sport), ["run"])
            XCTAssertEqual(try store.latestHRSampleTs(deviceId: "my-whoop"), 102)
            XCTAssertEqual(try store.storageStats().rawBatches, 1)

            // Supplying an explicit fixture path prevents fallback to real HOME candidates.
            let config = LocalAccessConfiguration(databasePath: url.path)
            let access = try NoopDataAccess.open(configuration: config)
            XCTAssertNotNil(try access.healthSnapshot(days: 4000).objectValue?["sources"])
            let series = try access.metricSeries(
                key: "hrv", source: "my-whoop", days: 14,
                fromDay: "2026-06-10", toDay: "2026-06-11", limit: 2
            )
            XCTAssertEqual(series.objectValue?["returned"], .int(2))
            XCTAssertNotNil(try access.freshness().objectValue?["storage"])
            XCTAssertNotNil(try access.sleepSummary(days: 7).objectValue?["sessions"])
            XCTAssertNotNil(try access.workoutSummary(days: 7).objectValue?["workouts"])

            let tools: [(String, [String: JSONValue])] = [
                ("health_snapshot", ["days": .int(7)]),
                ("metric_series", [
                    "key": .string("hrv"),
                    "from_day": .string("2026-06-10"),
                    "to_day": .string("2026-06-11"),
                    "limit": .int(2),
                ]),
                ("data_freshness", [:]),
                ("sleep_summary", ["days": .int(7)]),
                ("workout_summary", ["days": .int(7)]),
            ]
            for (name, arguments) in tools {
                let request = NoopCLIQueryRequest(toolName: name, arguments: arguments, configuration: config)
                XCTAssertNotNil(try NoopCLIQuery.dispatch(request).objectValue, "Expected object from \(name)")
            }

            let response = try XCTUnwrap(try NoopMCPServer(configuration: config).handle(RPCRequest(
                id: .int(1),
                method: "tools/call",
                params: .object([
                    "name": .string("data_freshness"),
                    "arguments": .object([:]),
                ])
            )))
            XCTAssertNotNil(response.objectValue?["result"]?.objectValue?["structuredContent"])

            // Compare both raw SQLite bytes and queried schema/row evidence, not just a read-only flag.
            XCTAssertEqual(try TemporaryDatabase.snapshot(at: url), original)
            XCTAssertFalse(original.schema.contains { $0.contains("__noop_local_access_write_probe") })
        }
    }

    func testReadOnlyConnectionRejectsWriteAndLeavesFixtureUnchanged() throws {
        try TemporaryDatabase.withFixture(TemporaryDatabase.seeded) { url in
            let original = try TemporaryDatabase.snapshot(at: url)
            let store = try ReadonlyNoopStore(path: url.path)

            XCTAssertTrue(try store.isReadOnlyForTest())
            XCTAssertThrowsError(try store.writeProbeForTest())

            let after = try TemporaryDatabase.snapshot(at: url)
            XCTAssertEqual(after.bytes, original.bytes)
            XCTAssertEqual(after, original)
            XCTAssertFalse(after.schema.contains { $0.contains("__noop_local_access_write_probe") })
        }
    }

    func testOpeningMissingStorePathDoesNotCreateDatabaseFile() throws {
        try TemporaryDatabase.withFixture(TemporaryDatabase.emptyFileURL) { fixture in
            let missing = fixture.deletingLastPathComponent().appendingPathComponent("missing.sqlite")
            let fm = FileManager.default

            XCTAssertFalse(fm.fileExists(atPath: missing.path))
            XCTAssertThrowsError(try ReadonlyNoopStore(path: missing.path))
            XCTAssertFalse(fm.fileExists(atPath: missing.path))
            XCTAssertTrue(fm.fileExists(atPath: fixture.path))
        }
    }

    func testNonSQLiteFileIsNotRewrittenAsAStore() throws {
        try TemporaryDatabase.withFixture(TemporaryDatabase.emptyFileURL) { url in
            let original = Data("not a SQLite database".utf8)
            try original.write(to: url)

            XCTAssertThrowsError(try ReadonlyNoopStore(path: url.path))
            XCTAssertEqual(try Data(contentsOf: url), original)
        }
    }

    func testExistingDirectoryIsNotAReadableStore() throws {
        try TemporaryDatabase.withFixture(TemporaryDatabase.emptyFileURL) { url in
            let directory = url.deletingLastPathComponent()

            // The current resolver checks existence, not type; SQLite must reject a directory.
            XCTAssertEqual(
                try DatabasePathResolver.resolve(configuration: LocalAccessConfiguration(databasePath: directory.path)),
                directory.path
            )
            XCTAssertThrowsError(try ReadonlyNoopStore(path: directory.path))
            XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        }
    }
}
