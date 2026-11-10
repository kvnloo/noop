import XCTest
import GRDB
@testable import NoopLocalAccessCore

final class SleepStageValidationTests: XCTestCase {
    private func minutes(_ stagesJSON: String) throws -> [String: JSONValue] {
        let url = try TemporaryDatabase.seeded()
        let now = Int(Date().timeIntervalSince1970)
        let queue = try DatabaseQueue(path: url.path)
        try queue.write { db in
            try db.execute(sql: "INSERT INTO sleepSession(deviceId,startTs,endTs,stagesJSON) VALUES (?,?,?,?)",
                           arguments: ["my-whoop", now - 3600, now - 1800, stagesJSON])
        }
        let payload = try NoopToolDispatcher(configuration: LocalAccessConfiguration(databasePath: url.path))
            .dispatch(name: "sleep_stages", arguments: ["days": .int(1)])
        XCTAssertNoThrow(try NoopCLIQuery.encodeLine(payload))
        guard case .array(let sessions) = payload.objectValue?["sessions"] else {
            XCTFail("Expected sessions")
            return [:]
        }
        return try XCTUnwrap(sessions.first?.objectValue?["minutes"]?.objectValue)
    }

    func testMinuteDictionaryRejectsNegativeAndNonfiniteValues() throws {
        let result = try minutes(#"{"light":-5,"deep":"nan","rem":"inf","awake":7}"#)
        XCTAssertEqual(result, ["light": .double(0), "deep": .double(0), "rem": .double(0), "awake": .double(7)])
    }

    func testMinuteArrayKeepsValidValuesAfterMalformedDurations() throws {
        let result = try minutes(#"[{"stage":"light","min":-5},{"stage":"light","min":"nan"},{"stage":"light","min":2.5},{"stage":"wake","minutes":"3"}]"#)
        XCTAssertEqual(result["light"], .double(2.5))
        XCTAssertEqual(result["awake"], .double(3))
    }

    func testMinuteArrayDoesNotOverflowFiniteTotals() throws {
        let result = try minutes(#"[{"stage":"deep","min":1e308},{"stage":"deep","min":1e308},{"stage":"rem","min":4}]"#)
        XCTAssertEqual(result["deep"], .double(1e308))
        XCTAssertEqual(result["rem"], .double(4))
    }
}
