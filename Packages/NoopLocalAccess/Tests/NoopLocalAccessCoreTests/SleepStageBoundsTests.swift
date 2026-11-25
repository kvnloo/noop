import Foundation
import GRDB
import NoopLocalAccessCore
import XCTest

final class SleepStageBoundsTests: XCTestCase {
    func testDaysAboveMaximumExcludeOlderSessions() throws {
        let now = Int(Date().timeIntervalSince1970)
        let inside = now - 3_999 * 86_400
        let outside = now - 4_001 * 86_400
        let data = try access(starts: [outside, inside])

        let result = try data.sleepStages(days: 4_002, limit: 10, maxPoints: 1)

        XCTAssertEqual(result.objectValue?["range"]?.objectValue?["days"], .int(4_000))
        XCTAssertEqual(result.objectValue?["count"], .int(1))
        XCTAssertEqual(try sessions(in: result).map { $0.objectValue?["startTs"] }, [.int(inside)])
    }

    func testLimitAboveMaximumReturnsNewestSixtySessions() throws {
        let now = Int(Date().timeIntervalSince1970)
        let starts = (1...61).map { now - $0 * 3_600 }.sorted()
        let data = try access(starts: starts)

        let result = try data.sleepStages(days: 3, limit: 61, maxPoints: 1)
        let returned = try sessions(in: result)

        XCTAssertEqual(result.objectValue?["count"], .int(61))
        XCTAssertEqual(result.objectValue?["returned"], .int(60))
        XCTAssertEqual(result.objectValue?["truncated"], .bool(true))
        XCTAssertEqual(returned.count, 60)
        XCTAssertEqual(returned.first?.objectValue?["startTs"], .int(starts[1]))
        XCTAssertEqual(returned.last?.objectValue?["startTs"], starts.last.map(JSONValue.int))
    }

    func testMaxPointsAboveMaximumCapsEachSession() throws {
        let now = Int(Date().timeIntervalSince1970)
        let data = try access(starts: [now - 7_200, now - 3_600], points: 2_001)

        let result = try data.sleepStages(days: 1, limit: 2, maxPoints: 2_001)
        let returned = try sessions(in: result)

        XCTAssertEqual(returned.count, 2)
        XCTAssertEqual(result.objectValue?["truncated"], .bool(true))
        for session in returned {
            XCTAssertEqual(session.objectValue?["truncated"], .bool(true))
            XCTAssertEqual(try array(session.objectValue?["stages"]).count, 2_000)
        }
    }

    func testNonpositiveArgumentsClampToOne() throws {
        let now = Int(Date().timeIntervalSince1970)
        let data = try access(starts: [now - 7_200, now - 3_600], points: 2)
        let expected = try data.sleepStages(days: 1, limit: 1, maxPoints: 1)
        let zero = try data.sleepStages(days: 0, limit: 0, maxPoints: 0)

        func checkOne(_ result: JSONValue) throws {
            // Range timestamps are clock-relative, so compare only the effective day count.
            XCTAssertEqual(result.objectValue?["range"]?.objectValue?["days"], .int(1))
            XCTAssertEqual(result.objectValue?["count"], .int(2))
            XCTAssertEqual(result.objectValue?["returned"], .int(1))
            XCTAssertEqual(try sessions(in: result), try sessions(in: expected))
        }

        try checkOne(zero)
        // Unclamped negative size arguments can trap in suffix/prefix. Only exercise
        // negatives after the corresponding zero inputs are known to be safe.
        guard zero.objectValue?["returned"] == .int(1),
              let session = try sessions(in: zero).first,
              try array(session.objectValue?["stages"]).count == 1
        else { return }
        try checkOne(data.sleepStages(days: -1, limit: -1, maxPoints: -1))
    }

    private func access(starts: [Int], points: Int = 0) throws -> NoopDataAccess {
        let url = try TemporaryDatabase.seeded()
        addTeardownBlock { try FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let queue = try DatabaseQueue(path: url.path)
        try queue.write { db in
            for start in starts {
                let stages = (0..<points).map { offset in
                    #"{"start":\#(start + offset),"end":\#(start + offset + 1),"stage":"light"}"#
                }.joined(separator: ",")
                try db.execute(
                    sql: "INSERT INTO sleepSession(deviceId, startTs, endTs, stagesJSON) VALUES (?, ?, ?, ?)",
                    arguments: ["synthetic", start, start + max(1, points), "[\(stages)]"]
                )
            }
        }
        return try NoopDataAccess.open(configuration: .init(databasePath: url.path, deviceID: "synthetic"))
    }

    private func sessions(in result: JSONValue) throws -> [JSONValue] {
        try array(result.objectValue?["sessions"])
    }

    private func array(_ value: JSONValue?) throws -> [JSONValue] {
        guard case .array(let values) = value else {
            XCTFail("Expected an array")
            return try XCTUnwrap(nil as [JSONValue]?)
        }
        return values
    }
}
