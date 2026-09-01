import Foundation

/// The single read-only dispatch surface shared by MCP and the direct CLI transport.
public final class NoopToolDispatcher {
    private let configuration: LocalAccessConfiguration
    private var dataAccess: NoopDataAccess?

    public init(configuration: LocalAccessConfiguration = .environment()) {
        self.configuration = configuration
    }

    public static let toolNames = [
        "health_snapshot",
        "metric_series",
        "data_freshness",
        "sleep_summary",
        "workout_summary",
        "hr_series",
        "sleep_stages",
        "event_series",
    ]

    public func dispatch(name: String, arguments: [String: JSONValue] = [:]) throws -> JSONValue {
        switch name {
        case "health_snapshot":
            return try data().healthSnapshot(days: boundedDays(arguments["days"], default: 14, max: 120))
        case "metric_series":
            guard let key = arguments["key"]?.stringValue else {
                throw LocalAccessError.invalidParams("metric_series requires key")
            }
            return try data().metricSeries(
                key: key,
                source: arguments["source"]?.stringValue ?? "my-whoop",
                days: boundedDays(arguments["days"], default: 90, max: 4000),
                fromDay: arguments["from_day"]?.stringValue,
                toDay: arguments["to_day"]?.stringValue,
                limit: boundedLimit(arguments["limit"], default: 500, max: 2000)
            )
        case "data_freshness":
            return try data().freshness()
        case "sleep_summary":
            return try data().sleepSummary(days: boundedDays(arguments["days"], default: 30, max: 4000))
        case "workout_summary":
            return try data().workoutSummary(days: boundedDays(arguments["days"], default: 90, max: 4000))
        case "hr_series":
            let fromTs = arguments["from_ts"]?.intValue
            let toTs = arguments["to_ts"]?.intValue
            if (fromTs == nil) != (toTs == nil) {
                throw LocalAccessError.invalidParams("hr_series requires both from_ts and to_ts")
            }
            return try data().hrSeries(
                hours: boundedDays(arguments["hours"], default: 6, max: 24),
                fromTs: fromTs,
                toTs: toTs,
                bucketSeconds: boundedLimit(arguments["bucket_seconds"], default: 60, max: 3600),
                limit: boundedLimit(arguments["limit"], default: 500, max: 2000),
                deviceId: arguments["device_id"]?.stringValue
            )
        case "sleep_stages":
            return try data().sleepStages(
                days: boundedDays(arguments["days"], default: 30, max: 4000),
                limit: boundedLimit(arguments["limit"], default: 14, max: 60),
                maxPoints: boundedLimit(arguments["max_points"], default: 200, max: 2000)
            )
        case "event_series":
            guard let kind = arguments["kind"]?.stringValue else {
                throw LocalAccessError.invalidParams("event_series requires kind")
            }
            let fromTs = arguments["from_ts"]?.intValue
            let toTs = arguments["to_ts"]?.intValue
            if (fromTs == nil) != (toTs == nil) {
                throw LocalAccessError.invalidParams("event_series requires both from_ts and to_ts")
            }
            return try data().eventSeries(
                kind: kind,
                hours: boundedDays(arguments["hours"], default: 6, max: 24),
                fromTs: fromTs,
                toTs: toTs,
                limit: boundedLimit(arguments["limit"], default: 500, max: 2000),
                deviceId: arguments["device_id"]?.stringValue
            )
        default:
            throw LocalAccessError.toolNotFound(name)
        }
    }

    public func resourcePayload(uri: String) throws -> JSONValue {
        switch uri {
        case "noop://health/snapshot":
            return try dispatch(name: "health_snapshot", arguments: ["days": .int(14)])
        case "noop://data/freshness":
            return try dispatch(name: "data_freshness")
        case "noop://metrics/catalog":
            return NoopDataAccess.metricCatalog()
        case "noop://sources":
            return NoopDataAccess.sources()
        default:
            throw LocalAccessError.resourceNotFound(uri)
        }
    }

    private func data() throws -> NoopDataAccess {
        if let dataAccess { return dataAccess }
        do {
            let access = try NoopDataAccess.open(configuration: configuration)
            dataAccess = access
            return access
        } catch let error as LocalAccessError {
            throw error
        } catch {
            throw LocalAccessError.databaseUnavailable("NOOP database is not available: \(error)")
        }
    }
}
