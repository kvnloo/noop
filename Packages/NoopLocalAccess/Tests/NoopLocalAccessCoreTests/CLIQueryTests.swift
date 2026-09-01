import XCTest
@testable import NoopLocalAccessCore

final class CLIQueryTests: XCTestCase {
    func testAllQueriesDispatchThroughTheSamePayloadAsMCP() throws {
        let url = try TemporaryDatabase.withEvents()
        let configuration = LocalAccessConfiguration(databasePath: url.path)
        let queries: [(String, [String])] = [
            ("health_snapshot", ["--days", "14"]),
            ("metric_series", ["--key", "hrv", "--from-day", "2026-06-10", "--to-day", "2026-06-11"]),
            ("data_freshness", []),
            ("sleep_summary", ["--days", "30"]),
            ("workout_summary", ["--days", "90"]),
            ("hr_series", ["--from-ts", "100", "--to-ts", "102", "--bucket-seconds", "1", "--limit", "50"]),
            ("sleep_stages", ["--days", "30", "--limit", "14", "--max-points", "200"]),
            ("event_series", ["--kind", "ALPHA", "--from-ts", "100", "--to-ts", "102", "--limit", "50"]),
            ("rr_series", ["--from-ts", "100", "--to-ts", "103", "--limit", "50"]),
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
        assertUsageError(["hr_series", "--from-ts", "100"])
        assertUsageError(["hr_series", "--to-ts", "102"])
        assertUsageError(["hr_series", "--unknown", "x"])
        assertUsageError(["health_snapshot", "--hours", "1"])
        assertUsageError(["metric_series", "--key", "hrv", "--from-ts", "100", "--to-ts", "102"])
        assertUsageError(["sleep_stages", "--from-ts", "100"])
        assertUsageError(["sleep_stages", "--max-points"])
        assertUsageError(["health_snapshot", "--max-points", "10"])
        assertUsageError(["sleep_summary", "--limit", "1"])
        assertUsageError(["event_series"])
        assertUsageError(["event_series", "--from-ts", "100", "--to-ts", "102"])
        assertUsageError(["event_series", "--kind", "ALPHA", "--from-ts", "100"])
        assertUsageError(["event_series", "--kind", "ALPHA", "--to-ts", "102"])
        assertUsageError(["event_series", "--kind"])
        assertUsageError(["event_series", "--unknown", "x"])
        assertUsageError(["health_snapshot", "--kind", "ALPHA"])
        assertUsageError(["hr_series", "--kind", "ALPHA"])
        assertUsageError(["sleep_stages", "--kind", "ALPHA"])
        assertUsageError(["event_series", "--kind", "ALPHA", "--max-points", "10"])
        assertUsageError(["event_series", "--kind", "ALPHA", "--bucket-seconds", "1"])
        assertUsageError(["rr_series", "--from-ts", "100"])
        assertUsageError(["rr_series", "--to-ts", "102"])
        assertUsageError(["rr_series", "--unknown", "x"])
        assertUsageError(["rr_series", "--kind", "ALPHA"])
        assertUsageError(["rr_series", "--bucket-seconds", "1"])
        assertUsageError(["rr_series", "--max-points", "10"])
        assertUsageError(["health_snapshot", "--from-ts", "100", "--to-ts", "102"])
        assertUsageError(["sleep_stages", "--hours", "1"])
    }

    func testHRSeriesParsesTheCompleteFlagContract() throws {
        let parsed = try NoopCLIQuery.parse(arguments: [
            "hr_series",
            "--hours", "2",
            "--from-ts", "100",
            "--to-ts", "102",
            "--bucket-seconds", "1",
            "--limit", "50",
            "--device-id", "my-whoop",
            "--db-path", "/tmp/noop.sqlite",
        ])

        XCTAssertEqual(parsed.toolName, "hr_series")
        XCTAssertEqual(parsed.arguments, [
            "hours": .int(2),
            "from_ts": .int(100),
            "to_ts": .int(102),
            "bucket_seconds": .int(1),
            "limit": .int(50),
            "device_id": .string("my-whoop"),
        ])
        XCTAssertEqual(parsed.configuration.databasePath, "/tmp/noop.sqlite")
    }

    func testHRSeriesBucketsMatchMeasuredThenPPGFillIn() throws {
        let url = try TemporaryDatabase.seeded()
        let configuration = LocalAccessConfiguration(databasePath: url.path)
        let parsed = try NoopCLIQuery.parse(arguments: [
            "hr_series", "--from-ts", "100", "--to-ts", "102", "--bucket-seconds", "1",
        ])
        let request = NoopCLIQueryRequest(
            toolName: parsed.toolName,
            arguments: parsed.arguments,
            configuration: configuration
        )
        let payload = try NoopCLIQuery.dispatch(request)
        let object = try XCTUnwrap(payload.objectValue)

        XCTAssertEqual(object["bucketSeconds"], .int(1))
        XCTAssertEqual(object["returned"], .int(3))
        XCTAssertEqual(object["truncated"], .bool(false))
        XCTAssertEqual(object["range"]?.objectValue?["fromTs"], .int(100))
        XCTAssertEqual(object["range"]?.objectValue?["toTs"], .int(102))
        guard case .array(let points) = object["points"] else {
            return XCTFail("Expected points array")
        }
        XCTAssertEqual(points.count, 3)
        XCTAssertEqual(points[0].objectValue?["ts"], .int(100))
        XCTAssertEqual(points[1].objectValue?["ts"], .int(101))
        XCTAssertEqual(points[2].objectValue?["ts"], .int(102))
        XCTAssertEqual(points[0].objectValue?["bpm"]?.intValue, 70)
        XCTAssertEqual(points[1].objectValue?["bpm"]?.intValue, 72)
        switch points[2].objectValue?["bpm"] {
        case .double(let bpm):
            XCTAssertEqual(bpm, 73.2, accuracy: 0.01)
        default:
            XCTFail("Expected PPG bpm 73.2, got \(String(describing: points[2].objectValue?["bpm"]))")
        }
        XCTAssertNil(points[0].objectValue?["conf"])

        let truncated = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "hr_series",
            arguments: [
                "from_ts": .int(100),
                "to_ts": .int(102),
                "bucket_seconds": .int(1),
                "limit": .int(2),
            ],
            configuration: configuration
        ))
        XCTAssertEqual(truncated.objectValue?["returned"], .int(2))
        XCTAssertEqual(truncated.objectValue?["truncated"], .bool(true))
        guard case .array(let suffix) = truncated.objectValue?["points"] else {
            return XCTFail("Expected truncated points")
        }
        XCTAssertEqual(suffix.compactMap { $0.objectValue?["ts"]?.intValue }, [101, 102])
    }

    func testHRSeriesHoursZeroIsClampedByTheDispatcher() throws {
        let parsed = try NoopCLIQuery.parse(arguments: ["hr_series", "--hours", "0"])
        XCTAssertEqual(parsed.arguments["hours"], .int(0))

        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: parsed.toolName,
            arguments: parsed.arguments,
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.seeded().path)
        ))
        XCTAssertEqual(payload.objectValue?["range"]?.objectValue?["hours"], .int(1))
    }


    func testSleepStagesParsesTheCompleteFlagContract() throws {
        let parsed = try NoopCLIQuery.parse(arguments: [
            "sleep_stages",
            "--days", "14",
            "--limit", "7",
            "--max-points", "40",
            "--db-path", "/tmp/noop.sqlite",
        ])

        XCTAssertEqual(parsed.toolName, "sleep_stages")
        XCTAssertEqual(parsed.arguments, [
            "days": .int(14),
            "limit": .int(7),
            "max_points": .int(40),
        ])
        XCTAssertEqual(parsed.configuration.databasePath, "/tmp/noop.sqlite")
    }

    func testSleepStagesDecodesSegmentsAndCapsPoints() throws {
        let now = Int(Date().timeIntervalSince1970)
        let url = try TemporaryDatabase.withSleepStages(now: now)
        let configuration = LocalAccessConfiguration(databasePath: url.path)
        let newerStart = now - 3_600

        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "sleep_stages",
            arguments: [
                "days": .int(3),
                "limit": .int(1),
                "max_points": .int(2),
            ],
            configuration: configuration
        ))
        let object = try XCTUnwrap(payload.objectValue)
        XCTAssertEqual(object["count"], .int(3))
        XCTAssertEqual(object["returned"], .int(1))
        XCTAssertEqual(object["truncated"], .bool(true))
        guard case .array(let sessions) = object["sessions"] else {
            return XCTFail("Expected sessions array")
        }
        XCTAssertEqual(sessions.count, 1)
        let session = try XCTUnwrap(sessions[0].objectValue)
        XCTAssertEqual(session["startTs"], .int(newerStart))
        XCTAssertEqual(session["hasStages"], .bool(true))
        XCTAssertEqual(session["shape"], .string("segments"))
        XCTAssertEqual(session["truncated"], .bool(true))
        guard case .array(let stages) = session["stages"] else {
            return XCTFail("Expected stages array")
        }
        XCTAssertEqual(stages.count, 2)
        XCTAssertEqual(stages[0].objectValue?["stage"], .string("light"))
        XCTAssertEqual(stages[1].objectValue?["stage"], .string("deep"))
        XCTAssertEqual(stages[0].objectValue?["start"], .int(newerStart))
        XCTAssertEqual(stages[0].objectValue?["end"], .int(newerStart + 300))
        XCTAssertEqual(session["minutes"]?.objectValue?["light"], .double(5))
        XCTAssertEqual(session["minutes"]?.objectValue?["deep"], .double(10))
        XCTAssertEqual(session["minutes"]?.objectValue?["rem"], .double(0))
        XCTAssertEqual(session["minutes"]?.objectValue?["awake"], .double(0))
    }

    func testSleepStagesMinutesShapeDoesNotInventATimeline() throws {
        let now = Int(Date().timeIntervalSince1970)
        let url = try TemporaryDatabase.withSleepStages(now: now)
        let importedStart = now - 180_000
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "sleep_stages",
            arguments: [
                "days": .int(3),
                "limit": .int(3),
                "max_points": .int(200),
            ],
            configuration: LocalAccessConfiguration(databasePath: url.path)
        ))
        guard case .array(let sessions) = payload.objectValue?["sessions"] else {
            return XCTFail("Expected sessions array")
        }
        XCTAssertEqual(payload.objectValue?["returned"], .int(3))
        XCTAssertEqual(payload.objectValue?["truncated"], .bool(false))
        let imported = try XCTUnwrap(sessions.first { $0.objectValue?["startTs"] == .int(importedStart) }?.objectValue)
        XCTAssertEqual(imported["shape"], .string("minutes"))
        XCTAssertEqual(imported["hasStages"], .bool(true))
        XCTAssertEqual(imported["truncated"], .bool(false))
        guard case .array(let stages) = imported["stages"] else {
            return XCTFail("Expected empty stages array")
        }
        XCTAssertEqual(stages.count, 0)
        XCTAssertEqual(imported["minutes"]?.objectValue?["light"], .double(100))
        XCTAssertEqual(imported["minutes"]?.objectValue?["deep"], .double(50))
        XCTAssertEqual(imported["minutes"]?.objectValue?["rem"], .double(40))
        XCTAssertEqual(imported["minutes"]?.objectValue?["awake"], .double(10))

        let older = try XCTUnwrap(sessions.first { $0.objectValue?["startTs"] == .int(now - 90_000) }?.objectValue)
        XCTAssertEqual(older["shape"], .string("segments"))
        guard case .array(let olderStages) = older["stages"] else {
            return XCTFail("Expected older stages")
        }
        XCTAssertEqual(olderStages.count, 3)
        XCTAssertEqual(olderStages[2].objectValue?["stage"], .string("awake"))
    }

    func testSleepSummaryStillOmitsTheStagePayload() throws {
        let url = try TemporaryDatabase.withSleepStages()
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "sleep_summary",
            arguments: ["days": .int(3)],
            configuration: LocalAccessConfiguration(databasePath: url.path)
        ))
        XCTAssertEqual(payload.objectValue?["count"], .int(3))
        guard case .array(let sessions) = payload.objectValue?["sessions"] else {
            return XCTFail("Expected sessions")
        }
        XCTAssertFalse(sessions.isEmpty)
        for session in sessions {
            let object = try XCTUnwrap(session.objectValue)
            XCTAssertNotNil(object["hasStages"])
            XCTAssertNil(object["stages"])
            XCTAssertNil(object["shape"])
            XCTAssertNil(object["minutes"])
        }
    }

    func testSleepStagesDaysZeroIsClampedByTheDispatcher() throws {
        let parsed = try NoopCLIQuery.parse(arguments: ["sleep_stages", "--days", "0", "--limit", "0"])
        XCTAssertEqual(parsed.arguments["days"], .int(0))
        XCTAssertEqual(parsed.arguments["limit"], .int(0))

        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: parsed.toolName,
            arguments: parsed.arguments,
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withSleepStages().path)
        ))
        XCTAssertEqual(payload.objectValue?["range"]?.objectValue?["days"], .int(1))
    }

    func testEventSeriesParsesTheCompleteFlagContract() throws {
        let parsed = try NoopCLIQuery.parse(arguments: [
            "event_series",
            "--kind", "ALPHA",
            "--hours", "2",
            "--from-ts", "100",
            "--to-ts", "102",
            "--limit", "50",
            "--device-id", "my-whoop",
            "--db-path", "/tmp/noop.sqlite",
        ])

        XCTAssertEqual(parsed.toolName, "event_series")
        XCTAssertEqual(parsed.arguments, [
            "kind": .string("ALPHA"),
            "hours": .int(2),
            "from_ts": .int(100),
            "to_ts": .int(102),
            "limit": .int(50),
            "device_id": .string("my-whoop"),
        ])
        XCTAssertEqual(parsed.configuration.databasePath, "/tmp/noop.sqlite")
    }

    func testEventSeriesFiltersByKindAndParsesPayload() throws {
        let url = try TemporaryDatabase.withEvents()
        let configuration = LocalAccessConfiguration(databasePath: url.path)
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "event_series",
            arguments: [
                "kind": .string("ALPHA"),
                "from_ts": .int(100),
                "to_ts": .int(102),
            ],
            configuration: configuration
        ))
        let object = try XCTUnwrap(payload.objectValue)
        XCTAssertEqual(object["kind"], .string("ALPHA"))
        XCTAssertEqual(object["returned"], .int(3))
        XCTAssertEqual(object["truncated"], .bool(false))
        XCTAssertEqual(object["range"]?.objectValue?["fromTs"], .int(100))
        XCTAssertEqual(object["range"]?.objectValue?["toTs"], .int(102))
        guard case .array(let points) = object["points"] else {
            return XCTFail("Expected points array")
        }
        XCTAssertEqual(points.count, 3)
        XCTAssertEqual(points[0].objectValue?["ts"], .int(100))
        XCTAssertEqual(points[1].objectValue?["ts"], .int(101))
        XCTAssertEqual(points[2].objectValue?["ts"], .int(102))
        XCTAssertEqual(points[0].objectValue?["kind"], .string("ALPHA"))
        XCTAssertEqual(points[0].objectValue?["payload"], .object(["ok": .bool(true), "n": .int(1)]))
        XCTAssertEqual(points[1].objectValue?["payload"], .string("not-json"))
        XCTAssertEqual(points[2].objectValue?["payload"], .object(["later": .bool(true)]))
        XCTAssertNotNil(points[0].objectValue?["iso"])

        let truncated = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "event_series",
            arguments: [
                "kind": .string("ALPHA"),
                "from_ts": .int(100),
                "to_ts": .int(102),
                "limit": .int(2),
            ],
            configuration: configuration
        ))
        XCTAssertEqual(truncated.objectValue?["returned"], .int(2))
        XCTAssertEqual(truncated.objectValue?["truncated"], .bool(true))
        guard case .array(let suffix) = truncated.objectValue?["points"] else {
            return XCTFail("Expected truncated points")
        }
        XCTAssertEqual(suffix.compactMap { $0.objectValue?["ts"]?.intValue }, [101, 102])

        let unknown = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "event_series",
            arguments: [
                "kind": .string("NO_SUCH_KIND"),
                "from_ts": .int(0),
                "to_ts": .int(10_000),
            ],
            configuration: configuration
        ))
        XCTAssertEqual(unknown.objectValue?["kind"], .string("NO_SUCH_KIND"))
        XCTAssertEqual(unknown.objectValue?["returned"], .int(0))
        XCTAssertEqual(unknown.objectValue?["truncated"], .bool(false))
        guard case .array(let emptyUnknown) = unknown.objectValue?["points"] else {
            return XCTFail("Expected empty points")
        }
        XCTAssertEqual(emptyUnknown.count, 0)

        let missingTable = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "event_series",
            arguments: [
                "kind": .string("ALPHA"),
                "from_ts": .int(100),
                "to_ts": .int(102),
            ],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.seeded().path)
        ))
        XCTAssertEqual(missingTable.objectValue?["returned"], .int(0))
        guard case .array(let emptyMissing) = missingTable.objectValue?["points"] else {
            return XCTFail("Expected empty points when event table is missing")
        }
        XCTAssertEqual(emptyMissing.count, 0)
    }

    func testEventSeriesHoursZeroIsClampedByTheDispatcher() throws {
        let parsed = try NoopCLIQuery.parse(arguments: ["event_series", "--kind", "ALPHA", "--hours", "0"])
        XCTAssertEqual(parsed.arguments["hours"], .int(0))

        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: parsed.toolName,
            arguments: parsed.arguments,
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withEvents().path)
        ))
        XCTAssertEqual(payload.objectValue?["range"]?.objectValue?["hours"], .int(1))
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

    func testRRSeriesMatchesWhoopStoreFiltersAndSuffixLimit() throws {
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
