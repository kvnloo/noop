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

    func testDataFreshnessAddsSourceTimestampsAndKeepsExistingContract() throws {
        func freshness(_ url: URL) throws -> [String: JSONValue] {
            let result = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
                toolName: "data_freshness",
                arguments: [:],
                configuration: LocalAccessConfiguration(databasePath: url.path)
            ))
            return try XCTUnwrap(result.objectValue)
        }

        let seeded = try freshness(TemporaryDatabase.seeded())
        for key in [
            "generatedAt", "deviceId", "computedDeviceId", "latestHeartRateSample",
            "latestRrInterval", "latestEvent", "latestSleepSession", "storage",
            "coverage", "metricKeys",
        ] {
            XCTAssertNotNil(seeded[key], "missing freshness key \(key)")
        }
        XCTAssertEqual(seeded["deviceId"], .string("my-whoop"))
        XCTAssertEqual(seeded["latestHeartRateSample"]?.objectValue?["ts"], .int(102))
        XCTAssertEqual(seeded["latestRrInterval"]?.objectValue?["ts"], .int(101))
        XCTAssertEqual(seeded["latestEvent"], .null)
        XCTAssertEqual(seeded["latestSleepSession"]?.objectValue?["ts"], .int(2000))
        XCTAssertNil(seeded["analysisFingerprint"])
        XCTAssertNil(seeded["score"])
        XCTAssertNil(seeded["recoveryScore"])

        let withEvents = try freshness(TemporaryDatabase.withEvents())
        XCTAssertEqual(withEvents["latestEvent"]?.objectValue?["ts"], .int(103))
        let qualifiedRR = try freshness(TemporaryDatabase.withRRQualityRows())
        XCTAssertEqual(qualifiedRR["latestRrInterval"]?.objectValue?["ts"], .int(104))
        let withoutRR = try freshness(TemporaryDatabase.withoutRRInterval())
        XCTAssertEqual(withoutRR["latestRrInterval"], .null)
        let withoutSleep = try freshness(TemporaryDatabase.withoutSleepSession())
        XCTAssertEqual(withoutSleep["latestSleepSession"], .null)
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
