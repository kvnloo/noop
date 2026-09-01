import Foundation
import GRDB

public enum LocalAccessError: Error, CustomStringConvertible, Equatable {
    case invalidParams(String)
    case methodNotFound(String)
    case toolNotFound(String)
    case resourceNotFound(String)
    case promptNotFound(String)
    case databaseUnavailable(String)

    public var description: String {
        switch self {
        case .invalidParams(let message),
             .databaseUnavailable(let message):
            return message
        case .methodNotFound(let method):
            return "Unsupported MCP method: \(method)"
        case .toolNotFound(let tool):
            return "Unknown NOOP tool: \(tool)"
        case .resourceNotFound(let uri):
            return "Unknown NOOP resource: \(uri)"
        case .promptNotFound(let name):
            return "Unknown NOOP prompt: \(name)"
        }
    }

    public var rpcCode: Int {
        switch self {
        case .methodNotFound:
            return -32601
        case .invalidParams, .toolNotFound, .resourceNotFound, .promptNotFound:
            return -32602
        case .databaseUnavailable:
            return -32603
        }
    }
}

public struct LocalAccessConfiguration: Equatable, Sendable {
    public var databasePath: String?
    public var bundleID: String?
    public var deviceID: String

    public init(databasePath: String? = nil, bundleID: String? = nil, deviceID: String = "my-whoop") {
        self.databasePath = databasePath
        self.bundleID = bundleID
        self.deviceID = deviceID
    }

    public static func environment(_ env: [String: String] = ProcessInfo.processInfo.environment) -> LocalAccessConfiguration {
        LocalAccessConfiguration(
            databasePath: nonEmpty(env["NOOP_DB_PATH"]),
            bundleID: nonEmpty(env["NOOP_BUNDLE_ID"]),
            deviceID: nonEmpty(env["NOOP_DEVICE_ID"]) ?? "my-whoop"
        )
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}

public enum DatabasePathResolver {
    public static let productionBundleID = "com.noopapp.noop"

    public static func resolve(configuration: LocalAccessConfiguration) throws -> String {
        let fm = FileManager.default
        if let explicit = configuration.databasePath {
            let expanded = expandHome(explicit)
            guard fm.fileExists(atPath: expanded) else {
                throw LocalAccessError.databaseUnavailable("NOOP database not found at NOOP_DB_PATH.")
            }
            return expanded
        }

        for candidate in candidates(bundleID: configuration.bundleID) where fm.fileExists(atPath: candidate) {
            return candidate
        }

        throw LocalAccessError.databaseUnavailable(
            "No official NOOP database was found. Start NOOP once, or set NOOP_DB_PATH explicitly."
        )
    }

    public static func candidates(bundleID: String? = nil, home: String = FileManager.default.homeDirectoryForCurrentUser.path) -> [String] {
        var ids = [productionBundleID]
        if let bundleID, bundleID != productionBundleID {
            ids.insert(bundleID, at: 0)
        }

        var paths: [String] = ids.map {
            "\(home)/Library/Containers/\($0)/Data/Library/Application Support/OpenWhoop/whoop.sqlite"
        }
        paths.append("\(home)/Library/Application Support/OpenWhoop/whoop.sqlite")
        return orderedUnique(paths)
    }

    public static func expandHome(_ path: String, home: String = FileManager.default.homeDirectoryForCurrentUser.path) -> String {
        guard path == "~" || path.hasPrefix("~/") else { return path }
        return home + String(path.dropFirst())
    }
}

public struct DailyMetricRow: Equatable, Sendable {
    public let day: String
    public let totalSleepMin: Double?
    public let efficiency: Double?
    public let deepMin: Double?
    public let remMin: Double?
    public let lightMin: Double?
    public let disturbances: Int?
    public let restingHr: Int?
    public let avgHrv: Double?
    public let recovery: Double?
    public let strain: Double?
    public let exerciseCount: Int?
    public let spo2Pct: Double?
    public let skinTempDevC: Double?
    public let respRateBpm: Double?
    public let steps: Int?
    public let activeKcalEst: Double?
}

public struct SleepSessionRow: Equatable, Sendable {
    public let startTs: Int
    public let endTs: Int
    public let efficiency: Double?
    public let restingHr: Int?
    public let avgHrv: Double?
    public let stagesJSON: String?
    public let motionJSON: String?
    public let sleepStateJSON: String?
    public let startTsAdjusted: Int?
    // `startTsAdjusted`, `motionJSON`, and `sleepStateJSON` are selected only when pragma_table_info
    // lists them; pre-v14 / foreign sleepSession tables stay readable. sleep_summary omits
    // startTsAdjusted unless includeStartAdjusted is true and the column has a value.
}

public struct MetricPointRow: Equatable, Sendable {
    public let day: String
    public let key: String
    public let value: Double
}

public struct AppleDailyRow: Equatable, Sendable {
    public let day: String
    public let steps: Int?
    public let activeKcal: Double?
    public let basalKcal: Double?
    public let vo2max: Double?
    public let avgHr: Int?
    public let maxHr: Int?
    public let walkingHr: Int?
    public let weightKg: Double?
}

public struct WorkoutRow: Equatable, Sendable {
    public let startTs: Int
    public let endTs: Int
    public let sport: String
    public let source: String
    public let durationS: Double?
    public let energyKcal: Double?
    public let avgHr: Int?
    public let maxHr: Int?
    public let strain: Double?
    public let distanceM: Double?
    public let zonesJSON: String?
    public let notes: String?
}

public struct StorageStats: Equatable, Sendable {
    public let decodedRows: Int
    public let rawBatches: Int
    public let rawBytes: Int
}

public struct HRBucketRow: Equatable, Sendable {
    public let ts: Int
    public let bpm: Double
}

public struct Spo2BucketRow: Equatable, Sendable {
    public let ts: Int
    public let red: Double
    public let ir: Double
}

public struct SkinTempBucketRow: Equatable, Sendable {
    public let ts: Int
    public let raw: Double
}

public struct EventRow: Equatable, Sendable {
    public let ts: Int
    public let kind: String
    public let payloadJSON: String
}

public struct RRIntervalRow: Equatable, Sendable {
    public let ts: Int
    public let rrMs: Int
    public let seq: Int?
    public let ord: Int?
}

public final class ReadonlyNoopStore {
    private let dbQueue: DatabaseQueue
    private let tableNames: Set<String>
    private let rrIntervalColumns: Set<String>
    private let sleepSessionColumns: Set<String>
    /// WhoopStore `RRSourceChannel.spo2Ibi.rawValue` — stored enum, not the 0x6E wire tag.
    private static let spo2IbiChannel = 2

    public init(path: String) throws {
        var config = Configuration()
        config.readonly = true
        config.busyMode = .timeout(5)
        dbQueue = try DatabaseQueue(path: path, configuration: config)
        tableNames = try dbQueue.read { db in
            try Set(String.fetchAll(db, sql: "SELECT name FROM sqlite_master WHERE type = 'table'"))
        }
        if tableNames.contains("rrInterval") {
            rrIntervalColumns = try dbQueue.read { db in
                try Set(String.fetchAll(db, sql: "SELECT name FROM pragma_table_info('rrInterval')"))
            }
        } else {
            rrIntervalColumns = []
        }
        if tableNames.contains("sleepSession") {
            sleepSessionColumns = try dbQueue.read { db in
                try Set(String.fetchAll(db, sql: "SELECT name FROM pragma_table_info('sleepSession')"))
            }
        } else {
            sleepSessionColumns = []
        }
        try validateSchema()
    }

    public func dailyMetrics(deviceId: String, from: String, to: String) throws -> [DailyMetricRow] {
        guard tableNames.contains("dailyMetric") else { return [] }
        return try dbQueue.read { db in
            try Row.fetchAll(db, sql: """
                SELECT day, totalSleepMin, efficiency, deepMin, remMin, lightMin, disturbances,
                       restingHr, avgHrv, recovery, strain, exerciseCount,
                       spo2Pct, skinTempDevC, respRateBpm, steps, activeKcalEst
                FROM dailyMetric
                WHERE deviceId = ? AND day >= ? AND day <= ?
                ORDER BY day ASC
                """, arguments: [deviceId, from, to])
                .map {
                    DailyMetricRow(day: $0["day"], totalSleepMin: $0["totalSleepMin"],
                                   efficiency: $0["efficiency"], deepMin: $0["deepMin"],
                                   remMin: $0["remMin"], lightMin: $0["lightMin"],
                                   disturbances: $0["disturbances"], restingHr: $0["restingHr"],
                                   avgHrv: $0["avgHrv"], recovery: $0["recovery"],
                                   strain: $0["strain"], exerciseCount: $0["exerciseCount"],
                                   spo2Pct: $0["spo2Pct"], skinTempDevC: $0["skinTempDevC"],
                                   respRateBpm: $0["respRateBpm"], steps: $0["steps"],
                                   activeKcalEst: $0["activeKcalEst"])
                }
        }
    }

    public func sleepSessions(deviceId: String, from: Int, to: Int, limit: Int) throws -> [SleepSessionRow] {
        guard tableNames.contains("sleepSession") else { return [] }
        return try dbQueue.read { db in
            let motionSelect = sleepSessionColumns.contains("motionJSON") ? "motionJSON" : "NULL AS motionJSON"
            let sleepStateSelect = sleepSessionColumns.contains("sleepStateJSON") ? "sleepStateJSON" : "NULL AS sleepStateJSON"
            let startAdjustedSelect = sleepSessionColumns.contains("startTsAdjusted") ? "startTsAdjusted" : "NULL AS startTsAdjusted"
            return try Row.fetchAll(db, sql: """
                SELECT startTs, endTs, efficiency, restingHr, avgHrv, stagesJSON, \(motionSelect), \(sleepStateSelect), \(startAdjustedSelect)
                FROM sleepSession
                WHERE deviceId = ? AND startTs >= ? AND startTs <= ?
                ORDER BY startTs ASC LIMIT ?
                """, arguments: [deviceId, from, to, limit])
                .map {
                    SleepSessionRow(startTs: $0["startTs"], endTs: $0["endTs"],
                                    efficiency: $0["efficiency"], restingHr: $0["restingHr"],
                                    avgHrv: $0["avgHrv"], stagesJSON: $0["stagesJSON"],
                                    motionJSON: $0["motionJSON"],
                                    sleepStateJSON: $0["sleepStateJSON"],
                                    startTsAdjusted: $0["startTsAdjusted"])
                }
        }
    }

    public func metricSeries(deviceId: String, key: String, from: String, to: String) throws -> [MetricPointRow] {
        guard tableNames.contains("metricSeries") else { return [] }
        return try dbQueue.read { db in
            try Row.fetchAll(db, sql: """
                SELECT day, key, value FROM metricSeries
                WHERE deviceId = ? AND key = ? AND day >= ? AND day <= ?
                ORDER BY day ASC
                """, arguments: [deviceId, key, from, to])
                .map { MetricPointRow(day: $0["day"], key: $0["key"], value: $0["value"]) }
        }
    }

    public func metricKeys(deviceId: String) throws -> [String] {
        guard tableNames.contains("metricSeries") else { return [] }
        return try dbQueue.read { db in
            try String.fetchAll(db, sql: """
                SELECT DISTINCT key FROM metricSeries
                WHERE deviceId = ?
                ORDER BY key ASC
                """, arguments: [deviceId])
        }
    }

    public func appleDaily(deviceId: String, from: String, to: String) throws -> [AppleDailyRow] {
        guard tableNames.contains("appleDaily") else { return [] }
        return try dbQueue.read { db in
            try Row.fetchAll(db, sql: """
                SELECT day, steps, activeKcal, basalKcal, vo2max, avgHr, maxHr, walkingHr, weightKg
                FROM appleDaily
                WHERE deviceId = ? AND day >= ? AND day <= ?
                ORDER BY day ASC
                """, arguments: [deviceId, from, to])
                .map {
                    AppleDailyRow(day: $0["day"], steps: $0["steps"], activeKcal: $0["activeKcal"],
                                  basalKcal: $0["basalKcal"], vo2max: $0["vo2max"],
                                  avgHr: $0["avgHr"], maxHr: $0["maxHr"],
                                  walkingHr: $0["walkingHr"], weightKg: $0["weightKg"])
                }
        }
    }

    public func workouts(deviceId: String, from: Int, to: Int, limit: Int) throws -> [WorkoutRow] {
        guard tableNames.contains("workout") else { return [] }
        return try dbQueue.read { db in
            try Row.fetchAll(db, sql: """
                SELECT startTs, endTs, sport, source, durationS, energyKcal, avgHr, maxHr,
                       strain, distanceM, zonesJSON, notes
                FROM workout
                WHERE deviceId = ? AND startTs >= ? AND startTs <= ?
                ORDER BY startTs ASC LIMIT ?
                """, arguments: [deviceId, from, to, limit])
                .map {
                    WorkoutRow(startTs: $0["startTs"], endTs: $0["endTs"], sport: $0["sport"],
                               source: $0["source"], durationS: $0["durationS"],
                               energyKcal: $0["energyKcal"], avgHr: $0["avgHr"],
                               maxHr: $0["maxHr"], strain: $0["strain"],
                               distanceM: $0["distanceM"], zonesJSON: $0["zonesJSON"],
                               notes: $0["notes"])
                }
        }
    }

    public func latestRRIntervalTs(deviceId: String) throws -> Int? {
        guard tableNames.contains("rrInterval") else { return nil }
        let hasSrc = rrIntervalColumns.contains("srcChannel")
        let hasSuspect = rrIntervalColumns.contains("tsSuspect")

        var whereSQL = "WHERE deviceId = ?"
        if hasSrc {
            whereSQL += " AND (srcChannel IS NULL OR srcChannel <> ?)"
        }
        if hasSuspect {
            whereSQL += " AND (tsSuspect IS NULL OR tsSuspect <> 1)"
        }

        let sql = "SELECT MAX(ts) FROM rrInterval \(whereSQL)"
        return try dbQueue.read { db in
            if hasSrc {
                return try Int.fetchOne(db, sql: sql, arguments: [deviceId, Self.spo2IbiChannel])
            }
            return try Int.fetchOne(db, sql: sql, arguments: [deviceId])
        }
    }

    public func latestEventTs(deviceId: String) throws -> Int? {
        guard tableNames.contains("event") else { return nil }
        return try dbQueue.read { db in
            try Int.fetchOne(db, sql: "SELECT MAX(ts) FROM event WHERE deviceId = ?", arguments: [deviceId])
        }
    }

    public func latestSleepSessionTs(deviceId: String) throws -> Int? {
        guard tableNames.contains("sleepSession") else { return nil }
        return try dbQueue.read { db in
            try Int.fetchOne(db, sql: "SELECT MAX(endTs) FROM sleepSession WHERE deviceId = ?", arguments: [deviceId])
        }
    }

    public func latestWorkoutTs(deviceId: String) throws -> Int? {
        guard tableNames.contains("workout") else { return nil }
        return try dbQueue.read { db in
            try Int.fetchOne(db, sql: "SELECT MAX(endTs) FROM workout WHERE deviceId = ?", arguments: [deviceId])
        }
    }

    public func latestHRSampleTs(deviceId: String) throws -> Int? {
        let hasHr = tableNames.contains("hrSample")
        let hasPpg = tableNames.contains("ppgHrSample")
        guard hasHr || hasPpg else { return nil }

        return try dbQueue.read { db in
            switch (hasHr, hasPpg) {
            case (true, true):
                return try Int.fetchOne(db, sql: """
                    SELECT MAX(ts) FROM (
                        SELECT ts FROM hrSample WHERE deviceId = ?
                        UNION ALL
                        SELECT ts FROM ppgHrSample WHERE deviceId = ?
                    )
                    """, arguments: [deviceId, deviceId])
            case (true, false):
                return try Int.fetchOne(db, sql: "SELECT MAX(ts) FROM hrSample WHERE deviceId = ?", arguments: [deviceId])
            case (false, true):
                return try Int.fetchOne(db, sql: "SELECT MAX(ts) FROM ppgHrSample WHERE deviceId = ?", arguments: [deviceId])
            case (false, false):
                return nil
            }
        }
    }

    public func hrBuckets(deviceId: String, from: Int, to: Int, bucketSeconds: Int) throws -> [HRBucketRow] {
        let hasHr = tableNames.contains("hrSample")
        let hasPpg = tableNames.contains("ppgHrSample")
        guard hasHr || hasPpg else { return [] }
        let bucket = max(1, bucketSeconds)

        return try dbQueue.read { db in
            let sql: String
            let arguments: StatementArguments
            switch (hasHr, hasPpg) {
            case (true, true):
                // Same measured-first UNION + PPG anti-join + GROUP BY ts/bucket as WhoopStore.hrBuckets.
                sql = """
                    SELECT (ts / ?) * ? AS bucket, AVG(bpm) AS avgBpm FROM (
                        SELECT ts, bpm FROM hrSample
                        WHERE deviceId = ? AND ts >= ? AND ts <= ?
                        UNION ALL
                        SELECT p.ts, p.bpm FROM ppgHrSample p
                        WHERE p.deviceId = ? AND p.ts >= ? AND p.ts <= ?
                          AND NOT EXISTS (
                            SELECT 1 FROM hrSample h
                            WHERE h.deviceId = p.deviceId AND h.ts = p.ts)
                    )
                    GROUP BY ts / ?
                    ORDER BY bucket ASC
                    """
                arguments = [bucket, bucket, deviceId, from, to, deviceId, from, to, bucket]
            case (true, false):
                sql = """
                    SELECT (ts / ?) * ? AS bucket, AVG(bpm) AS avgBpm
                    FROM hrSample
                    WHERE deviceId = ? AND ts >= ? AND ts <= ?
                    GROUP BY ts / ?
                    ORDER BY bucket ASC
                    """
                arguments = [bucket, bucket, deviceId, from, to, bucket]
            case (false, true):
                sql = """
                    SELECT (ts / ?) * ? AS bucket, AVG(bpm) AS avgBpm
                    FROM ppgHrSample
                    WHERE deviceId = ? AND ts >= ? AND ts <= ?
                    GROUP BY ts / ?
                    ORDER BY bucket ASC
                    """
                arguments = [bucket, bucket, deviceId, from, to, bucket]
            case (false, false):
                return []
            }
            return try Row.fetchAll(db, sql: sql, arguments: arguments).map {
                HRBucketRow(ts: $0["bucket"], bpm: $0["avgBpm"])
            }
        }
    }

    public func spo2Buckets(deviceId: String, from: Int, to: Int, bucketSeconds: Int) throws -> [Spo2BucketRow] {
        guard tableNames.contains("spo2Sample") else { return [] }
        let bucket = max(1, bucketSeconds)
        return try dbQueue.read { db in
            try Row.fetchAll(db, sql: """
                SELECT (ts / ?) * ? AS bucket, AVG(red) AS avgRed, AVG(ir) AS avgIr
                FROM spo2Sample
                WHERE deviceId = ? AND ts >= ? AND ts <= ?
                GROUP BY ts / ?
                ORDER BY bucket ASC
                """, arguments: [bucket, bucket, deviceId, from, to, bucket]).map {
                Spo2BucketRow(ts: $0["bucket"], red: $0["avgRed"], ir: $0["avgIr"])
            }
        }
    }

    public func skinTempBuckets(deviceId: String, from: Int, to: Int, bucketSeconds: Int) throws -> [SkinTempBucketRow] {
        guard tableNames.contains("skinTempSample") else { return [] }
        let bucket = max(1, bucketSeconds)
        return try dbQueue.read { db in
            try Row.fetchAll(db, sql: """
                SELECT (ts / ?) * ? AS bucket, AVG(raw) AS avgRaw
                FROM skinTempSample
                WHERE deviceId = ? AND ts >= ? AND ts <= ?
                GROUP BY ts / ?
                ORDER BY bucket ASC
                """, arguments: [bucket, bucket, deviceId, from, to, bucket]).map {
                SkinTempBucketRow(ts: $0["bucket"], raw: $0["avgRaw"])
            }
        }
    }

    public func events(deviceId: String, kind: String, from: Int, to: Int, limit: Int) throws -> [EventRow] {
        guard tableNames.contains("event") else { return [] }
        let cap = max(1, limit)
        return try dbQueue.read { db in
            let rows = try Row.fetchAll(db, sql: """
                SELECT ts, kind, payloadJSON FROM event
                WHERE deviceId = ? AND kind = ? AND ts >= ? AND ts <= ?
                ORDER BY ts DESC, kind DESC LIMIT ?
                """, arguments: [deviceId, kind, from, to, cap])
                .map {
                    EventRow(ts: $0["ts"], kind: $0["kind"], payloadJSON: $0["payloadJSON"])
                }
            return Array(rows.reversed())
        }
    }

    /// Bounded WhoopStore.rrIntervals twin: exclude spo2Ibi and tsSuspect==1, keep NULLs.
    /// Suffix-capped (newest first in SQL, then reversed) so this is never an unbounded 1Hz dump.
    public func rrIntervals(deviceId: String, from: Int, to: Int, limit: Int) throws -> [RRIntervalRow] {
        guard tableNames.contains("rrInterval") else { return [] }
        let cap = max(1, limit)
        let hasSrc = rrIntervalColumns.contains("srcChannel")
        let hasSuspect = rrIntervalColumns.contains("tsSuspect")
        let hasOrd = rrIntervalColumns.contains("ord")
        let hasSeq = rrIntervalColumns.contains("seq")

        var select = ["ts", "rrMs"]
        if hasOrd { select.append("ord") }
        if hasSeq { select.append("seq") }

        var whereSQL = "WHERE deviceId = ? AND ts >= ? AND ts <= ?"
        if hasSrc {
            whereSQL += " AND (srcChannel IS NULL OR srcChannel <> ?)"
        }
        if hasSuspect {
            whereSQL += " AND (tsSuspect IS NULL OR tsSuspect <> 1)"
        }

        var order = ["ts DESC"]
        if hasOrd { order.append("ord DESC") }
        order.append("rrMs DESC")
        if hasSeq { order.append("seq DESC") }

        let sql = """
            SELECT \(select.joined(separator: ", ")) FROM rrInterval
            \(whereSQL)
            ORDER BY \(order.joined(separator: ", ")) LIMIT ?
            """
        let arguments: StatementArguments = hasSrc
            ? [deviceId, from, to, Self.spo2IbiChannel, cap]
            : [deviceId, from, to, cap]

        return try dbQueue.read { db in
            let rows = try Row.fetchAll(db, sql: sql, arguments: arguments).map { row in
                RRIntervalRow(
                    ts: row["ts"],
                    rrMs: row["rrMs"],
                    seq: hasSeq ? (row["seq"] as Int?) : nil,
                    ord: hasOrd ? (row["ord"] as Int?) : nil
                )
            }
            return Array(rows.reversed())
        }
    }

    public func storageStats() throws -> StorageStats {
        try dbQueue.read { db in
            // Every durable decoded per-second table. The four below the first line were landed as
            // instrumentation and then left OUT of this count, which is how an unbounded, unpruned table
            // became invisible: `ppgWaveformSample` banks a ~48-byte BLOB per v26 strap-second and
            // `v18AuxSample` ~30 bytes per v18 strap-second, neither is pruned (deliberately — this is
            // decoded biometric history, not the transient raw outbox), and until now neither showed up in
            // the only readout a user has for "what is the store spending space on". Growth that nothing
            // reads still has to be growth somebody can SEE. Guarded by `tableNames.contains` so a store
            // predating any of these migrations still reports.
            let decodedTables = [
                "hrSample", "rrInterval", "event", "battery", "spo2Sample",
                "skinTempSample", "respSample", "gravitySample", "ppgHrSample", "stepSample",
                "sleepStateSample", "ppgWaveformSample", "v18AuxSample",
            ]
            var decodedRows = 0
            for table in decodedTables where tableNames.contains(table) {
                decodedRows += try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM \(table)") ?? 0
            }
            let rawBatches = tableNames.contains("rawBatch")
                ? (try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM rawBatch") ?? 0)
                : 0
            let rawBytes = tableNames.contains("rawBatch")
                ? (try Int.fetchOne(db, sql: "SELECT COALESCE(SUM(byteSize), 0) FROM rawBatch") ?? 0)
                : 0
            return StorageStats(decodedRows: decodedRows, rawBatches: rawBatches, rawBytes: rawBytes)
        }
    }

    internal func writeProbeForTest() throws {
        try dbQueue.write { db in
            try db.execute(sql: "CREATE TABLE __noop_local_access_write_probe(id INTEGER)")
        }
    }

    internal func isReadOnlyForTest() throws -> Bool {
        try dbQueue.read { db in db.configuration.readonly }
    }

    private func validateSchema() throws {
        if !tableNames.contains("grdb_migrations"),
           tableNames.contains("device") || tableNames.contains("hrSample") {
            throw LocalAccessError.databaseUnavailable(
                "This looks like a NOOP-like SQLite file without GRDB migration metadata. Open NOOP to repair it before using local access."
            )
        }
    }
}

public final class NoopDataAccess {
    private let store: ReadonlyNoopStore
    private let deviceId: String
    private var computedDeviceId: String { deviceId + "-noop" }

    public init(store: ReadonlyNoopStore, deviceId: String = "my-whoop") {
        self.store = store
        self.deviceId = deviceId
    }

    public static func open(configuration: LocalAccessConfiguration = .environment()) throws -> NoopDataAccess {
        let path = try DatabasePathResolver.resolve(configuration: configuration)
        return try NoopDataAccess(store: ReadonlyNoopStore(path: path), deviceId: configuration.deviceID)
    }

    public func healthSnapshot(days: Int) throws -> JSONValue {
        let (fromDay, toDay) = dayRange(days: days)
        let daily = try mergedDaily(from: fromDay, to: toDay)
        let apple = try store.appleDaily(deviceId: "apple-health", from: fromDay, to: toDay)
        let latestHR = try store.latestHRSampleTs(deviceId: deviceId)

        let logical = logicalDayKey(Date())
        let displayed = daily.last(where: { $0.row.day == logical }) ?? daily.last

        return .object([
            "generatedAt": .string(iso(Date())),
            "logicalToday": .string(logical),
            "sources": Self.sources(),
            "freshness": freshnessPayload(latestHR: latestHR, apple: apple, daily: daily),
            "today": displayed.map { dailyJSON($0.row, source: $0.source) } ?? .null,
            "recentDays": .array(daily.suffix(days).map { dailyJSON($0.row, source: $0.source) }),
            "appleDaily": .array(apple.map(appleDailyJSON)),
        ])
    }

    public func metricSeries(
        key: String,
        source: String,
        days: Int,
        fromDay explicitFrom: String?,
        toDay explicitTo: String?,
        limit: Int
    ) throws -> JSONValue {
        let defaultRange = dayRange(days: days)
        let fromDay = explicitFrom ?? defaultRange.from
        let toDay = explicitTo ?? defaultRange.to
        let candidates = Self.sourceCandidates(forKey: key, preferredSource: source, actualWhoopSource: deviceId)
        var mergedByDay: [String: JSONValue] = [:]
        var usedSources: [String] = []

        for candidate in candidates {
            let rows = try store.metricSeries(deviceId: candidate.source, key: candidate.key, from: fromDay, to: toDay)
            if !rows.isEmpty { usedSources.append(candidate.source) }
            for row in rows where mergedByDay[row.day] == nil {
                mergedByDay[row.day] = .object([
                    "day": .string(row.day),
                    "key": .string(row.key),
                    "value": .double(row.value),
                    "source": .string(candidate.source),
                    "sourceKey": .string(candidate.key),
                ])
            }
        }

        let points = mergedByDay.keys.sorted().compactMap { mergedByDay[$0] }
        let boundedPoints = Array(points.suffix(limit))
        return .object([
            "key": .string(key),
            "requestedSource": .string(source),
            "range": .object(["from": .string(fromDay), "to": .string(toDay)]),
            "resolution": .object([
                "candidates": .array(candidates.map { .object(["source": .string($0.source), "key": .string($0.key)]) }),
                "usedSources": .array(orderedUnique(usedSources).map { .string($0) }),
            ]),
            "returned": .int(boundedPoints.count),
            "points": .array(boundedPoints),
        ])
    }

    public func freshness() throws -> JSONValue {
        let latestHR = try store.latestHRSampleTs(deviceId: deviceId)
        let latestRR = try store.latestRRIntervalTs(deviceId: deviceId)
        let latestEvent = try store.latestEventTs(deviceId: deviceId)
        let latestSleep = try store.latestSleepSessionTs(deviceId: deviceId)
        let latestWorkout = try store.latestWorkoutTs(deviceId: deviceId)
        let stats = try store.storageStats()
        let now = Date()
        let (fromDay, toDay) = dayRange(days: 4000)
        let importedDaily = try store.dailyMetrics(deviceId: deviceId, from: fromDay, to: toDay)
        let computedDaily = try store.dailyMetrics(deviceId: computedDeviceId, from: fromDay, to: toDay)
        let appleDaily = try store.appleDaily(deviceId: "apple-health", from: fromDay, to: toDay)
        let importedKeys = try store.metricKeys(deviceId: deviceId)
        let computedKeys = try store.metricKeys(deviceId: computedDeviceId)
        let appleKeys = try store.metricKeys(deviceId: "apple-health")

        return .object([
            "generatedAt": .string(iso(now)),
            "deviceId": .string(deviceId),
            "computedDeviceId": .string(computedDeviceId),
            "latestHeartRateSample": timestampJSON(latestHR, now: now),
            "latestRrInterval": timestampJSON(latestRR, now: now),
            "latestEvent": timestampJSON(latestEvent, now: now),
            "latestSleepSession": timestampJSON(latestSleep, now: now),
            "latestWorkout": timestampJSON(latestWorkout, now: now),
            "storage": .object([
                "decodedRows": .int(stats.decodedRows),
                "rawBatches": .int(stats.rawBatches),
                "rawBytes": .int(stats.rawBytes),
            ]),
            "coverage": .object([
                "dailyImported": coverageJSON(importedDaily.map(\.day)),
                "dailyComputed": coverageJSON(computedDaily.map(\.day)),
                "appleDaily": coverageJSON(appleDaily.map(\.day)),
            ]),
            "metricKeys": .object([
                deviceId: .array(importedKeys.map { .string($0) }),
                computedDeviceId: .array(computedKeys.map { .string($0) }),
                "apple-health": .array(appleKeys.map { .string($0) }),
            ]),
        ])
    }

    public func sleepSummary(days: Int, includeMotion: Bool = false, includeSleepState: Bool = false, includeStartAdjusted: Bool = false) throws -> JSONValue {
        let (fromTs, toTs) = timestampRange(days: days)
        let imported = try store.sleepSessions(deviceId: deviceId, from: fromTs, to: toTs, limit: 5000)
        let computed = try store.sleepSessions(deviceId: computedDeviceId, from: fromTs, to: toTs, limit: 5000)
        let merged = mergeSleep(imported: imported, computed: computed)
        let durations = merged.map { Double(max(0, $0.endTs - $0.startTs)) / 60.0 }
        let efficiencies = merged.compactMap(\.efficiency)

        return .object([
            "range": .object(["fromTs": .int(fromTs), "toTs": .int(toTs), "days": .int(days)]),
            "count": .int(merged.count),
            "averageDurationMin": optionalDouble(mean(durations)),
            "averageEfficiency": optionalDouble(mean(efficiencies)),
            "sessions": .array(merged.suffix(200).map { sleepJSON($0, includeMotion: includeMotion, includeSleepState: includeSleepState, includeStartAdjusted: includeStartAdjusted) }),
        ])
    }

    public func sleepStages(days: Int, limit: Int, maxPoints: Int) throws -> JSONValue {
        let (fromTs, toTs) = timestampRange(days: days)
        let imported = try store.sleepSessions(deviceId: deviceId, from: fromTs, to: toTs, limit: 5000)
        let computed = try store.sleepSessions(deviceId: computedDeviceId, from: fromTs, to: toTs, limit: 5000)
        let merged = mergeSleep(imported: imported, computed: computed)
        let truncatedSessions = merged.count > limit
        let window = Array(merged.suffix(limit))
        var anyStageTruncation = false
        let sessions: [JSONValue] = window.map { row in
            let decoded = decodeSleepHypnogram(
                stagesJSON: row.stagesJSON,
                sessionStart: row.startTs,
                sessionEnd: row.endTs,
                maxPoints: maxPoints
            )
            if decoded.truncated { anyStageTruncation = true }
            return .object([
                "startTs": .int(row.startTs),
                "endTs": .int(row.endTs),
                "start": .string(iso(Date(timeIntervalSince1970: TimeInterval(row.startTs)))),
                "end": .string(iso(Date(timeIntervalSince1970: TimeInterval(row.endTs)))),
                "durationMin": .double(Double(max(0, row.endTs - row.startTs)) / 60.0),
                "hasStages": .bool(row.stagesJSON != nil),
                "shape": .string(decoded.shape),
                "truncated": .bool(decoded.truncated),
                "minutes": decoded.minutesJSON,
                "stages": .array(decoded.segments.map { seg in
                    .object([
                        "start": .int(seg.start),
                        "end": .int(seg.end),
                        "stage": .string(seg.stage),
                    ])
                }),
            ])
        }

        return .object([
            "range": .object(["fromTs": .int(fromTs), "toTs": .int(toTs), "days": .int(days)]),
            "count": .int(merged.count),
            "returned": .int(window.count),
            "truncated": .bool(truncatedSessions || anyStageTruncation),
            "sessions": .array(sessions),
        ])
    }

    public func workoutSummary(days: Int, includeZones: Bool = false, includeNotes: Bool = false) throws -> JSONValue {
        let (fromTs, toTs) = timestampRange(days: days)
        let imported = try store.workouts(deviceId: deviceId, from: fromTs, to: toTs, limit: 5000)
        let apple = try store.workouts(deviceId: "apple-health", from: fromTs, to: toTs, limit: 5000)
        let computed = try store.workouts(deviceId: computedDeviceId, from: fromTs, to: toTs, limit: 5000)
        let rows = (imported + apple + computed).sorted { $0.startTs < $1.startTs }
        let durationMin = rows.reduce(0.0) { total, row in
            total + ((row.durationS ?? Double(max(0, row.endTs - row.startTs))) / 60.0)
        }
        let calories = rows.compactMap(\.energyKcal).reduce(0, +)
        let strain = rows.compactMap(\.strain).reduce(0, +)

        return .object([
            "range": .object(["fromTs": .int(fromTs), "toTs": .int(toTs), "days": .int(days)]),
            "count": .int(rows.count),
            "totalDurationMin": .double(durationMin),
            "totalEnergyKcal": .double(calories),
            "totalStrain": .double(strain),
            "workouts": .array(rows.suffix(300).map { workoutJSON($0, includeZones: includeZones, includeNotes: includeNotes) }),
        ])
    }

    public func hrSeries(
        hours: Int,
        fromTs explicitFrom: Int?,
        toTs explicitTo: Int?,
        bucketSeconds: Int,
        limit: Int,
        deviceId overrideDeviceId: String?
    ) throws -> JSONValue {
        if (explicitFrom == nil) != (explicitTo == nil) {
            throw LocalAccessError.invalidParams("hr_series requires both from_ts and to_ts")
        }

        let now = Int(Date().timeIntervalSince1970)
        let fromTs: Int
        let toTs: Int
        if let explicitFrom, let explicitTo {
            fromTs = explicitFrom
            toTs = explicitTo
        } else {
            fromTs = now - hours * 3_600
            toTs = now
        }

        let resolvedDeviceId = overrideDeviceId ?? deviceId
        let buckets = try store.hrBuckets(
            deviceId: resolvedDeviceId,
            from: fromTs,
            to: toTs,
            bucketSeconds: bucketSeconds
        )
        let truncated = buckets.count > limit
        let points = Array(buckets.suffix(limit))
        return .object([
            "range": .object([
                "fromTs": .int(fromTs),
                "toTs": .int(toTs),
                "hours": .int(hours),
            ]),
            "bucketSeconds": .int(bucketSeconds),
            "returned": .int(points.count),
            "truncated": .bool(truncated),
            "points": .array(points.map { row in
                .object([
                    "ts": .int(row.ts),
                    "iso": .string(iso(Date(timeIntervalSince1970: TimeInterval(row.ts)))),
                    "bpm": .double(row.bpm),
                ])
            }),
        ])
    }

    public func spo2Series(
        hours: Int,
        fromTs explicitFrom: Int?,
        toTs explicitTo: Int?,
        bucketSeconds: Int,
        limit: Int,
        deviceId overrideDeviceId: String?
    ) throws -> JSONValue {
        if (explicitFrom == nil) != (explicitTo == nil) {
            throw LocalAccessError.invalidParams("spo2_series requires both from_ts and to_ts")
        }

        let now = Int(Date().timeIntervalSince1970)
        let fromTs: Int
        let toTs: Int
        if let explicitFrom, let explicitTo {
            fromTs = explicitFrom
            toTs = explicitTo
        } else {
            fromTs = now - hours * 3_600
            toTs = now
        }

        let resolvedDeviceId = overrideDeviceId ?? deviceId
        let buckets = try store.spo2Buckets(
            deviceId: resolvedDeviceId,
            from: fromTs,
            to: toTs,
            bucketSeconds: bucketSeconds
        )
        let truncated = buckets.count > limit
        let points = Array(buckets.suffix(limit))
        return .object([
            "range": .object([
                "fromTs": .int(fromTs),
                "toTs": .int(toTs),
                "hours": .int(hours),
            ]),
            "bucketSeconds": .int(bucketSeconds),
            "returned": .int(points.count),
            "truncated": .bool(truncated),
            "points": .array(points.map { row in
                .object([
                    "ts": .int(row.ts),
                    "iso": .string(iso(Date(timeIntervalSince1970: TimeInterval(row.ts)))),
                    "red": .double(row.red),
                    "ir": .double(row.ir),
                ])
            }),
        ])
    }

    public func skinTempSeries(
        hours: Int,
        fromTs explicitFrom: Int?,
        toTs explicitTo: Int?,
        bucketSeconds: Int,
        limit: Int,
        deviceId overrideDeviceId: String?
    ) throws -> JSONValue {
        if (explicitFrom == nil) != (explicitTo == nil) {
            throw LocalAccessError.invalidParams("skin_temp_series requires both from_ts and to_ts")
        }

        let now = Int(Date().timeIntervalSince1970)
        let fromTs: Int
        let toTs: Int
        if let explicitFrom, let explicitTo {
            fromTs = explicitFrom
            toTs = explicitTo
        } else {
            fromTs = now - hours * 3_600
            toTs = now
        }

        let resolvedDeviceId = overrideDeviceId ?? deviceId
        let buckets = try store.skinTempBuckets(
            deviceId: resolvedDeviceId,
            from: fromTs,
            to: toTs,
            bucketSeconds: bucketSeconds
        )
        let truncated = buckets.count > limit
        let points = Array(buckets.suffix(limit))
        return .object([
            "range": .object([
                "fromTs": .int(fromTs),
                "toTs": .int(toTs),
                "hours": .int(hours),
            ]),
            "bucketSeconds": .int(bucketSeconds),
            "returned": .int(points.count),
            "truncated": .bool(truncated),
            "points": .array(points.map { row in
                .object([
                    "ts": .int(row.ts),
                    "iso": .string(iso(Date(timeIntervalSince1970: TimeInterval(row.ts)))),
                    "raw": .double(row.raw),
                ])
            }),
        ])
    }

    public func eventSeries(
        kind: String,
        hours: Int,
        fromTs explicitFrom: Int?,
        toTs explicitTo: Int?,
        limit: Int,
        deviceId overrideDeviceId: String?
    ) throws -> JSONValue {
        if (explicitFrom == nil) != (explicitTo == nil) {
            throw LocalAccessError.invalidParams("event_series requires both from_ts and to_ts")
        }

        let now = Int(Date().timeIntervalSince1970)
        let fromTs: Int
        let toTs: Int
        if let explicitFrom, let explicitTo {
            fromTs = explicitFrom
            toTs = explicitTo
        } else {
            fromTs = now - hours * 3_600
            toTs = now
        }

        let resolvedDeviceId = overrideDeviceId ?? deviceId
        let rows = try store.events(
            deviceId: resolvedDeviceId,
            kind: kind,
            from: fromTs,
            to: toTs,
            limit: limit + 1
        )
        let truncated = rows.count > limit
        let points = Array(rows.suffix(limit))
        return .object([
            "kind": .string(kind),
            "range": .object([
                "fromTs": .int(fromTs),
                "toTs": .int(toTs),
                "hours": .int(hours),
            ]),
            "returned": .int(points.count),
            "truncated": .bool(truncated),
            "points": .array(points.map { row in
                .object([
                    "ts": .int(row.ts),
                    "iso": .string(iso(Date(timeIntervalSince1970: TimeInterval(row.ts)))),
                    "kind": .string(row.kind),
                    "payload": parseEventPayloadJSON(row.payloadJSON),
                ])
            }),
        ])
    }

    public func rrSeries(
        hours: Int,
        fromTs explicitFrom: Int?,
        toTs explicitTo: Int?,
        limit: Int,
        deviceId overrideDeviceId: String?
    ) throws -> JSONValue {
        if (explicitFrom == nil) != (explicitTo == nil) {
            throw LocalAccessError.invalidParams("rr_series requires both from_ts and to_ts")
        }

        let now = Int(Date().timeIntervalSince1970)
        let fromTs: Int
        let toTs: Int
        if let explicitFrom, let explicitTo {
            fromTs = explicitFrom
            toTs = explicitTo
        } else {
            fromTs = now - hours * 3_600
            toTs = now
        }

        let resolvedDeviceId = overrideDeviceId ?? deviceId
        let rows = try store.rrIntervals(
            deviceId: resolvedDeviceId,
            from: fromTs,
            to: toTs,
            limit: limit + 1
        )
        let truncated = rows.count > limit
        let points = Array(rows.suffix(limit))
        return .object([
            "range": .object([
                "fromTs": .int(fromTs),
                "toTs": .int(toTs),
                "hours": .int(hours),
            ]),
            "returned": .int(points.count),
            "truncated": .bool(truncated),
            "points": .array(points.map { row in
                var object: [String: JSONValue] = [
                    "ts": .int(row.ts),
                    "iso": .string(iso(Date(timeIntervalSince1970: TimeInterval(row.ts)))),
                    "rrMs": .int(row.rrMs),
                ]
                if let seq = row.seq { object["seq"] = .int(seq) }
                if let ord = row.ord { object["ord"] = .int(ord) }
                return .object(object)
            }),
        ])
    }

    public static func metricCatalog() -> JSONValue {
        .object([
            "sources": sources(),
            "keys": .array([
                "avg_hr", "max_hr", "energy_kcal", "recovery", "hrv", "rhr", "resp_rate",
                "spo2", "skin_temp", "sleep_performance", "sleep_total_min", "sleep_efficiency",
                "sleep_deep_min", "sleep_rem_min", "sleep_light_min", "sleep_need_min",
                "sleep_debt_min", "strain", "steps", "active_kcal", "weight", "vo2max",
                "body_fat", "lean_mass", "bmi", "stress", "mood", "calories_in",
                "protein_g", "carbs_g", "fat_g",
            ].map { .string($0) }),
            "resolutionRule": .string("my-whoop resolves imported my-whoop first, then my-whoop-noop computed rows, then compatible Apple Health fill-ins for rhr/hrv/spo2/resp_rate."),
        ])
    }

    public static func sources() -> JSONValue {
        .object([
            "whoopImported": .string("my-whoop"),
            "noopComputed": .string("my-whoop-noop"),
            "appleHealth": .string("apple-health"),
            "nutrition": .string("nutrition-csv"),
            "mood": .string("noop-mood"),
            "journal": .string("noop-journal"),
        ])
    }

    private func mergedDaily(from: String, to: String) throws -> [(row: DailyMetricRow, source: String)] {
        var byDay: [String: (DailyMetricRow, String)] = [:]
        for row in try store.dailyMetrics(deviceId: computedDeviceId, from: from, to: to) {
            byDay[row.day] = (row, computedDeviceId)
        }
        for row in try store.dailyMetrics(deviceId: deviceId, from: from, to: to) {
            byDay[row.day] = (row, deviceId)
        }
        return byDay.values.sorted { $0.0.day < $1.0.day }
    }

    private func mergeSleep(imported: [SleepSessionRow], computed: [SleepSessionRow]) -> [SleepSessionRow] {
        var importedDays = Set<String>()
        for session in imported {
            importedDays.insert(dayString(Date(timeIntervalSince1970: TimeInterval(session.endTs))))
        }
        let computedKept = computed.filter {
            !importedDays.contains(dayString(Date(timeIntervalSince1970: TimeInterval($0.endTs))))
        }
        return (imported + computedKept).sorted { $0.startTs < $1.startTs }
    }

    private func freshnessPayload(latestHR: Int?, apple: [AppleDailyRow], daily: [(row: DailyMetricRow, source: String)]) -> JSONValue {
        .object([
            "latestHeartRateSample": timestampJSON(latestHR, now: Date()),
            "latestDailyMetricDay": daily.last.map { .string($0.row.day) } ?? .null,
            "latestAppleHealthDay": apple.last.map { .string($0.day) } ?? .null,
            "dailyRows": .int(daily.count),
            "appleDailyRows": .int(apple.count),
        ])
    }

    private static func sourceCandidates(forKey key: String, preferredSource: String, actualWhoopSource: String) -> [MetricSourceCandidate] {
        if preferredSource == "my-whoop" || preferredSource == actualWhoopSource {
            var candidates = [
                MetricSourceCandidate(source: actualWhoopSource, key: key),
                MetricSourceCandidate(source: actualWhoopSource + "-noop", key: key),
            ]
            if let appleKey = appleCompatibleKey(forWhoopKey: key) {
                candidates.append(MetricSourceCandidate(source: "apple-health", key: appleKey))
            }
            return orderedUnique(candidates)
        }
        return [MetricSourceCandidate(source: preferredSource, key: key)]
    }

    private static func appleCompatibleKey(forWhoopKey key: String) -> String? {
        switch key {
        case "rhr":
            return "resting_hr"
        case "hrv", "spo2", "resp_rate":
            return key
        default:
            return nil
        }
    }
}

private struct MetricSourceCandidate: Hashable {
    let source: String
    let key: String
}

private func dailyJSON(_ row: DailyMetricRow, source: String) -> JSONValue {
    .object([
        "day": .string(row.day),
        "source": .string(source),
        "totalSleepMin": optionalDouble(row.totalSleepMin),
        "efficiency": optionalDouble(row.efficiency),
        "deepMin": optionalDouble(row.deepMin),
        "remMin": optionalDouble(row.remMin),
        "lightMin": optionalDouble(row.lightMin),
        "disturbances": optionalInt(row.disturbances),
        "restingHr": optionalInt(row.restingHr),
        "avgHrv": optionalDouble(row.avgHrv),
        "recovery": optionalDouble(row.recovery),
        "strain": optionalDouble(row.strain),
        "exerciseCount": optionalInt(row.exerciseCount),
        "spo2Pct": optionalDouble(row.spo2Pct),
        "skinTempDevC": optionalDouble(row.skinTempDevC),
        "respRateBpm": optionalDouble(row.respRateBpm),
        "steps": optionalInt(row.steps),
        "activeKcalEst": optionalDouble(row.activeKcalEst),
    ])
}

private func appleDailyJSON(_ row: AppleDailyRow) -> JSONValue {
    .object([
        "day": .string(row.day),
        "steps": optionalInt(row.steps),
        "activeKcal": optionalDouble(row.activeKcal),
        "basalKcal": optionalDouble(row.basalKcal),
        "vo2max": optionalDouble(row.vo2max),
        "avgHr": optionalInt(row.avgHr),
        "maxHr": optionalInt(row.maxHr),
        "walkingHr": optionalInt(row.walkingHr),
        "weightKg": optionalDouble(row.weightKg),
    ])
}

private func sleepJSON(_ row: SleepSessionRow, includeMotion: Bool = false, includeSleepState: Bool = false, includeStartAdjusted: Bool = false) -> JSONValue {
    var object: [String: JSONValue] = [
        "startTs": .int(row.startTs),
        "endTs": .int(row.endTs),
        "start": .string(iso(Date(timeIntervalSince1970: TimeInterval(row.startTs)))),
        "end": .string(iso(Date(timeIntervalSince1970: TimeInterval(row.endTs)))),
        "durationMin": .double(Double(max(0, row.endTs - row.startTs)) / 60.0),
        "efficiency": optionalDouble(row.efficiency),
        "restingHr": optionalInt(row.restingHr),
        "avgHrv": optionalDouble(row.avgHrv),
        "hasStages": .bool(row.stagesJSON != nil),
    ]
    if includeMotion {
        let decoded = decodeSleepMotion(row.motionJSON)
        object["motion"] = .object([
            "truncated": .bool(decoded.truncated),
            "payload": decoded.payload,
        ])
    }
    if includeSleepState {
        let decoded = decodeSleepState(row.sleepStateJSON)
        object["sleepState"] = .object([
            "truncated": .bool(decoded.truncated),
            "payload": decoded.payload,
        ])
    }
    if includeStartAdjusted, let startTsAdjusted = row.startTsAdjusted {
        object["startTsAdjusted"] = .int(startTsAdjusted)
    }
    return .object(object)
}

private func workoutJSON(_ row: WorkoutRow, includeZones: Bool = false, includeNotes: Bool = false) -> JSONValue {
    var object: [String: JSONValue] = [
        "startTs": .int(row.startTs),
        "endTs": .int(row.endTs),
        "start": .string(iso(Date(timeIntervalSince1970: TimeInterval(row.startTs)))),
        "end": .string(iso(Date(timeIntervalSince1970: TimeInterval(row.endTs)))),
        "sport": .string(row.sport),
        "source": .string(row.source),
        "durationS": optionalDouble(row.durationS),
        "energyKcal": optionalDouble(row.energyKcal),
        "avgHr": optionalInt(row.avgHr),
        "maxHr": optionalInt(row.maxHr),
        "strain": optionalDouble(row.strain),
        "distanceM": optionalDouble(row.distanceM),
        "hasZones": .bool(row.zonesJSON != nil),
        "hasNotes": .bool(row.notes != nil),
    ]
    if includeZones {
        let decoded = decodeWorkoutZones(row.zonesJSON)
        object["zones"] = .object([
            "truncated": .bool(decoded.truncated),
            "payload": decoded.payload,
        ])
    }
    if includeNotes {
        let decoded = boundWorkoutNotes(row.notes)
        object["notes"] = .object([
            "truncated": .bool(decoded.truncated),
            "payload": decoded.payload,
        ])
    }
    return .object(object)
}

private func timestampJSON(_ ts: Int?, now: Date) -> JSONValue {
    guard let ts else { return .null }
    let date = Date(timeIntervalSince1970: TimeInterval(ts))
    return .object([
        "ts": .int(ts),
        "iso": .string(iso(date)),
        "ageSeconds": .int(max(0, Int(now.timeIntervalSince(date)))),
    ])
}

private func coverageJSON(_ days: [String]) -> JSONValue {
    .object([
        "count": .int(days.count),
        "firstDay": days.min().map { .string($0) } ?? .null,
        "lastDay": days.max().map { .string($0) } ?? .null,
    ])
}

private func optionalDouble(_ value: Double?) -> JSONValue {
    value.map { .double($0) } ?? .null
}

private func optionalInt(_ value: Int?) -> JSONValue {
    value.map { .int($0) } ?? .null
}

private struct SleepHypnogramDecode {
    let shape: String
    let segments: [(start: Int, end: Int, stage: String)]
    let minutesJSON: JSONValue
    let truncated: Bool
}

private func decodeSleepHypnogram(
    stagesJSON: String?,
    sessionStart: Int,
    sessionEnd: Int,
    maxPoints: Int
) -> SleepHypnogramDecode {
    let empty = SleepHypnogramDecode(shape: "none", segments: [], minutesJSON: .null, truncated: false)
    guard let stagesJSON,
          let data = stagesJSON.data(using: .utf8),
          let object = try? JSONSerialization.jsonObject(with: data)
    else { return empty }

    if let dict = object as? [String: Any] {
        return SleepHypnogramDecode(
            shape: "minutes",
            segments: [],
            minutesJSON: minutesObject(from: dict),
            truncated: false
        )
    }

    guard let array = object as? [[String: Any]] else { return empty }

    let missingTiming = array.contains { jsonInt($0["start"]) == nil || jsonInt($0["end"]) == nil }
    if missingTiming {
        var totals = SleepStageMinutes()
        for item in array {
            guard let stage = normalizeSleepStage(item["stage"] as? String) else { continue }
            let minutes = jsonDouble(item["min"]) ?? jsonDouble(item["minutes"]) ?? 0
            totals.add(stage: stage, minutes: minutes)
        }
        return SleepHypnogramDecode(
            shape: "minutes",
            segments: [],
            minutesJSON: totals.json,
            truncated: false
        )
    }

    var segments: [(start: Int, end: Int, stage: String)] = []
    for item in array {
        guard let rawStart = jsonInt(item["start"]),
              let rawEnd = jsonInt(item["end"]),
              let stage = normalizeSleepStage(item["stage"] as? String)
        else { continue }
        let start = max(rawStart, sessionStart)
        let end = min(rawEnd, sessionEnd)
        guard end > start else { continue }
        segments.append((start, end, stage))
    }
    segments.sort { $0.start < $1.start }
    let truncated = segments.count > maxPoints
    let kept = Array(segments.prefix(maxPoints))
    var totals = SleepStageMinutes()
    for seg in kept {
        totals.add(stage: seg.stage, minutes: Double(seg.end - seg.start) / 60.0)
    }
    return SleepHypnogramDecode(
        shape: "segments",
        segments: kept,
        minutesJSON: totals.json,
        truncated: truncated
    )
}

private struct SleepStageMinutes {
    var light = 0.0
    var deep = 0.0
    var rem = 0.0
    var awake = 0.0

    mutating func add(stage: String, minutes: Double) {
        switch stage {
        case "light": light += minutes
        case "deep": deep += minutes
        case "rem": rem += minutes
        case "awake": awake += minutes
        default: break
        }
    }

    var json: JSONValue {
        .object([
            "light": .double(light),
            "deep": .double(deep),
            "rem": .double(rem),
            "awake": .double(awake),
        ])
    }
}

private func minutesObject(from dict: [String: Any]) -> JSONValue {
    var totals = SleepStageMinutes()
    for (key, value) in dict {
        guard let stage = normalizeSleepStage(key), let minutes = jsonDouble(value) else { continue }
        totals.add(stage: stage, minutes: minutes)
    }
    return totals.json
}

private func normalizeSleepStage(_ raw: String?) -> String? {
    switch raw?.lowercased() {
    case "wake", "awake": return "awake"
    case "light": return "light"
    case "deep": return "deep"
    case "rem": return "rem"
    default: return nil
    }
}

private func jsonInt(_ value: Any?) -> Int? {
    switch value {
    case let value as Int: return value
    case let value as Double: return Int(value)
    case let value as NSNumber: return value.intValue
    case let value as String: return Int(value)
    default: return nil
    }
}

private func jsonDouble(_ value: Any?) -> Double? {
    switch value {
    case let value as Double: return value
    case let value as Int: return Double(value)
    case let value as NSNumber: return value.doubleValue
    case let value as String: return Double(value)
    default: return nil
    }
}


private let workoutZonesMaxEntries = 32
private let workoutZonesMaxStringChars = 2048
private let workoutNotesMaxStringChars = 2048
private let sleepMotionMaxEntries = 32
private let sleepMotionMaxStringChars = 2048
private let sleepStateMaxEntries = 32
private let sleepStateMaxStringChars = 2048

private struct WorkoutZonesDecode {
    let payload: JSONValue
    let truncated: Bool
}

private struct WorkoutNotesDecode {
    let payload: JSONValue
    let truncated: Bool
}

private func boundWorkoutNotes(_ notes: String?) -> WorkoutNotesDecode {
    guard let notes else {
        return WorkoutNotesDecode(payload: .null, truncated: false)
    }
    let truncated = notes.count > workoutNotesMaxStringChars
    let kept = truncated ? String(notes.prefix(workoutNotesMaxStringChars)) : notes
    return WorkoutNotesDecode(payload: .string(kept), truncated: truncated)
}

private func decodeWorkoutZones(_ zonesJSON: String?) -> WorkoutZonesDecode {
    guard let zonesJSON else {
        return WorkoutZonesDecode(payload: .null, truncated: false)
    }
    if let data = zonesJSON.data(using: .utf8),
       let decoded = try? JSONDecoder().decode(JSONValue.self, from: data) {
        let bounded = boundWorkoutZonesJSON(decoded)
        return WorkoutZonesDecode(payload: bounded.payload, truncated: bounded.truncated)
    }
    let truncated = zonesJSON.count > workoutZonesMaxStringChars
    let raw = truncated ? String(zonesJSON.prefix(workoutZonesMaxStringChars)) : zonesJSON
    return WorkoutZonesDecode(payload: .string(raw), truncated: truncated)
}

private func boundWorkoutZonesJSON(_ value: JSONValue) -> WorkoutZonesDecode {
    switch value {
    case .array(let items):
        let truncated = items.count > workoutZonesMaxEntries
        return WorkoutZonesDecode(
            payload: .array(Array(items.prefix(workoutZonesMaxEntries))),
            truncated: truncated
        )
    case .object(let object):
        let keys = object.keys.sorted()
        let truncated = keys.count > workoutZonesMaxEntries
        var kept: [String: JSONValue] = [:]
        for key in keys.prefix(workoutZonesMaxEntries) {
            kept[key] = object[key]
        }
        return WorkoutZonesDecode(payload: .object(kept), truncated: truncated)
    case .string(let raw):
        let truncated = raw.count > workoutZonesMaxStringChars
        let kept = truncated ? String(raw.prefix(workoutZonesMaxStringChars)) : raw
        return WorkoutZonesDecode(payload: .string(kept), truncated: truncated)
    default:
        return WorkoutZonesDecode(payload: value, truncated: false)
    }
}


private struct SleepMotionDecode {
    let payload: JSONValue
    let truncated: Bool
}

private func decodeSleepMotion(_ motionJSON: String?) -> SleepMotionDecode {
    guard let motionJSON else {
        return SleepMotionDecode(payload: .null, truncated: false)
    }
    if let data = motionJSON.data(using: .utf8),
       let decoded = try? JSONDecoder().decode(JSONValue.self, from: data) {
        let bounded = boundSleepMotionJSON(decoded)
        return SleepMotionDecode(payload: bounded.payload, truncated: bounded.truncated)
    }
    let truncated = motionJSON.count > sleepMotionMaxStringChars
    let raw = truncated ? String(motionJSON.prefix(sleepMotionMaxStringChars)) : motionJSON
    return SleepMotionDecode(payload: .string(raw), truncated: truncated)
}

private func boundSleepMotionJSON(_ value: JSONValue) -> SleepMotionDecode {
    switch value {
    case .array(let items):
        let truncated = items.count > sleepMotionMaxEntries
        return SleepMotionDecode(
            payload: .array(Array(items.prefix(sleepMotionMaxEntries))),
            truncated: truncated
        )
    case .object(let object):
        let keys = object.keys.sorted()
        let truncated = keys.count > sleepMotionMaxEntries
        var kept: [String: JSONValue] = [:]
        for key in keys.prefix(sleepMotionMaxEntries) {
            kept[key] = object[key]
        }
        return SleepMotionDecode(payload: .object(kept), truncated: truncated)
    case .string(let raw):
        let truncated = raw.count > sleepMotionMaxStringChars
        let kept = truncated ? String(raw.prefix(sleepMotionMaxStringChars)) : raw
        return SleepMotionDecode(payload: .string(kept), truncated: truncated)
    default:
        return SleepMotionDecode(payload: value, truncated: false)
    }
}

private struct SleepStateDecode {
    let payload: JSONValue
    let truncated: Bool
}

private func decodeSleepState(_ sleepStateJSON: String?) -> SleepStateDecode {
    guard let sleepStateJSON else {
        return SleepStateDecode(payload: .null, truncated: false)
    }
    if let data = sleepStateJSON.data(using: .utf8),
       let decoded = try? JSONDecoder().decode(JSONValue.self, from: data) {
        let bounded = boundSleepStateJSON(decoded)
        return SleepStateDecode(payload: bounded.payload, truncated: bounded.truncated)
    }
    let truncated = sleepStateJSON.count > sleepStateMaxStringChars
    let raw = truncated ? String(sleepStateJSON.prefix(sleepStateMaxStringChars)) : sleepStateJSON
    return SleepStateDecode(payload: .string(raw), truncated: truncated)
}

private func boundSleepStateJSON(_ value: JSONValue) -> SleepStateDecode {
    switch value {
    case .array(let items):
        let truncated = items.count > sleepStateMaxEntries
        return SleepStateDecode(
            payload: .array(Array(items.prefix(sleepStateMaxEntries))),
            truncated: truncated
        )
    case .object(let object):
        let keys = object.keys.sorted()
        let truncated = keys.count > sleepStateMaxEntries
        var kept: [String: JSONValue] = [:]
        for key in keys.prefix(sleepStateMaxEntries) {
            kept[key] = object[key]
        }
        return SleepStateDecode(payload: .object(kept), truncated: truncated)
    case .string(let raw):
        let truncated = raw.count > sleepStateMaxStringChars
        let kept = truncated ? String(raw.prefix(sleepStateMaxStringChars)) : raw
        return SleepStateDecode(payload: .string(kept), truncated: truncated)
    default:
        return SleepStateDecode(payload: value, truncated: false)
    }
}

private func parseEventPayloadJSON(_ raw: String) -> JSONValue {
    guard let data = raw.data(using: .utf8),
          let decoded = try? JSONDecoder().decode(JSONValue.self, from: data)
    else {
        return .string(raw)
    }
    return decoded
}

func boundedDays(_ value: JSONValue?, default defaultValue: Int, max maxValue: Int) -> Int {
    guard let raw = value?.intValue else { return defaultValue }
    return min(max(raw, 1), maxValue)
}

func boundedLimit(_ value: JSONValue?, default defaultValue: Int, max maxValue: Int) -> Int {
    guard let raw = value?.intValue else { return defaultValue }
    return min(max(raw, 1), maxValue)
}

func orderedUnique<T: Hashable>(_ values: [T]) -> [T] {
    var seen = Set<T>()
    var result: [T] = []
    for value in values where !seen.contains(value) {
        seen.insert(value)
        result.append(value)
    }
    return result
}

private func mean(_ values: [Double]) -> Double? {
    guard !values.isEmpty else { return nil }
    return values.reduce(0, +) / Double(values.count)
}

private func dayRange(days: Int) -> (from: String, to: String) {
    let now = Date()
    return (
        from: dayString(now.addingTimeInterval(-Double(max(1, days) - 1) * 86_400)),
        to: dayString(now.addingTimeInterval(86_400))
    )
}

private func timestampRange(days: Int) -> (from: Int, to: Int) {
    let now = Int(Date().timeIntervalSince1970)
    return (now - max(1, days) * 86_400, now + 86_400)
}

private func dayString(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter.string(from: date)
}

private func logicalDayKey(_ now: Date) -> String {
    dayString(now.addingTimeInterval(-4 * 3_600))
}

private func iso(_ date: Date) -> String {
    ISO8601DateFormatter().string(from: date)
}
