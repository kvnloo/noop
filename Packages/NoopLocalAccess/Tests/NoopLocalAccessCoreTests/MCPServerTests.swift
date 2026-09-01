import XCTest
@testable import NoopLocalAccessCore

final class MCPServerTests: XCTestCase {
    func testInitializeIncludesReadOnlyInstructionsForCodex() throws {
        let server = NoopMCPServer(configuration: LocalAccessConfiguration(databasePath: "/unused"))
        let response = try XCTUnwrap(try server.handle(RPCRequest(id: .int(1), method: "initialize", params: nil)))
        let result = try XCTUnwrap(response.objectValue?["result"]?.objectValue)

        XCTAssertEqual(result["protocolVersion"], .string(noopLocalAccessProtocolVersion))
        XCTAssertTrue(result["instructions"]?.stringValue?.contains("read-only") == true)
        XCTAssertTrue(result["instructions"]?.stringValue?.contains("do not diagnose") == true)
    }

    func testToolsAreAnnotatedReadOnly() throws {
        let tools = try XCTUnwrap(toolsList().objectValue?["tools"])
        guard case .array(let values) = tools else {
            return XCTFail("Expected tools array")
        }

        XCTAssertFalse(values.isEmpty)
        let names = values.compactMap { $0.objectValue?["name"]?.stringValue }
        XCTAssertTrue(names.contains("hr_series"))
        XCTAssertTrue(names.contains("spo2_series"))
        XCTAssertTrue(names.contains("skin_temp_series"))
        XCTAssertTrue(names.contains("resp_series"))
        XCTAssertTrue(names.contains("step_series"))
        XCTAssertTrue(names.contains("gravity_series"))
        XCTAssertTrue(names.contains("battery_series"))
        XCTAssertTrue(names.contains("sleep_state_series"))
        XCTAssertTrue(names.contains("sleep_stages"))
        XCTAssertTrue(names.contains("event_series"))
        XCTAssertTrue(names.contains("event_kinds"))
        XCTAssertTrue(names.contains("rr_series"))
        XCTAssertTrue(names.contains("workout_summary"))
        XCTAssertTrue(names.contains("sleep_summary"))
        let sleep = try XCTUnwrap(values.first { $0.objectValue?["name"] == .string("sleep_summary") }?.objectValue)
        XCTAssertNotNil(sleep["inputSchema"]?.objectValue?["properties"]?.objectValue?["include_motion"])
        XCTAssertEqual(
            sleep["inputSchema"]?.objectValue?["properties"]?.objectValue?["include_motion"]?.objectValue?["type"],
            .string("boolean")
        )
        XCTAssertNotNil(sleep["inputSchema"]?.objectValue?["properties"]?.objectValue?["include_sleep_state"])
        XCTAssertEqual(
            sleep["inputSchema"]?.objectValue?["properties"]?.objectValue?["include_sleep_state"]?.objectValue?["type"],
            .string("boolean")
        )
        XCTAssertNotNil(sleep["inputSchema"]?.objectValue?["properties"]?.objectValue?["include_start_adjusted"])
        XCTAssertEqual(
            sleep["inputSchema"]?.objectValue?["properties"]?.objectValue?["include_start_adjusted"]?.objectValue?["type"],
            .string("boolean")
        )
        let workout = try XCTUnwrap(values.first { $0.objectValue?["name"] == .string("workout_summary") }?.objectValue)
        XCTAssertNotNil(workout["inputSchema"]?.objectValue?["properties"]?.objectValue?["include_zones"])
        XCTAssertEqual(
            workout["inputSchema"]?.objectValue?["properties"]?.objectValue?["include_zones"]?.objectValue?["type"],
            .string("boolean")
        )
        XCTAssertNotNil(workout["inputSchema"]?.objectValue?["properties"]?.objectValue?["include_notes"])
        XCTAssertEqual(
            workout["inputSchema"]?.objectValue?["properties"]?.objectValue?["include_notes"]?.objectValue?["type"],
            .string("boolean")
        )
        for tool in values {
            let annotations = try XCTUnwrap(tool.objectValue?["annotations"]?.objectValue)
            XCTAssertEqual(annotations["readOnlyHint"], .bool(true))
            XCTAssertEqual(annotations["openWorldHint"], .bool(false))
        }
    }

    func testMetricSeriesUsesComputedFallbackForMissingImportedDay() throws {
        let url = try TemporaryDatabase.seeded()
        let server = NoopMCPServer(configuration: LocalAccessConfiguration(databasePath: url.path))
        let response = try XCTUnwrap(try server.handle(RPCRequest(
            id: .int(2),
            method: "tools/call",
            params: .object([
                "name": .string("metric_series"),
                "arguments": .object([
                    "key": .string("hrv"),
                    "from_day": .string("2026-06-10"),
                    "to_day": .string("2026-06-11"),
                ]),
            ])
        )))

        let structured = try XCTUnwrap(response.objectValue?["result"]?.objectValue?["structuredContent"]?.objectValue)
        XCTAssertEqual(structured["returned"], .int(2))
        guard case .array(let points) = structured["points"] else {
            return XCTFail("Expected points array")
        }
        XCTAssertEqual(points.compactMap { $0.objectValue?["source"]?.stringValue }, ["my-whoop", "my-whoop-noop"])
    }

    func testResourcesListIncludesToolsCatalog() throws {
        let resources = try XCTUnwrap(resourcesList().objectValue?["resources"])
        guard case .array(let values) = resources else {
            return XCTFail("Expected resources array")
        }
        let uris = values.compactMap { $0.objectValue?["uri"]?.stringValue }
        XCTAssertTrue(uris.contains("noop://tools/catalog"))
        let catalog = try XCTUnwrap(values.first { $0.objectValue?["uri"] == .string("noop://tools/catalog") }?.objectValue)
        XCTAssertEqual(catalog["name"], .string("tools_catalog"))
        XCTAssertEqual(catalog["mimeType"], .string("application/json"))
    }

    func testToolsCatalogResourcePayloadIsDispatcherToolNamesJSON() throws {
        let dispatcher = NoopToolDispatcher(configuration: LocalAccessConfiguration(databasePath: "/unused"))
        let payload = try dispatcher.resourcePayload(uri: "noop://tools/catalog")
        XCTAssertEqual(payload, .array(NoopToolDispatcher.toolNames.map { .string($0) }))
        XCTAssertTrue(NoopToolDispatcher.toolNames.contains("sleep_state_series"))
        XCTAssertTrue(NoopToolDispatcher.toolNames.contains("event_kinds"))
        XCTAssertFalse(NoopToolDispatcher.toolNames.contains("nzt"))
        XCTAssertFalse(NoopToolDispatcher.toolNames.contains("scores"))
    }

    func testResourcesReadToolsCatalogWiresResourcePayload() throws {
        let server = NoopMCPServer(configuration: LocalAccessConfiguration(databasePath: "/unused"))
        let response = try XCTUnwrap(try server.handle(RPCRequest(
            id: .int(3),
            method: "resources/read",
            params: .object(["uri": .string("noop://tools/catalog")])
        )))
        let contents = try XCTUnwrap(response.objectValue?["result"]?.objectValue?["contents"])
        guard case .array(let values) = contents, let first = values.first?.objectValue else {
            return XCTFail("Expected contents array")
        }
        XCTAssertEqual(first["uri"], .string("noop://tools/catalog"))
        XCTAssertEqual(first["mimeType"], .string("application/json"))
        let expected = JSONValue.array(NoopToolDispatcher.toolNames.map { .string($0) })
        XCTAssertEqual(first["text"], .string(prettyJSON(expected)))
    }
}
