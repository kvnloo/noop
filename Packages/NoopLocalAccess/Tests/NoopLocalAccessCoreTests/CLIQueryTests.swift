import XCTest
@testable import NoopLocalAccessCore

final class CLIQueryTests: XCTestCase {
    func testAllQueriesDispatchThroughTheSamePayloadAsMCP() throws {
        let url = try TemporaryDatabase.seeded()
        let configuration = LocalAccessConfiguration(databasePath: url.path)
        let queries: [(String, [String])] = [
            ("health_snapshot", ["--days", "14"]),
            ("metric_series", ["--key", "hrv", "--from-day", "2026-06-10", "--to-day", "2026-06-11"]),
            ("data_freshness", []),
            ("sleep_summary", ["--days", "30"]),
            ("workout_summary", ["--days", "90"]),
            ("rr_series", ["--from-ts", "100", "--to-ts", "103", "--limit", "2"]),
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
        assertUsageError(["rr_series", "--from-ts", "100"])
        assertUsageError(["rr_series", "--to-ts", "103"])
        assertUsageError(["rr_series", "--kind", "ALPHA"])
        assertUsageError(["rr_series", "--bucket-seconds", "1"])
        assertUsageError(["rr_series", "--days", "7"])
        assertUsageError(["rr_series", "--hours", "bad"])
        assertUsageError(["rr_series", "--hours", "1", "--hours", "2"])
        assertUsageError(["rr_series", "--limit"])
        assertUsageError(["rr_series", "--limit=2"])
    }

    func testRRSeriesParsesTheCompleteFlagContract() throws {
        let parsed = try NoopCLIQuery.parse(arguments: [
            "rr_series",
            "--hours", "2",
            "--from-ts", "100",
            "--to-ts", "103",
            "--limit", "50",
            "--device-id", "my-whoop",
            "--db-path", "/tmp/noop.sqlite",
        ])

        XCTAssertEqual(parsed.toolName, "rr_series")
        XCTAssertEqual(parsed.arguments, [
            "hours": .int(2),
            "from_ts": .int(100),
            "to_ts": .int(103),
            "limit": .int(50),
            "device_id": .string("my-whoop"),
        ])
        XCTAssertEqual(parsed.configuration.databasePath, "/tmp/noop.sqlite")
    }

    func testRRSeriesMatchesRawReadFiltersAndSuffixLimit() throws {
        let url = try TemporaryDatabase.withRRIntervals()
        let configuration = LocalAccessConfiguration(databasePath: url.path)
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "rr_series",
            arguments: [
                "from_ts": .int(100),
                "to_ts": .int(103),
            ],
            configuration: configuration
        ))
        let object = try XCTUnwrap(payload.objectValue)
        XCTAssertEqual(object["returned"], .int(4))
        XCTAssertEqual(object["truncated"], .bool(false))
        XCTAssertEqual(object["range"]?.objectValue?["fromTs"], .int(100))
        XCTAssertEqual(object["range"]?.objectValue?["toTs"], .int(103))
        guard case .array(let points) = object["points"] else {
            return XCTFail("Expected points array")
        }
        XCTAssertEqual(points.count, 4)
        XCTAssertEqual(points.compactMap { $0.objectValue?["ts"]?.intValue }, [100, 101, 101, 103])
        XCTAssertEqual(points.compactMap { $0.objectValue?["rrMs"]?.intValue }, [800, 790, 810, 840])
        XCTAssertEqual(points[0].objectValue?["seq"], .int(0))
        XCTAssertEqual(points[1].objectValue?["ord"], .int(0))
        XCTAssertEqual(points[2].objectValue?["ord"], .int(1))
        XCTAssertEqual(points[2].objectValue?["seq"], .int(1))
        XCTAssertNotNil(points[0].objectValue?["iso"])
        XCTAssertNil(points[0].objectValue?["srcChannel"])

        let truncated = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "rr_series",
            arguments: [
                "from_ts": .int(100),
                "to_ts": .int(103),
                "limit": .int(2),
            ],
            configuration: configuration
        ))
        XCTAssertEqual(truncated.objectValue?["returned"], .int(2))
        XCTAssertEqual(truncated.objectValue?["truncated"], .bool(true))
        guard case .array(let suffix) = truncated.objectValue?["points"] else {
            return XCTFail("Expected truncated points")
        }
        XCTAssertEqual(suffix.compactMap { $0.objectValue?["ts"]?.intValue }, [101, 103])
        XCTAssertEqual(suffix.compactMap { $0.objectValue?["rrMs"]?.intValue }, [810, 840])

        let missingTable = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "rr_series",
            arguments: [
                "from_ts": .int(100),
                "to_ts": .int(103),
            ],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withoutRRInterval().path)
        ))
        XCTAssertEqual(missingTable.objectValue?["returned"], .int(0))
        XCTAssertEqual(missingTable.objectValue?["truncated"], .bool(false))
        guard case .array(let emptyMissing) = missingTable.objectValue?["points"] else {
            return XCTFail("Expected empty points when rrInterval table is missing")
        }
        XCTAssertEqual(emptyMissing.count, 0)

        let legacy = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "rr_series",
            arguments: [
                "from_ts": .int(100),
                "to_ts": .int(103),
            ],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.seeded().path)
        ))
        XCTAssertEqual(legacy.objectValue?["returned"], .int(1))
        guard case .array(let legacyPoints) = legacy.objectValue?["points"] else {
            return XCTFail("Expected legacy points")
        }
        XCTAssertEqual(legacyPoints[0].objectValue?["ts"], .int(101))
        XCTAssertEqual(legacyPoints[0].objectValue?["rrMs"], .int(850))
        XCTAssertNil(legacyPoints[0].objectValue?["seq"])
        XCTAssertNil(legacyPoints[0].objectValue?["ord"])
    }

    func testRRSeriesHoursZeroIsClampedByTheDispatcher() throws {
        let parsed = try NoopCLIQuery.parse(arguments: ["rr_series", "--hours", "0"])
        XCTAssertEqual(parsed.arguments["hours"], .int(0))

        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: parsed.toolName,
            arguments: parsed.arguments,
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withRRIntervals().path)
        ))
        XCTAssertEqual(payload.objectValue?["range"]?.objectValue?["hours"], .int(1))
    }

    func testRRSeriesEnforces2000PointMaximumAndNewestSuffix() throws {
        let url = try TemporaryDatabase.withDenseRRIntervals()
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "rr_series",
            arguments: [
                "from_ts": .int(200),
                "to_ts": .int(2202),
                "limit": .int(999_999),
            ],
            configuration: LocalAccessConfiguration(databasePath: url.path)
        ))
        XCTAssertEqual(payload.objectValue?["returned"], .int(2_000))
        XCTAssertEqual(payload.objectValue?["truncated"], .bool(true))
        guard case .array(let points) = payload.objectValue?["points"] else {
            return XCTFail("Expected bounded RR points array")
        }
        XCTAssertEqual(points.count, 2_000)
        XCTAssertEqual(points.first?.objectValue?["ts"], .int(203))
        XCTAssertEqual(points.last?.objectValue?["ts"], .int(2202))
    }

    func testRRSeriesExcludesMalformedRowsInsteadOfFailingTheRead() throws {
        let url = try TemporaryDatabase.withRRIntervals()
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "rr_series",
            arguments: ["from_ts": .int(101), "to_ts": .int(101)],
            configuration: LocalAccessConfiguration(databasePath: url.path)
        ))
        XCTAssertEqual(payload.objectValue?["returned"], .int(2))
        guard case .array(let points) = payload.objectValue?["points"] else {
            return XCTFail("Expected RR points array")
        }
        XCTAssertEqual(points.compactMap { $0.objectValue?["rrMs"]?.intValue }, [790, 810])
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
