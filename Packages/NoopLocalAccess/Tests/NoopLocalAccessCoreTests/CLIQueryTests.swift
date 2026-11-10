import XCTest
@testable import NoopLocalAccessCore

final class CLIQueryTests: XCTestCase {
    func testAllFiveQueriesDispatchThroughTheSamePayloadAsMCP() throws {
        let url = try TemporaryDatabase.seeded()
        let configuration = LocalAccessConfiguration(databasePath: url.path)
        let queries: [(String, [String])] = [
            ("health_snapshot", ["--days", "14"]),
            ("metric_series", ["--key", "hrv", "--from-day", "2026-06-10", "--to-day", "2026-06-11"]),
            ("data_freshness", []),
            ("sleep_summary", ["--days", "30"]),
            ("workout_summary", ["--days", "90"]),
            ("workout_summary", ["--days", "90", "--include-zones"]),
        ]

        for (tool, flags) in queries {
            let parsed = try NoopCLIQuery.parse(arguments: [tool] + flags)
            let request = NoopCLIQueryRequest(
                toolName: parsed.toolName,
                arguments: parsed.arguments,
                configuration: configuration
            )
            let cliPayload = try NoopCLIQuery.dispatch(request)
            let response = try XCTUnwrap(try NoopMCPServer(configuration: configuration).handle(RPCRequest(
                id: .int(1),
                method: "tools/call",
                params: .object([
                    "name": .string(tool),
                    "arguments": .object(parsed.arguments),
                ])
            )))
            let mcpPayload = try XCTUnwrap(response.objectValue?["result"]?.objectValue?["structuredContent"])

            XCTAssertEqual(
                withoutVolatileFields(mcpPayload),
                withoutVolatileFields(cliPayload),
                "CLI/MCP payload mismatch for \(tool)"
            )
        }
    }

    func testMetricSeriesRequiresKeyAndRejectsUnknownOrMissingFlags() {
        assertUsageError([])
        assertUsageError(["metric_series"])
        assertUsageError(["metric_series", "--unknown", "x"])
        assertUsageError(["metric_series", "--key=hrv"])
        assertUsageError(["metric_series", "--key"])
        assertUsageError(["metric_series", "--key", "hrv", "--limit"])
        assertUsageError(["metric_series", "--key", "hrv", "--key", "rhr"])
        assertUsageError(["metric_series", "--key", "hrv", "extra"])
        assertUsageError(["health_snapshot", "--db-path"])
        assertUsageError(["data_freshness", "--days", "7"])
        assertUsageError(["health_snapshot", "--days", "not-an-integer"])
        assertUsageError(["health_snapshot", "--include-zones"])
        assertUsageError(["sleep_summary", "--include-zones"])
        assertUsageError(["workout_summary", "--include-zones", "--include-zones"])
    }

    func testMetricSeriesParsesTheCompleteFlagContract() throws {
        let parsed = try NoopCLIQuery.parse(arguments: [
            "metric_series",
            "--key", "hrv",
            "--source", "apple-health",
            "--days", "30",
            "--from-day", "2026-06-01",
            "--to-day", "2026-06-30",
            "--limit", "42",
            "--db-path", "/tmp/noop.sqlite",
        ])

        XCTAssertEqual(parsed.toolName, "metric_series")
        XCTAssertEqual(parsed.arguments, [
            "key": .string("hrv"),
            "source": .string("apple-health"),
            "days": .int(30),
            "from_day": .string("2026-06-01"),
            "to_day": .string("2026-06-30"),
            "limit": .int(42),
        ])
        XCTAssertEqual(parsed.configuration.databasePath, "/tmp/noop.sqlite")
    }

    func testCLIValuesArePassedToTheExistingBounds() throws {
        let parsed = try NoopCLIQuery.parse(arguments: ["sleep_summary", "--days", "0"])
        XCTAssertEqual(parsed.arguments["days"], .int(0))

        let request = NoopCLIQueryRequest(
            toolName: parsed.toolName,
            arguments: parsed.arguments,
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.seeded().path)
        )
        let payload = try NoopCLIQuery.dispatch(request)
        XCTAssertEqual(payload.objectValue?["range"]?.objectValue?["days"], .int(1))
    }

    func testSuccessfulOutputIsOneJSONValueAndEncodingFailuresPropagate() throws {
        let value: JSONValue = .object(["ok": .bool(true)])
        let line = try NoopCLIQuery.encodeLine(value)

        XCTAssertEqual(line.last, 0x0A)
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: Data(line.dropLast())), value)
        XCTAssertThrowsError(try NoopCLIQuery.encodeLine(.double(.infinity)))
    }

    func testWorkoutSummaryParsesIncludeZonesFlag() throws {
        let omitted = try NoopCLIQuery.parse(arguments: ["workout_summary", "--days", "14"])
        XCTAssertEqual(omitted.toolName, "workout_summary")
        XCTAssertEqual(omitted.arguments, ["days": .int(14)])

        let parsed = try NoopCLIQuery.parse(arguments: [
            "workout_summary",
            "--days", "14",
            "--include-zones",
            "--db-path", "/tmp/noop.sqlite",
        ])
        XCTAssertEqual(parsed.toolName, "workout_summary")
        XCTAssertEqual(parsed.arguments, [
            "days": .int(14),
            "include_zones": .bool(true),
        ])
        XCTAssertEqual(parsed.configuration.databasePath, "/tmp/noop.sqlite")
    }

    func testWorkoutSummaryDefaultOmitsZonesPayload() throws {
        let now = Int(Date().timeIntervalSince1970)
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "workout_summary",
            arguments: ["days": .int(3)],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withWorkoutZones(now: now).path)
        ))
        XCTAssertEqual(payload.objectValue?["count"], .int(5))
        XCTAssertEqual(payload.objectValue?["totalDurationMin"], .double(150))
        XCTAssertEqual(payload.objectValue?["totalEnergyKcal"], .double(1550))
        XCTAssertEqual(payload.objectValue?["totalStrain"], .double(42.5))
        guard case .array(let workouts) = payload.objectValue?["workouts"] else {
            return XCTFail("Expected workouts")
        }
        XCTAssertEqual(workouts.count, 5)
        var sawZones = false
        var sawNoZones = false
        for workout in workouts {
            let object = try XCTUnwrap(workout.objectValue)
            XCTAssertNotNil(object["hasZones"])
            XCTAssertNil(object["zones"])
            XCTAssertNil(object["zonesJSON"])
            if object["hasZones"] == .bool(true) { sawZones = true }
            if object["hasZones"] == .bool(false) { sawNoZones = true }
        }
        XCTAssertTrue(sawZones)
        XCTAssertTrue(sawNoZones)
    }

    func testWorkoutSummaryIncludeZonesAttachesBoundedPayload() throws {
        let now = Int(Date().timeIntervalSince1970)
        let macStart = now - 3_600
        let androidStart = now - 7_200
        let noneStart = now - 10_800
        let badStart = now - 14_400
        let hugeStart = now - 18_000
        let configuration = LocalAccessConfiguration(databasePath: try TemporaryDatabase.withWorkoutZones(now: now).path)

        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "workout_summary",
            arguments: [
                "days": .int(3),
                "include_zones": .bool(true),
            ],
            configuration: configuration
        ))
        guard case .array(let workouts) = payload.objectValue?["workouts"] else {
            return XCTFail("Expected workouts")
        }
        XCTAssertEqual(workouts.count, 5)

        let mac = try XCTUnwrap(workouts.first { $0.objectValue?["startTs"] == .int(macStart) }?.objectValue)
        XCTAssertEqual(mac["hasZones"], .bool(true))
        XCTAssertEqual(mac["zones"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(mac["zones"]?.objectValue?["payload"], .object([
            "z1": .double(12.5),
            "z5": .double(4.5),
        ]))
        XCTAssertNil(mac["zonesJSON"])

        let android = try XCTUnwrap(workouts.first { $0.objectValue?["startTs"] == .int(androidStart) }?.objectValue)
        XCTAssertEqual(android["hasZones"], .bool(true))
        XCTAssertEqual(android["zones"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(android["zones"]?.objectValue?["payload"]?.objectValue?["zone1"], .int(10))
        XCTAssertEqual(android["zones"]?.objectValue?["payload"]?.objectValue?["zone5"], .int(15))

        let none = try XCTUnwrap(workouts.first { $0.objectValue?["startTs"] == .int(noneStart) }?.objectValue)
        XCTAssertEqual(none["hasZones"], .bool(false))
        XCTAssertEqual(none["zones"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(none["zones"]?.objectValue?["payload"], .null)

        let bad = try XCTUnwrap(workouts.first { $0.objectValue?["startTs"] == .int(badStart) }?.objectValue)
        XCTAssertEqual(bad["hasZones"], .bool(true))
        XCTAssertEqual(bad["zones"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(bad["zones"]?.objectValue?["payload"], .string("not-json"))

        let huge = try XCTUnwrap(workouts.first { $0.objectValue?["startTs"] == .int(hugeStart) }?.objectValue)
        XCTAssertEqual(huge["hasZones"], .bool(true))
        XCTAssertEqual(huge["zones"]?.objectValue?["truncated"], .bool(true))
        guard case .object(let keys) = huge["zones"]?.objectValue?["payload"] else {
            return XCTFail("Expected truncated object payload")
        }
        XCTAssertEqual(keys.count, 32)
        XCTAssertNil(huge["zonesJSON"])

        let mcp = try XCTUnwrap(try NoopMCPServer(configuration: configuration).handle(RPCRequest(
            id: .int(1),
            method: "tools/call",
            params: .object([
                "name": .string("workout_summary"),
                "arguments": .object([
                    "days": .int(3),
                    "include_zones": .bool(true),
                ]),
            ])
        )))
        let mcpPayload = try XCTUnwrap(mcp.objectValue?["result"]?.objectValue?["structuredContent"])
        XCTAssertEqual(withoutVolatileFields(mcpPayload), withoutVolatileFields(payload))
    }

    private func assertUsageError(_ arguments: [String], file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try NoopCLIQuery.parse(arguments: arguments), file: file, line: line) { error in
            guard let error = error as? NoopCLIQueryError else {
                return XCTFail("Expected usage error, got \(error)", file: file, line: line)
            }
            XCTAssertEqual(error.exitCode, 64, file: file, line: line)
        }
    }

    private func withoutVolatileFields(_ value: JSONValue) -> JSONValue {
        switch value {
        case .array(let values):
            return .array(values.map(withoutVolatileFields))
        case .object(let object):
            let volatile = Set(["generatedAt", "ageSeconds", "fromTs", "toTs"])
            var normalized: [String: JSONValue] = [:]
            for (key, value) in object where !volatile.contains(key) {
                normalized[key] = withoutVolatileFields(value)
            }
            return .object(normalized)
        default:
            return value
        }
    }
}
