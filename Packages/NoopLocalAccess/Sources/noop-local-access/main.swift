import Foundation
import NoopLocalAccessCore

@main
enum NoopLocalAccessMain {
    static func main() {
        var args = Array(CommandLine.arguments.dropFirst())
        do {
            if try NoopCLIQuery.wantsVersion(arguments: args) {
                FileHandle.standardOutput.write(Data(NoopCLIQuery.versionLine().utf8))
                return
            }
        } catch let error as NoopCLIQueryError {
            fputs("[noop-local-access] \(error)\n", stderr)
            Foundation.exit(error.exitCode)
        }

        let command = args.first ?? "mcp"
        if !args.isEmpty { args.removeFirst() }

        switch command {
        case "mcp":
            runMCP(configuration: .environment())
        case "query":
            runQuery(arguments: args)
        case "tools":
            runTools(arguments: args)
        case "resource":
            runResource(arguments: args)
        case "codex-config":
            print(codexConfig(arguments: args))
        case "--help", "-h", "help":
            print(helpText)
        default:
            fputs("Unknown command: \(command)\n\n\(helpText)\n", stderr)
            Foundation.exit(64)
        }
    }

    private static func runQuery(arguments: [String]) {
        if arguments == ["--help"] || arguments == ["-h"] {
            print(helpText)
            return
        }
        do {
            if try NoopCLIQuery.wantsListTools(arguments: arguments) {
                FileHandle.standardOutput.write(
                    try NoopCLIQuery.encodeLine(
                        NoopCLIQuery.listToolsPayload(),
                        pretty: try NoopCLIQuery.outputPretty(arguments: arguments)
                    )
                )
                return
            }
            let request = try NoopCLIQuery.parse(arguments: arguments)
            let payload = try NoopCLIQuery.dispatch(request)
            FileHandle.standardOutput.write(try NoopCLIQuery.encodeLine(payload, pretty: request.pretty))
        } catch let error as NoopCLIQueryError {
            emitDiagnostic("\(error)", arguments: arguments)
            Foundation.exit(error.exitCode)
        } catch let error as LocalAccessError {
            let message: String
            if case .databaseUnavailable = error {
                message = "NOOP database is unavailable"
            } else {
                message = error.description
            }
            emitDiagnostic(message, arguments: arguments)
            Foundation.exit(1)
        } catch {
            emitDiagnostic("runtime error", arguments: arguments)
            Foundation.exit(1)
        }
    }

    private static func runTools(arguments: [String]) {
        do {
            try NoopCLIQuery.parseToolsCommand(arguments: arguments)
            FileHandle.standardOutput.write(try NoopCLIQuery.encodeLine(NoopCLIQuery.listToolsPayload()))
        } catch let error as NoopCLIQueryError {
            emitDiagnostic("\(error)", arguments: arguments)
            Foundation.exit(error.exitCode)
        } catch {
            emitDiagnostic("runtime error", arguments: arguments)
            Foundation.exit(1)
        }
    }

    private static func runResource(arguments: [String]) {
        if arguments == ["--help"] || arguments == ["-h"] {
            print(helpText)
            return
        }
        do {
            if try NoopCLIQuery.wantsListResources(arguments: arguments) {
                FileHandle.standardOutput.write(
                    try NoopCLIQuery.encodeLine(
                        NoopCLIQuery.listResourcesPayload(),
                        pretty: try NoopCLIQuery.outputPretty(arguments: arguments)
                    )
                )
                return
            }
            let request = try NoopCLIQuery.parseResourceCommand(arguments: arguments)
            let payload = try NoopCLIQuery.resourcePayload(request)
            FileHandle.standardOutput.write(try NoopCLIQuery.encodeLine(payload, pretty: request.pretty))
        } catch let error as NoopCLIQueryError {
            emitDiagnostic("\(error)", arguments: arguments)
            Foundation.exit(error.exitCode)
        } catch let error as LocalAccessError {
            let message: String
            if case .databaseUnavailable = error {
                message = "NOOP database is unavailable"
            } else {
                message = error.description
            }
            emitDiagnostic(message, arguments: arguments)
            Foundation.exit(1)
        } catch {
            emitDiagnostic("runtime error", arguments: arguments)
            Foundation.exit(1)
        }
    }

    private static func emitDiagnostic(_ message: String, arguments: [String]) {
        let quiet: Bool
        if let parsed = try? NoopCLIQuery.outputQuiet(arguments: arguments) {
            quiet = parsed
        } else {
            quiet = arguments.contains("--quiet")
        }
        guard let data = NoopCLIQuery.stderrDiagnostic(message, quiet: quiet) else { return }
        FileHandle.standardError.write(data)
    }

    private static func runMCP(configuration: LocalAccessConfiguration) {

        let server = NoopMCPServer(configuration: configuration)
        while let line = readLine(strippingNewline: true) {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            let response = server.handleLine(trimmed)
            guard response != .null else { continue }
            write(response)
        }
    }

    private static func write(_ value: JSONValue) {
        do {
            let data = try JSONEncoder().encode(value)
            FileHandle.standardOutput.write(data)
            FileHandle.standardOutput.write(Data("\n".utf8))
        } catch {
            fputs("[noop-local-access] failed to encode response: \(error)\n", stderr)
        }
    }

    private static func codexConfig(arguments: [String]) -> String {
        let executable = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL.path
        var dbPath: String?
        var iterator = arguments.makeIterator()
        while let arg = iterator.next() {
            if arg == "--db-path" {
                dbPath = iterator.next()
            }
        }

        var lines = [
            "[mcp_servers.noop]",
            "command = \"\(toml(executable))\"",
            "args = [\"mcp\"]",
            "startup_timeout_sec = 10",
            "tool_timeout_sec = 60",
            "default_tools_approval_mode = \"prompt\"",
        ]
        if let dbPath, !dbPath.isEmpty {
            lines.append("")
            lines.append("[mcp_servers.noop.env]")
            lines.append("NOOP_DB_PATH = \"\(toml(dbPath))\"")
        }
        return lines.joined(separator: "\n")
    }

    private static func toml(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
    }

    private static let helpText = """
    Usage:
      noop-local-access --version
      noop-local-access -V
      noop-local-access mcp
      noop-local-access query <tool> [flags] [--pretty] [--quiet]
      noop-local-access query --list-tools [--pretty] [--quiet]
      noop-local-access tools
      noop-local-access resource --list [--pretty] [--quiet]
      noop-local-access resource <uri> [--db-path PATH] [--pretty] [--quiet]
      noop-local-access codex-config [--db-path /absolute/path/to/whoop.sqlite]

    Query tools:
      health_snapshot [--days N]
      metric_series --key KEY [--source SOURCE] [--days N] [--from-day YYYY-MM-DD] [--to-day YYYY-MM-DD] [--limit N]
      data_freshness
      sleep_summary [--days N] [--include-motion] [--include-sleep-state] [--include-start-adjusted]
      workout_summary [--days N] [--include-zones]
      hr_series [--hours N] [--from-ts UNIX] [--to-ts UNIX] [--bucket-seconds N] [--limit N] [--device-id ID]
      spo2_series [--hours N] [--from-ts UNIX] [--to-ts UNIX] [--bucket-seconds N] [--limit N] [--device-id ID]
      skin_temp_series [--hours N] [--from-ts UNIX] [--to-ts UNIX] [--bucket-seconds N] [--limit N] [--device-id ID]
      resp_series [--hours N] [--from-ts UNIX] [--to-ts UNIX] [--bucket-seconds N] [--limit N] [--device-id ID]
      step_series [--hours N] [--from-ts UNIX] [--to-ts UNIX] [--bucket-seconds N] [--limit N] [--device-id ID]
      gravity_series [--hours N] [--from-ts UNIX] [--to-ts UNIX] [--bucket-seconds N] [--limit N] [--device-id ID]
      battery_series [--hours N] [--from-ts UNIX] [--to-ts UNIX] [--bucket-seconds N] [--limit N] [--device-id ID]
      sleep_state_series [--hours N] [--from-ts UNIX] [--to-ts UNIX] [--bucket-seconds N] [--limit N] [--device-id ID]
      sleep_stages [--days N] [--limit N] [--max-points N]
      event_series --kind KIND [--hours N] [--from-ts UNIX] [--to-ts UNIX] [--limit N] [--device-id ID]
      event_kinds [--limit N] [--device-id ID]
      rr_series [--hours N] [--from-ts UNIX] [--to-ts UNIX] [--limit N] [--device-id ID]

    Resource URIs (same JSON as MCP resourcePayload):
      noop-local-access resource --list prints the known URIs as a JSON array.
      noop://tools/catalog
      noop://data/freshness
      noop://health/snapshot
      noop://metrics/catalog
      noop://sources
      Short forms tools/catalog, data/freshness, health/snapshot, metrics/catalog, sources are accepted.

    Query options:
      --db-path PATH    Explicit NOOP SQLite path. Otherwise NOOP_DB_PATH or the official app container is used.
      --pretty          Pretty-print query and resource JSON. Default is compact one line.
      --quiet           Suppress stderr diagnostics. stdout JSON is unchanged.

    Environment:
      NOOP_DB_PATH    Explicit NOOP SQLite path. Optional; otherwise the official macOS app container is used.
      NOOP_BUNDLE_ID  Optional non-default bundle id. Not needed for the official app.
      NOOP_DEVICE_ID  Optional source id. Defaults to my-whoop.

    The MCP server is read-only, stdio-based, and exposes bounded local NOOP data tools.
    """
}
