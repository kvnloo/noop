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
            ("sleep_summary", ["--days", "30", "--include-motion"]),
            ("sleep_summary", ["--days", "30", "--include-sleep-state"]),
            ("sleep_summary", ["--days", "30", "--include-start-adjusted"]),
            ("workout_summary", ["--days", "90"]),
            ("workout_summary", ["--days", "90", "--include-zones"]),
            ("workout_summary", ["--days", "90", "--include-notes"]),
            ("hr_series", ["--from-ts", "100", "--to-ts", "102", "--bucket-seconds", "1", "--limit", "50"]),
            ("spo2_series", ["--from-ts", "100", "--to-ts", "102", "--bucket-seconds", "1", "--limit", "50"]),
            ("skin_temp_series", ["--from-ts", "100", "--to-ts", "102", "--bucket-seconds", "1", "--limit", "50"]),
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

    func testDataFreshnessAddsLastTsKeysAndKeepsExistingOnes() throws {
        let seeded = LocalAccessConfiguration(databasePath: try TemporaryDatabase.seeded().path)
        let seededPayload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "data_freshness",
            arguments: [:],
            configuration: seeded
        ))
        let seededObject = try XCTUnwrap(seededPayload.objectValue)
        let requiredKeys = [
            "generatedAt", "deviceId", "computedDeviceId",
            "latestHeartRateSample", "latestRrInterval", "latestEvent", "latestSleepSession",
            "latestWorkout",
            "storage", "coverage", "metricKeys",
        ]
        for key in requiredKeys {
            XCTAssertNotNil(seededObject[key], "missing freshness key \(key)")
        }
        XCTAssertEqual(seededObject["deviceId"], .string("my-whoop"))
        XCTAssertEqual(seededObject["latestHeartRateSample"]?.objectValue?["ts"], .int(102))
        XCTAssertEqual(seededObject["latestRrInterval"]?.objectValue?["ts"], .int(101))
        XCTAssertEqual(seededObject["latestEvent"], .null)
        XCTAssertEqual(seededObject["latestSleepSession"]?.objectValue?["ts"], .int(2000))
        XCTAssertEqual(seededObject["latestWorkout"]?.objectValue?["ts"], .int(4800))
        XCTAssertEqual(
            Set(seededObject["latestWorkout"]?.objectValue?.keys ?? []),
            Set(seededObject["latestHeartRateSample"]?.objectValue?.keys ?? [])
        )
        XCTAssertEqual(
            Set(seededObject["latestHeartRateSample"]?.objectValue?.keys ?? []),
            Set(["ts", "iso", "ageSeconds"])
        )
        XCTAssertNil(seededObject["analysisFingerprint"])
        XCTAssertNil(seededObject["score"])
        XCTAssertNil(seededObject["recoveryScore"])

        let events = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "data_freshness",
            arguments: [:],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withEvents().path)
        ))
        XCTAssertEqual(events.objectValue?["latestEvent"]?.objectValue?["ts"], .int(103))
        XCTAssertEqual(events.objectValue?["latestHeartRateSample"]?.objectValue?["ts"], .int(102))

        let rr = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "data_freshness",
            arguments: [:],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withRRIntervals().path)
        ))
        XCTAssertEqual(rr.objectValue?["latestRrInterval"]?.objectValue?["ts"], .int(103))

        let missingRR = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "data_freshness",
            arguments: [:],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withoutRRInterval().path)
        ))
        XCTAssertEqual(missingRR.objectValue?["latestRrInterval"], .null)
        XCTAssertEqual(missingRR.objectValue?["latestHeartRateSample"]?.objectValue?["ts"], .int(102))
        XCTAssertEqual(missingRR.objectValue?["latestSleepSession"]?.objectValue?["ts"], .int(2000))

        let missingSleep = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "data_freshness",
            arguments: [:],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withoutSleepSession().path)
        ))
        XCTAssertEqual(missingSleep.objectValue?["latestSleepSession"], .null)
        XCTAssertEqual(missingSleep.objectValue?["latestHeartRateSample"]?.objectValue?["ts"], .int(102))
        XCTAssertEqual(missingSleep.objectValue?["latestEvent"], .null)
        XCTAssertEqual(missingSleep.objectValue?["latestWorkout"]?.objectValue?["ts"], .int(4800))

        let missingWorkout = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "data_freshness",
            arguments: [:],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withoutWorkout().path)
        ))
        XCTAssertEqual(missingWorkout.objectValue?["latestWorkout"], .null)
        XCTAssertEqual(missingWorkout.objectValue?["latestHeartRateSample"]?.objectValue?["ts"], .int(102))
        XCTAssertEqual(missingWorkout.objectValue?["latestSleepSession"]?.objectValue?["ts"], .int(2000))
        XCTAssertEqual(missingWorkout.objectValue?["latestRrInterval"]?.objectValue?["ts"], .int(101))
        XCTAssertEqual(missingWorkout.objectValue?["latestEvent"], .null)
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
        assertUsageError(["spo2_series", "--from-ts", "100"])
        assertUsageError(["spo2_series", "--to-ts", "102"])
        assertUsageError(["spo2_series", "--unknown", "x"])
        assertUsageError(["skin_temp_series", "--from-ts", "100"])
        assertUsageError(["skin_temp_series", "--to-ts", "102"])
        assertUsageError(["skin_temp_series", "--unknown", "x"])
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
        assertUsageError(["spo2_series", "--kind", "ALPHA"])
        assertUsageError(["skin_temp_series", "--kind", "ALPHA"])
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
        assertUsageError(["health_snapshot", "--include-zones"])
        assertUsageError(["sleep_summary", "--include-zones"])
        assertUsageError(["hr_series", "--include-zones"])
        assertUsageError(["spo2_series", "--include-zones"])
        assertUsageError(["workout_summary", "--include-zones", "--include-zones"])
        assertUsageError(["health_snapshot", "--include-notes"])
        assertUsageError(["sleep_summary", "--include-notes"])
        assertUsageError(["hr_series", "--include-notes"])
        assertUsageError(["spo2_series", "--include-notes"])
        assertUsageError(["workout_summary", "--include-notes", "--include-notes"])
        assertUsageError(["health_snapshot", "--include-motion"])
        assertUsageError(["workout_summary", "--include-motion"])
        assertUsageError(["hr_series", "--include-motion"])
        assertUsageError(["spo2_series", "--include-motion"])
        assertUsageError(["sleep_stages", "--include-motion"])
        assertUsageError(["sleep_summary", "--include-motion", "--include-motion"])
        assertUsageError(["health_snapshot", "--include-sleep-state"])
        assertUsageError(["workout_summary", "--include-sleep-state"])
        assertUsageError(["hr_series", "--include-sleep-state"])
        assertUsageError(["spo2_series", "--include-sleep-state"])
        assertUsageError(["sleep_stages", "--include-sleep-state"])
        assertUsageError(["sleep_summary", "--include-sleep-state", "--include-sleep-state"])
        assertUsageError(["health_snapshot", "--include-start-adjusted"])
        assertUsageError(["workout_summary", "--include-start-adjusted"])
        assertUsageError(["hr_series", "--include-start-adjusted"])
        assertUsageError(["spo2_series", "--include-start-adjusted"])
        assertUsageError(["sleep_stages", "--include-start-adjusted"])
        assertUsageError(["sleep_summary", "--include-start-adjusted", "--include-start-adjusted"])
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

    func testSpo2SeriesParsesTheCompleteFlagContract() throws {
        let parsed = try NoopCLIQuery.parse(arguments: [
            "spo2_series",
            "--hours", "2",
            "--from-ts", "100",
            "--to-ts", "102",
            "--bucket-seconds", "1",
            "--limit", "50",
            "--device-id", "my-whoop",
            "--db-path", "/tmp/noop.sqlite",
        ])

        XCTAssertEqual(parsed.toolName, "spo2_series")
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

    func testSpo2SeriesBucketsMatchStoredRedIrAndSuffixLimit() throws {
        let url = try TemporaryDatabase.withSpo2Samples()
        let configuration = LocalAccessConfiguration(databasePath: url.path)
        let parsed = try NoopCLIQuery.parse(arguments: [
            "spo2_series", "--from-ts", "100", "--to-ts", "102", "--bucket-seconds", "1",
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
        XCTAssertEqual(points.compactMap { $0.objectValue?["ts"]?.intValue }, [100, 101, 102])
        XCTAssertEqual(points[0].objectValue?["red"]?.intValue, 97)
        XCTAssertEqual(points[1].objectValue?["red"]?.intValue, 98)
        XCTAssertEqual(points[2].objectValue?["red"]?.intValue, 96)
        XCTAssertEqual(points[0].objectValue?["ir"]?.intValue, 50)
        XCTAssertEqual(points[1].objectValue?["ir"]?.intValue, 51)
        XCTAssertEqual(points[2].objectValue?["ir"]?.intValue, 49)
        XCTAssertNotNil(points[0].objectValue?["iso"])
        XCTAssertNil(points[0].objectValue?["pct"])
        XCTAssertNil(object["score"])

        let grouped = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "spo2_series",
            arguments: [
                "from_ts": .int(100),
                "to_ts": .int(102),
                "bucket_seconds": .int(2),
            ],
            configuration: configuration
        ))
        XCTAssertEqual(grouped.objectValue?["returned"], .int(2))
        XCTAssertEqual(grouped.objectValue?["bucketSeconds"], .int(2))
        guard case .array(let groupedPoints) = grouped.objectValue?["points"] else {
            return XCTFail("Expected grouped points")
        }
        XCTAssertEqual(groupedPoints.compactMap { $0.objectValue?["ts"]?.intValue }, [100, 102])
        switch groupedPoints[0].objectValue?["red"] {
        case .double(let red):
            XCTAssertEqual(red, 97.5, accuracy: 0.01)
        default:
            XCTFail("Expected averaged red 97.5, got \(String(describing: groupedPoints[0].objectValue?["red"]))")
        }

        let truncated = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "spo2_series",
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

        let missingTable = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "spo2_series",
            arguments: [
                "from_ts": .int(100),
                "to_ts": .int(102),
                "bucket_seconds": .int(1),
            ],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.seeded().path)
        ))
        XCTAssertEqual(missingTable.objectValue?["returned"], .int(0))
        XCTAssertEqual(missingTable.objectValue?["truncated"], .bool(false))
        guard case .array(let emptyMissing) = missingTable.objectValue?["points"] else {
            return XCTFail("Expected empty points when spo2Sample table is missing")
        }
        XCTAssertEqual(emptyMissing.count, 0)
    }

    func testSpo2SeriesHoursZeroIsClampedByTheDispatcher() throws {
        let parsed = try NoopCLIQuery.parse(arguments: ["spo2_series", "--hours", "0"])
        XCTAssertEqual(parsed.arguments["hours"], .int(0))

        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: parsed.toolName,
            arguments: parsed.arguments,
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.seeded().path)
        ))
        XCTAssertEqual(payload.objectValue?["range"]?.objectValue?["hours"], .int(1))
    }



    func testSkinTempSeriesParsesTheCompleteFlagContract() throws {
        let parsed = try NoopCLIQuery.parse(arguments: [
            "skin_temp_series",
            "--hours", "2",
            "--from-ts", "100",
            "--to-ts", "102",
            "--bucket-seconds", "1",
            "--limit", "50",
            "--device-id", "my-whoop",
            "--db-path", "/tmp/noop.sqlite",
        ])

        XCTAssertEqual(parsed.toolName, "skin_temp_series")
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

    func testSkinTempSeriesBucketsMatchStoredRawAndSuffixLimit() throws {
        let url = try TemporaryDatabase.withSkinTempSamples()
        let configuration = LocalAccessConfiguration(databasePath: url.path)
        let parsed = try NoopCLIQuery.parse(arguments: [
            "skin_temp_series", "--from-ts", "100", "--to-ts", "102", "--bucket-seconds", "1",
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
        XCTAssertEqual(points.compactMap { $0.objectValue?["ts"]?.intValue }, [100, 101, 102])
        XCTAssertEqual(points[0].objectValue?["raw"]?.intValue, 3057)
        XCTAssertEqual(points[1].objectValue?["raw"]?.intValue, 3060)
        XCTAssertEqual(points[2].objectValue?["raw"]?.intValue, 3040)
        XCTAssertNotNil(points[0].objectValue?["iso"])
        XCTAssertNil(points[0].objectValue?["c"])
        XCTAssertNil(object["score"])

        let grouped = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "skin_temp_series",
            arguments: [
                "from_ts": .int(100),
                "to_ts": .int(102),
                "bucket_seconds": .int(2),
            ],
            configuration: configuration
        ))
        XCTAssertEqual(grouped.objectValue?["returned"], .int(2))
        XCTAssertEqual(grouped.objectValue?["bucketSeconds"], .int(2))
        guard case .array(let groupedPoints) = grouped.objectValue?["points"] else {
            return XCTFail("Expected grouped points")
        }
        XCTAssertEqual(groupedPoints.compactMap { $0.objectValue?["ts"]?.intValue }, [100, 102])
        switch groupedPoints[0].objectValue?["raw"] {
        case .double(let raw):
            XCTAssertEqual(raw, 3058.5, accuracy: 0.01)
        default:
            XCTFail("Expected averaged raw 3058.5, got \(String(describing: groupedPoints[0].objectValue?["raw"]))")
        }

        let truncated = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "skin_temp_series",
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

        let missingTable = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "skin_temp_series",
            arguments: [
                "from_ts": .int(100),
                "to_ts": .int(102),
                "bucket_seconds": .int(1),
            ],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.seeded().path)
        ))
        XCTAssertEqual(missingTable.objectValue?["returned"], .int(0))
        XCTAssertEqual(missingTable.objectValue?["truncated"], .bool(false))
        guard case .array(let emptyMissing) = missingTable.objectValue?["points"] else {
            return XCTFail("Expected empty points when skinTempSample table is missing")
        }
        XCTAssertEqual(emptyMissing.count, 0)
    }

    func testSkinTempSeriesHoursZeroIsClampedByTheDispatcher() throws {
        let parsed = try NoopCLIQuery.parse(arguments: ["skin_temp_series", "--hours", "0"])
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
            XCTAssertNil(object["motion"])
            XCTAssertNil(object["motionJSON"])
            XCTAssertNil(object["hasMotion"])
            XCTAssertNil(object["sleepState"])
            XCTAssertNil(object["sleepStateJSON"])
            XCTAssertNil(object["hasSleepState"])
            XCTAssertNil(object["startTsAdjusted"])
        }
    }


    func testSleepSummaryParsesIncludeMotionFlag() throws {
        let omitted = try NoopCLIQuery.parse(arguments: ["sleep_summary", "--days", "14"])
        XCTAssertEqual(omitted.toolName, "sleep_summary")
        XCTAssertEqual(omitted.arguments, ["days": .int(14)])

        let parsed = try NoopCLIQuery.parse(arguments: [
            "sleep_summary",
            "--days", "14",
            "--include-motion",
            "--db-path", "/tmp/noop.sqlite",
        ])
        XCTAssertEqual(parsed.toolName, "sleep_summary")
        XCTAssertEqual(parsed.arguments, [
            "days": .int(14),
            "include_motion": .bool(true),
        ])
        XCTAssertEqual(parsed.configuration.databasePath, "/tmp/noop.sqlite")
    }

    func testSleepSummaryDefaultOmitsMotionPayload() throws {
        let now = Int(Date().timeIntervalSince1970)
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "sleep_summary",
            arguments: ["days": .int(3)],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withSleepMotion(now: now).path)
        ))
        XCTAssertEqual(payload.objectValue?["count"], .int(4))
        guard case .array(let sessions) = payload.objectValue?["sessions"] else {
            return XCTFail("Expected sessions")
        }
        XCTAssertEqual(sessions.count, 4)
        for session in sessions {
            let object = try XCTUnwrap(session.objectValue)
            XCTAssertNotNil(object["hasStages"])
            XCTAssertNil(object["motion"])
            XCTAssertNil(object["motionJSON"])
            XCTAssertNil(object["hasMotion"])
            XCTAssertNil(object["stages"])
            XCTAssertNil(object["sleepState"])
            XCTAssertNil(object["sleepStateJSON"])
            XCTAssertNil(object["hasSleepState"])
            XCTAssertNil(object["startTsAdjusted"])
        }
    }

    func testSleepSummaryIncludeMotionAttachesBoundedPayload() throws {
        let now = Int(Date().timeIntervalSince1970)
        let arrayStart = now - 3_600
        let noneStart = now - 7_200
        let badStart = now - 10_800
        let hugeStart = now - 14_400
        let configuration = LocalAccessConfiguration(databasePath: try TemporaryDatabase.withSleepMotion(now: now).path)

        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "sleep_summary",
            arguments: [
                "days": .int(3),
                "include_motion": .bool(true),
            ],
            configuration: configuration
        ))
        guard case .array(let sessions) = payload.objectValue?["sessions"] else {
            return XCTFail("Expected sessions")
        }
        XCTAssertEqual(sessions.count, 4)

        let array = try XCTUnwrap(sessions.first { $0.objectValue?["startTs"] == .int(arrayStart) }?.objectValue)
        XCTAssertEqual(array["hasStages"], .bool(true))
        XCTAssertEqual(array["motion"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(array["motion"]?.objectValue?["payload"], .array([.double(0.1), .double(0.2), .double(0.3)]))
        XCTAssertNil(array["motionJSON"])

        let none = try XCTUnwrap(sessions.first { $0.objectValue?["startTs"] == .int(noneStart) }?.objectValue)
        XCTAssertEqual(none["motion"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(none["motion"]?.objectValue?["payload"], .null)

        let bad = try XCTUnwrap(sessions.first { $0.objectValue?["startTs"] == .int(badStart) }?.objectValue)
        XCTAssertEqual(bad["motion"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(bad["motion"]?.objectValue?["payload"], .string("not-json"))

        let huge = try XCTUnwrap(sessions.first { $0.objectValue?["startTs"] == .int(hugeStart) }?.objectValue)
        XCTAssertEqual(huge["motion"]?.objectValue?["truncated"], .bool(true))
        guard case .array(let values) = huge["motion"]?.objectValue?["payload"] else {
            return XCTFail("Expected truncated array payload")
        }
        XCTAssertEqual(values.count, 32)
        XCTAssertNil(huge["motionJSON"])

        let mcp = try XCTUnwrap(try NoopMCPServer(configuration: configuration).handle(RPCRequest(
            id: .int(1),
            method: "tools/call",
            params: .object([
                "name": .string("sleep_summary"),
                "arguments": .object([
                    "days": .int(3),
                    "include_motion": .bool(true),
                ]),
            ])
        )))
        XCTAssertEqual(
            mcp.objectValue?["result"]?.objectValue?["structuredContent"],
            payload
        )
    }

    func testSleepSummaryIncludeMotionWhenMotionColumnIsAbsent() throws {
        let url = try TemporaryDatabase.withSleepStages()
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "sleep_summary",
            arguments: [
                "days": .int(3),
                "include_motion": .bool(true),
            ],
            configuration: LocalAccessConfiguration(databasePath: url.path)
        ))
        guard case .array(let sessions) = payload.objectValue?["sessions"] else {
            return XCTFail("Expected sessions")
        }
        XCTAssertFalse(sessions.isEmpty)
        for session in sessions {
            let object = try XCTUnwrap(session.objectValue)
            XCTAssertNotNil(object["hasStages"])
            XCTAssertEqual(object["motion"]?.objectValue?["truncated"], .bool(false))
            XCTAssertEqual(object["motion"]?.objectValue?["payload"], .null)
            XCTAssertNil(object["motionJSON"])
            XCTAssertNil(object["sleepState"])
            XCTAssertNil(object["sleepStateJSON"])
        }
    }

    func testSleepSummaryParsesIncludeSleepStateFlag() throws {
        let omitted = try NoopCLIQuery.parse(arguments: ["sleep_summary", "--days", "14"])
        XCTAssertEqual(omitted.toolName, "sleep_summary")
        XCTAssertEqual(omitted.arguments, ["days": .int(14)])

        let parsed = try NoopCLIQuery.parse(arguments: [
            "sleep_summary",
            "--days", "14",
            "--include-sleep-state",
            "--db-path", "/tmp/noop.sqlite",
        ])
        XCTAssertEqual(parsed.toolName, "sleep_summary")
        XCTAssertEqual(parsed.arguments, [
            "days": .int(14),
            "include_sleep_state": .bool(true),
        ])
        XCTAssertEqual(parsed.configuration.databasePath, "/tmp/noop.sqlite")

        let both = try NoopCLIQuery.parse(arguments: [
            "sleep_summary",
            "--include-motion",
            "--include-sleep-state",
        ])
        XCTAssertEqual(both.arguments, [
            "include_motion": .bool(true),
            "include_sleep_state": .bool(true),
        ])
    }

    func testSleepSummaryDefaultOmitsSleepStatePayload() throws {
        let now = Int(Date().timeIntervalSince1970)
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "sleep_summary",
            arguments: ["days": .int(3)],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withSleepState(now: now).path)
        ))
        XCTAssertEqual(payload.objectValue?["count"], .int(4))
        guard case .array(let sessions) = payload.objectValue?["sessions"] else {
            return XCTFail("Expected sessions")
        }
        XCTAssertEqual(sessions.count, 4)
        for session in sessions {
            let object = try XCTUnwrap(session.objectValue)
            XCTAssertNotNil(object["hasStages"])
            XCTAssertNil(object["sleepState"])
            XCTAssertNil(object["sleepStateJSON"])
            XCTAssertNil(object["hasSleepState"])
            XCTAssertNil(object["stages"])
            XCTAssertNil(object["motion"])
            XCTAssertNil(object["startTsAdjusted"])
        }
    }

    func testSleepSummaryIncludeSleepStateAttachesBoundedPayload() throws {
        let now = Int(Date().timeIntervalSince1970)
        let arrayStart = now - 3_600
        let noneStart = now - 7_200
        let badStart = now - 10_800
        let hugeStart = now - 14_400
        let configuration = LocalAccessConfiguration(databasePath: try TemporaryDatabase.withSleepState(now: now).path)

        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "sleep_summary",
            arguments: [
                "days": .int(3),
                "include_sleep_state": .bool(true),
            ],
            configuration: configuration
        ))
        guard case .array(let sessions) = payload.objectValue?["sessions"] else {
            return XCTFail("Expected sessions")
        }
        XCTAssertEqual(sessions.count, 4)

        let array = try XCTUnwrap(sessions.first { $0.objectValue?["startTs"] == .int(arrayStart) }?.objectValue)
        XCTAssertEqual(array["hasStages"], .bool(true))
        XCTAssertEqual(array["sleepState"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(array["sleepState"]?.objectValue?["payload"], .array([.double(0.1), .double(0.2), .double(0.3)]))
        XCTAssertNil(array["sleepStateJSON"])
        XCTAssertNil(array["motion"])

        let none = try XCTUnwrap(sessions.first { $0.objectValue?["startTs"] == .int(noneStart) }?.objectValue)
        XCTAssertEqual(none["sleepState"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(none["sleepState"]?.objectValue?["payload"], .null)

        let bad = try XCTUnwrap(sessions.first { $0.objectValue?["startTs"] == .int(badStart) }?.objectValue)
        XCTAssertEqual(bad["sleepState"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(bad["sleepState"]?.objectValue?["payload"], .string("not-json"))

        let huge = try XCTUnwrap(sessions.first { $0.objectValue?["startTs"] == .int(hugeStart) }?.objectValue)
        XCTAssertEqual(huge["sleepState"]?.objectValue?["truncated"], .bool(true))
        guard case .array(let values) = huge["sleepState"]?.objectValue?["payload"] else {
            return XCTFail("Expected truncated array payload")
        }
        XCTAssertEqual(values.count, 32)
        XCTAssertNil(huge["sleepStateJSON"])

        let mcp = try XCTUnwrap(try NoopMCPServer(configuration: configuration).handle(RPCRequest(
            id: .int(1),
            method: "tools/call",
            params: .object([
                "name": .string("sleep_summary"),
                "arguments": .object([
                    "days": .int(3),
                    "include_sleep_state": .bool(true),
                ]),
            ])
        )))
        XCTAssertEqual(
            mcp.objectValue?["result"]?.objectValue?["structuredContent"],
            payload
        )
    }

    func testSleepSummaryIncludeSleepStateWhenSleepStateColumnIsAbsent() throws {
        let url = try TemporaryDatabase.withSleepStages()
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "sleep_summary",
            arguments: [
                "days": .int(3),
                "include_sleep_state": .bool(true),
            ],
            configuration: LocalAccessConfiguration(databasePath: url.path)
        ))
        guard case .array(let sessions) = payload.objectValue?["sessions"] else {
            return XCTFail("Expected sessions")
        }
        XCTAssertFalse(sessions.isEmpty)
        for session in sessions {
            let object = try XCTUnwrap(session.objectValue)
            XCTAssertNotNil(object["hasStages"])
            XCTAssertEqual(object["sleepState"]?.objectValue?["truncated"], .bool(false))
            XCTAssertEqual(object["sleepState"]?.objectValue?["payload"], .null)
            XCTAssertNil(object["sleepStateJSON"])
        }
    }


    func testSleepSummaryParsesIncludeStartAdjustedFlag() throws {
        let omitted = try NoopCLIQuery.parse(arguments: ["sleep_summary", "--days", "14"])
        XCTAssertEqual(omitted.toolName, "sleep_summary")
        XCTAssertEqual(omitted.arguments, ["days": .int(14)])

        let parsed = try NoopCLIQuery.parse(arguments: [
            "sleep_summary",
            "--days", "14",
            "--include-start-adjusted",
            "--db-path", "/tmp/noop.sqlite",
        ])
        XCTAssertEqual(parsed.toolName, "sleep_summary")
        XCTAssertEqual(parsed.arguments, [
            "days": .int(14),
            "include_start_adjusted": .bool(true),
        ])
        XCTAssertEqual(parsed.configuration.databasePath, "/tmp/noop.sqlite")

        let both = try NoopCLIQuery.parse(arguments: [
            "sleep_summary",
            "--include-motion",
            "--include-sleep-state",
            "--include-start-adjusted",
        ])
        XCTAssertEqual(both.arguments, [
            "include_motion": .bool(true),
            "include_sleep_state": .bool(true),
            "include_start_adjusted": .bool(true),
        ])
    }

    func testSleepSummaryDefaultOmitsStartTsAdjusted() throws {
        let now = Int(Date().timeIntervalSince1970)
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "sleep_summary",
            arguments: ["days": .int(3)],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withStartAdjusted(now: now).path)
        ))
        XCTAssertEqual(payload.objectValue?["count"], .int(2))
        guard case .array(let sessions) = payload.objectValue?["sessions"] else {
            return XCTFail("Expected sessions")
        }
        XCTAssertEqual(sessions.count, 2)
        for session in sessions {
            let object = try XCTUnwrap(session.objectValue)
            XCTAssertNotNil(object["hasStages"])
            XCTAssertNil(object["startTsAdjusted"])
            XCTAssertNil(object["motion"])
            XCTAssertNil(object["sleepState"])
            XCTAssertNil(object["stages"])
        }
    }

    func testSleepSummaryIncludeStartAdjustedExposesValueWhenPresent() throws {
        let now = Int(Date().timeIntervalSince1970)
        let adjustedStart = now - 3_600
        let noneStart = now - 7_200
        let configuration = LocalAccessConfiguration(databasePath: try TemporaryDatabase.withStartAdjusted(now: now).path)

        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "sleep_summary",
            arguments: [
                "days": .int(3),
                "include_start_adjusted": .bool(true),
            ],
            configuration: configuration
        ))
        guard case .array(let sessions) = payload.objectValue?["sessions"] else {
            return XCTFail("Expected sessions")
        }
        XCTAssertEqual(sessions.count, 2)

        let adjusted = try XCTUnwrap(sessions.first { $0.objectValue?["startTs"] == .int(adjustedStart) }?.objectValue)
        XCTAssertEqual(adjusted["hasStages"], .bool(true))
        XCTAssertEqual(adjusted["startTs"], .int(adjustedStart))
        XCTAssertEqual(adjusted["startTsAdjusted"], .int(now - 3_300))
        XCTAssertNil(adjusted["motion"])
        XCTAssertNil(adjusted["sleepState"])

        let none = try XCTUnwrap(sessions.first { $0.objectValue?["startTs"] == .int(noneStart) }?.objectValue)
        XCTAssertNil(none["startTsAdjusted"])

        let mcp = try XCTUnwrap(try NoopMCPServer(configuration: configuration).handle(RPCRequest(
            id: .int(1),
            method: "tools/call",
            params: .object([
                "name": .string("sleep_summary"),
                "arguments": .object([
                    "days": .int(3),
                    "include_start_adjusted": .bool(true),
                ]),
            ])
        )))
        XCTAssertEqual(
            mcp.objectValue?["result"]?.objectValue?["structuredContent"],
            payload
        )
    }

    func testSleepSummaryIncludeStartAdjustedWhenColumnIsAbsent() throws {
        let url = try TemporaryDatabase.withSleepStages()
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "sleep_summary",
            arguments: [
                "days": .int(3),
                "include_start_adjusted": .bool(true),
            ],
            configuration: LocalAccessConfiguration(databasePath: url.path)
        ))
        guard case .array(let sessions) = payload.objectValue?["sessions"] else {
            return XCTFail("Expected sessions")
        }
        XCTAssertFalse(sessions.isEmpty)
        for session in sessions {
            let object = try XCTUnwrap(session.objectValue)
            XCTAssertNotNil(object["hasStages"])
            XCTAssertNil(object["startTsAdjusted"])
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
            XCTAssertNotNil(object["hasNotes"])
            XCTAssertNil(object["notes"])
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
        XCTAssertEqual(
            mcp.objectValue?["result"]?.objectValue?["structuredContent"],
            payload
        )
    }

    func testWorkoutSummaryParsesIncludeNotesFlag() throws {
        let omitted = try NoopCLIQuery.parse(arguments: ["workout_summary", "--days", "14"])
        XCTAssertEqual(omitted.toolName, "workout_summary")
        XCTAssertEqual(omitted.arguments, ["days": .int(14)])

        let parsed = try NoopCLIQuery.parse(arguments: [
            "workout_summary",
            "--days", "14",
            "--include-notes",
            "--include-zones",
            "--db-path", "/tmp/noop.sqlite",
        ])
        XCTAssertEqual(parsed.toolName, "workout_summary")
        XCTAssertEqual(parsed.arguments, [
            "days": .int(14),
            "include_notes": .bool(true),
            "include_zones": .bool(true),
        ])
        XCTAssertEqual(parsed.configuration.databasePath, "/tmp/noop.sqlite")
    }

    func testWorkoutSummaryDefaultOmitsNotesText() throws {
        let now = Int(Date().timeIntervalSince1970)
        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "workout_summary",
            arguments: ["days": .int(3)],
            configuration: LocalAccessConfiguration(databasePath: try TemporaryDatabase.withWorkoutNotes(now: now).path)
        ))
        XCTAssertEqual(payload.objectValue?["count"], .int(4))
        guard case .array(let workouts) = payload.objectValue?["workouts"] else {
            return XCTFail("Expected workouts")
        }
        XCTAssertEqual(workouts.count, 4)
        var sawNotes = false
        var sawNoNotes = false
        for workout in workouts {
            let object = try XCTUnwrap(workout.objectValue)
            XCTAssertNotNil(object["hasNotes"])
            XCTAssertNil(object["notes"])
            if object["hasNotes"] == .bool(true) { sawNotes = true }
            if object["hasNotes"] == .bool(false) { sawNoNotes = true }
        }
        XCTAssertTrue(sawNotes)
        XCTAssertTrue(sawNoNotes)
    }

    func testWorkoutSummaryIncludeNotesAttachesBoundedPayload() throws {
        let now = Int(Date().timeIntervalSince1970)
        let shortStart = now - 3_600
        let noneStart = now - 7_200
        let hugeStart = now - 10_800
        let emptyStart = now - 14_400
        let configuration = LocalAccessConfiguration(databasePath: try TemporaryDatabase.withWorkoutNotes(now: now).path)

        let payload = try NoopCLIQuery.dispatch(NoopCLIQueryRequest(
            toolName: "workout_summary",
            arguments: [
                "days": .int(3),
                "include_notes": .bool(true),
            ],
            configuration: configuration
        ))
        guard case .array(let workouts) = payload.objectValue?["workouts"] else {
            return XCTFail("Expected workouts")
        }
        XCTAssertEqual(workouts.count, 4)

        let short = try XCTUnwrap(workouts.first { $0.objectValue?["startTs"] == .int(shortStart) }?.objectValue)
        XCTAssertEqual(short["hasNotes"], .bool(true))
        XCTAssertEqual(short["notes"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(short["notes"]?.objectValue?["payload"], .string("easy tempo"))
        XCTAssertNil(short["zones"])

        let none = try XCTUnwrap(workouts.first { $0.objectValue?["startTs"] == .int(noneStart) }?.objectValue)
        XCTAssertEqual(none["hasNotes"], .bool(false))
        XCTAssertEqual(none["notes"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(none["notes"]?.objectValue?["payload"], .null)

        let huge = try XCTUnwrap(workouts.first { $0.objectValue?["startTs"] == .int(hugeStart) }?.objectValue)
        XCTAssertEqual(huge["hasNotes"], .bool(true))
        XCTAssertEqual(huge["notes"]?.objectValue?["truncated"], .bool(true))
        guard case .string(let noteText) = huge["notes"]?.objectValue?["payload"] else {
            return XCTFail("Expected truncated notes payload")
        }
        XCTAssertEqual(noteText.count, 2048)
        XCTAssertTrue(noteText.allSatisfy { $0 == "x" })

        let empty = try XCTUnwrap(workouts.first { $0.objectValue?["startTs"] == .int(emptyStart) }?.objectValue)
        XCTAssertEqual(empty["hasNotes"], .bool(true))
        XCTAssertEqual(empty["notes"]?.objectValue?["truncated"], .bool(false))
        XCTAssertEqual(empty["notes"]?.objectValue?["payload"], .string(""))

        let mcp = try XCTUnwrap(try NoopMCPServer(configuration: configuration).handle(RPCRequest(
            id: .int(1),
            method: "tools/call",
            params: .object([
                "name": .string("workout_summary"),
                "arguments": .object([
                    "days": .int(3),
                    "include_notes": .bool(true),
                ]),
            ])
        )))
        XCTAssertEqual(
            mcp.objectValue?["result"]?.objectValue?["structuredContent"],
            payload
        )
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
