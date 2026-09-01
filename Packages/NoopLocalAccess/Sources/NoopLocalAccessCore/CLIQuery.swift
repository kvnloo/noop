import Foundation

public struct NoopCLIQueryRequest: Equatable, Sendable {
    public let toolName: String
    public let arguments: [String: JSONValue]
    public let configuration: LocalAccessConfiguration

    public init(
        toolName: String,
        arguments: [String: JSONValue],
        configuration: LocalAccessConfiguration = .environment()
    ) {
        self.toolName = toolName
        self.arguments = arguments
        self.configuration = configuration
    }
}

public struct NoopCLIResourceRequest: Equatable, Sendable {
    public let uri: String
    public let configuration: LocalAccessConfiguration

    public init(uri: String, configuration: LocalAccessConfiguration = .environment()) {
        self.uri = uri
        self.configuration = configuration
    }
}

public enum NoopCLIQueryError: Error, CustomStringConvertible, Equatable {
    case usage(String)

    public var description: String {
        switch self {
        case .usage(let message): return message
        }
    }

    public var exitCode: Int32 { 64 }
}

public enum NoopCLIQuery {
    public static func parse(arguments: [String]) throws -> NoopCLIQueryRequest {
        guard let toolName = arguments.first, !toolName.hasPrefix("-") else {
            throw NoopCLIQueryError.usage("query requires one tool name")
        }
        guard NoopToolDispatcher.toolNames.contains(toolName) else {
            throw NoopCLIQueryError.usage("unknown query tool")
        }

        var toolArguments: [String: JSONValue] = [:]
        var configuration = LocalAccessConfiguration.environment()
        var seenFlags = Set<String>()
        var index = 1

        while index < arguments.count {
            let flag = arguments[index]
            guard flag.hasPrefix("--") else {
                throw NoopCLIQueryError.usage("query does not accept additional positional arguments")
            }
            guard seenFlags.insert(flag).inserted else {
                throw NoopCLIQueryError.usage("duplicate query flag: \(flag)")
            }
            index += 1

            switch flag {
            case "--db-path":
                configuration.databasePath = try requiredValue(flag, arguments: arguments, index: &index)
            case "--days":
                guard toolName != "data_freshness" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["days"] = try integerValue(flag, arguments: arguments, index: &index)
            case "--key":
                guard toolName == "metric_series" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["key"] = .string(try requiredValue(flag, arguments: arguments, index: &index))
            case "--kind":
                guard toolName == "event_series" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["kind"] = .string(try requiredValue(flag, arguments: arguments, index: &index))
            case "--source":
                guard toolName == "metric_series" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["source"] = .string(try requiredValue(flag, arguments: arguments, index: &index))
            case "--from-day":
                guard toolName == "metric_series" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["from_day"] = .string(try requiredValue(flag, arguments: arguments, index: &index))
            case "--to-day":
                guard toolName == "metric_series" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["to_day"] = .string(try requiredValue(flag, arguments: arguments, index: &index))
            case "--limit":
                guard toolName == "metric_series" || toolName == "hr_series" || toolName == "spo2_series" || toolName == "skin_temp_series" || toolName == "resp_series" || toolName == "step_series" || toolName == "gravity_series" || toolName == "battery_series" || toolName == "sleep_state_series" || toolName == "sleep_stages" || toolName == "event_series" || toolName == "event_kinds" || toolName == "rr_series" else {
                    throw unsupported(flag, toolName: toolName)
                }
                toolArguments["limit"] = try integerValue(flag, arguments: arguments, index: &index)
            case "--max-points":
                guard toolName == "sleep_stages" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["max_points"] = try integerValue(flag, arguments: arguments, index: &index)
            case "--hours":
                guard toolName == "hr_series" || toolName == "spo2_series" || toolName == "skin_temp_series" || toolName == "resp_series" || toolName == "step_series" || toolName == "gravity_series" || toolName == "battery_series" || toolName == "sleep_state_series" || toolName == "event_series" || toolName == "rr_series" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["hours"] = try integerValue(flag, arguments: arguments, index: &index)
            case "--from-ts":
                guard toolName == "hr_series" || toolName == "spo2_series" || toolName == "skin_temp_series" || toolName == "resp_series" || toolName == "step_series" || toolName == "gravity_series" || toolName == "battery_series" || toolName == "sleep_state_series" || toolName == "event_series" || toolName == "rr_series" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["from_ts"] = try integerValue(flag, arguments: arguments, index: &index)
            case "--to-ts":
                guard toolName == "hr_series" || toolName == "spo2_series" || toolName == "skin_temp_series" || toolName == "resp_series" || toolName == "step_series" || toolName == "gravity_series" || toolName == "battery_series" || toolName == "sleep_state_series" || toolName == "event_series" || toolName == "rr_series" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["to_ts"] = try integerValue(flag, arguments: arguments, index: &index)
            case "--bucket-seconds":
                guard toolName == "hr_series" || toolName == "spo2_series" || toolName == "skin_temp_series" || toolName == "resp_series" || toolName == "step_series" || toolName == "gravity_series" || toolName == "battery_series" || toolName == "sleep_state_series" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["bucket_seconds"] = try integerValue(flag, arguments: arguments, index: &index)
            case "--device-id":
                guard toolName == "hr_series" || toolName == "spo2_series" || toolName == "skin_temp_series" || toolName == "resp_series" || toolName == "step_series" || toolName == "gravity_series" || toolName == "battery_series" || toolName == "sleep_state_series" || toolName == "event_series" || toolName == "event_kinds" || toolName == "rr_series" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["device_id"] = .string(try requiredValue(flag, arguments: arguments, index: &index))
            case "--include-zones":
                guard toolName == "workout_summary" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["include_zones"] = .bool(true)
            case "--include-notes":
                guard toolName == "workout_summary" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["include_notes"] = .bool(true)
            case "--include-motion":
                guard toolName == "sleep_summary" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["include_motion"] = .bool(true)
            case "--include-sleep-state":
                guard toolName == "sleep_summary" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["include_sleep_state"] = .bool(true)
            case "--include-start-adjusted":
                guard toolName == "sleep_summary" else { throw unsupported(flag, toolName: toolName) }
                toolArguments["include_start_adjusted"] = .bool(true)
            default:
                throw NoopCLIQueryError.usage("unknown query flag")
            }
        }

        if toolName == "metric_series", toolArguments["key"] == nil {
            throw NoopCLIQueryError.usage("metric_series requires --key")
        }
        if toolName == "event_series", toolArguments["kind"] == nil {
            throw NoopCLIQueryError.usage("event_series requires --kind")
        }
        if toolName == "hr_series" {
            let hasFrom = toolArguments["from_ts"] != nil
            let hasTo = toolArguments["to_ts"] != nil
            if hasFrom != hasTo {
                throw NoopCLIQueryError.usage("hr_series requires both --from-ts and --to-ts")
            }
        }
        if toolName == "spo2_series" {
            let hasFrom = toolArguments["from_ts"] != nil
            let hasTo = toolArguments["to_ts"] != nil
            if hasFrom != hasTo {
                throw NoopCLIQueryError.usage("spo2_series requires both --from-ts and --to-ts")
            }
        }
        if toolName == "skin_temp_series" {
            let hasFrom = toolArguments["from_ts"] != nil
            let hasTo = toolArguments["to_ts"] != nil
            if hasFrom != hasTo {
                throw NoopCLIQueryError.usage("skin_temp_series requires both --from-ts and --to-ts")
            }
        }
        if toolName == "resp_series" {
            let hasFrom = toolArguments["from_ts"] != nil
            let hasTo = toolArguments["to_ts"] != nil
            if hasFrom != hasTo {
                throw NoopCLIQueryError.usage("resp_series requires both --from-ts and --to-ts")
            }
        }
        if toolName == "step_series" {
            let hasFrom = toolArguments["from_ts"] != nil
            let hasTo = toolArguments["to_ts"] != nil
            if hasFrom != hasTo {
                throw NoopCLIQueryError.usage("step_series requires both --from-ts and --to-ts")
            }
        }
        if toolName == "gravity_series" {
            let hasFrom = toolArguments["from_ts"] != nil
            let hasTo = toolArguments["to_ts"] != nil
            if hasFrom != hasTo {
                throw NoopCLIQueryError.usage("gravity_series requires both --from-ts and --to-ts")
            }
        }

        if toolName == "battery_series" {
            let hasFrom = toolArguments["from_ts"] != nil
            let hasTo = toolArguments["to_ts"] != nil
            if hasFrom != hasTo {
                throw NoopCLIQueryError.usage("battery_series requires both --from-ts and --to-ts")
            }
        }
        if toolName == "sleep_state_series" {
            let hasFrom = toolArguments["from_ts"] != nil
            let hasTo = toolArguments["to_ts"] != nil
            if hasFrom != hasTo {
                throw NoopCLIQueryError.usage("sleep_state_series requires both --from-ts and --to-ts")
            }
        }
        if toolName == "event_series" {
            let hasFrom = toolArguments["from_ts"] != nil
            let hasTo = toolArguments["to_ts"] != nil
            if hasFrom != hasTo {
                throw NoopCLIQueryError.usage("event_series requires both --from-ts and --to-ts")
            }
        }
        if toolName == "rr_series" {
            let hasFrom = toolArguments["from_ts"] != nil
            let hasTo = toolArguments["to_ts"] != nil
            if hasFrom != hasTo {
                throw NoopCLIQueryError.usage("rr_series requires both --from-ts and --to-ts")
            }
        }

        return NoopCLIQueryRequest(toolName: toolName, arguments: toolArguments, configuration: configuration)
    }

    public static func dispatch(_ request: NoopCLIQueryRequest) throws -> JSONValue {
        try NoopToolDispatcher(configuration: request.configuration)
            .dispatch(name: request.toolName, arguments: request.arguments)
    }

    public static func listToolsPayload() -> JSONValue {
        .array(NoopToolDispatcher.toolNames.map { .string($0) })
    }

    public static func wantsListTools(arguments: [String]) throws -> Bool {
        guard arguments.first == "--list-tools" else { return false }
        guard arguments == ["--list-tools"] else {
            throw NoopCLIQueryError.usage("query --list-tools does not accept additional arguments")
        }
        return true
    }

    public static func parseToolsCommand(arguments: [String]) throws {
        guard arguments.isEmpty else {
            throw NoopCLIQueryError.usage("tools does not accept additional arguments")
        }
    }

    public static let resourceURIs: [String] = [
        "noop://health/snapshot",
        "noop://data/freshness",
        "noop://metrics/catalog",
        "noop://sources",
        "noop://tools/catalog",
    ]

    public static func listResourcesPayload() -> JSONValue {
        .array(resourceURIs.map { .string($0) })
    }

    public static func wantsListResources(arguments: [String]) throws -> Bool {
        guard arguments.first == "--list" else { return false }
        guard arguments == ["--list"] else {
            throw NoopCLIQueryError.usage("resource --list does not accept additional arguments")
        }
        return true
    }

    public static func parseResourceCommand(arguments: [String]) throws -> NoopCLIResourceRequest {
        guard let raw = arguments.first, !raw.hasPrefix("-") else {
            throw NoopCLIQueryError.usage("resource requires one uri")
        }
        guard let uri = canonicalizeResourceURI(raw) else {
            throw NoopCLIQueryError.usage("unknown resource uri")
        }

        var configuration = LocalAccessConfiguration.environment()
        var seenFlags = Set<String>()
        var index = 1
        while index < arguments.count {
            let flag = arguments[index]
            guard flag.hasPrefix("--") else {
                throw NoopCLIQueryError.usage("resource does not accept additional positional arguments")
            }
            guard seenFlags.insert(flag).inserted else {
                throw NoopCLIQueryError.usage("duplicate resource flag: \(flag)")
            }
            index += 1
            switch flag {
            case "--db-path":
                configuration.databasePath = try requiredValue(flag, arguments: arguments, index: &index)
            default:
                throw NoopCLIQueryError.usage("unknown resource flag")
            }
        }
        return NoopCLIResourceRequest(uri: uri, configuration: configuration)
    }

    public static func resourcePayload(_ request: NoopCLIResourceRequest) throws -> JSONValue {
        try NoopToolDispatcher(configuration: request.configuration).resourcePayload(uri: request.uri)
    }

    public static func encodeLine(_ value: JSONValue) throws -> Data {
        var data = try JSONEncoder().encode(value)
        data.append(0x0A)
        return data
    }

    /// Product version printed by `noop-local-access --version` / `-V`.
    /// SPM `Package()` has no version field, so this matches `noopLocalAccessServerVersion`.
    public static let version = noopLocalAccessServerVersion

    public static func versionLine() -> String {
        version + "\n"
    }

    public static func wantsVersion(arguments: [String]) throws -> Bool {
        guard let first = arguments.first, first == "--version" || first == "-V" else { return false }
        guard arguments.count == 1 else {
            throw NoopCLIQueryError.usage("version does not accept additional arguments")
        }
        return true
    }

    public static func parseVersionCommand(arguments: [String]) throws {
        guard arguments.isEmpty else {
            throw NoopCLIQueryError.usage("version does not accept additional arguments")
        }
    }

    private static func requiredValue(_ flag: String, arguments: [String], index: inout Int) throws -> String {
        guard index < arguments.count, !arguments[index].hasPrefix("--") else {
            throw NoopCLIQueryError.usage("missing value for \(flag)")
        }
        defer { index += 1 }
        return arguments[index]
    }

    private static func integerValue(_ flag: String, arguments: [String], index: inout Int) throws -> JSONValue {
        let raw = try requiredValue(flag, arguments: arguments, index: &index)
        guard let value = Int(raw) else {
            throw NoopCLIQueryError.usage("value for \(flag) must be an integer")
        }
        return .int(value)
    }

    private static func canonicalizeResourceURI(_ raw: String) -> String? {
        let path: String
        if raw.hasPrefix("noop://") {
            path = String(raw.dropFirst("noop://".count))
        } else {
            path = raw
        }
        let uri = "noop://\(path)"
        return resourceURIs.contains(uri) ? uri : nil
    }

    private static func unsupported(_ flag: String, toolName: String) -> NoopCLIQueryError {
        .usage("\(flag) is not supported for \(toolName)")
    }
}
