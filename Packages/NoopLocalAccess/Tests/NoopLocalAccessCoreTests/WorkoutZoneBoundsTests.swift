import XCTest
import GRDB
@testable import NoopLocalAccessCore

final class WorkoutZoneBoundsTests: XCTestCase {
    private func zones(_ raw: String) throws -> [String: JSONValue] {
        let url = try TemporaryDatabase.seeded()
        let now = Int(Date().timeIntervalSince1970)
        let queue = try DatabaseQueue(path: url.path)
        try queue.write { db in
            try db.execute(sql: "INSERT INTO workout(deviceId,startTs,endTs,sport,source,zonesJSON) VALUES (?,?,?,?,?,?)",
                           arguments: ["my-whoop", now - 3600, now - 1800, "run", "whoop", raw])
        }
        let payload = try NoopToolDispatcher(configuration: LocalAccessConfiguration(databasePath: url.path))
            .dispatch(name: "workout_summary", arguments: ["days": .int(1), "include_zones": .bool(true)])
        XCTAssertNoThrow(try NoopCLIQuery.encodeLine(payload))
        guard case .array(let workouts) = payload.objectValue?["workouts"] else {
            XCTFail("Expected workouts")
            return [:]
        }
        return try XCTUnwrap(workouts.first?.objectValue?["zones"]?.objectValue)
    }

    func testOversizedNestedPayloadIsTruncated() throws {
        for text in [String(repeating: "x", count: 65_529), String(repeating: "é", count: 32_765)] {
            let raw = "{\"v\":\"" + text + "\"}"
            XCTAssertGreaterThan(raw.utf8.count, 65_536)
            let result = try zones(raw)
            XCTAssertEqual(result["truncated"], .bool(true))
            let text = try XCTUnwrap(result["payload"]?.stringValue)
            XCTAssertEqual(text, String(raw.unicodeScalars.prefix(2048)))
        }
    }

    func testPayloadAtByteLimitRemainsStructured() throws {
        let text = String(repeating: "x", count: 65_528)
        let raw = "{\"v\":\"" + text + "\"}"
        XCTAssertEqual(raw.utf8.count, 65_536)
        let result = try zones(raw)
        XCTAssertEqual(result["truncated"], .bool(false))
        XCTAssertEqual(result["payload"]?.objectValue?["v"], .string(text))
    }

    func testCombiningCharactersCannotBypassStringBounds() throws {
        let text = "a" + String(repeating: "\u{301}", count: 3000)
        XCTAssertEqual(text.count, 1)
        for raw in [text, "\"" + text + "\""] {
            let result = try zones(raw)
            XCTAssertEqual(result["truncated"], .bool(true))
            let payload = try XCTUnwrap(result["payload"]?.stringValue)
            XCTAssertEqual(payload.unicodeScalars.count, 2048)
        }
    }
}
