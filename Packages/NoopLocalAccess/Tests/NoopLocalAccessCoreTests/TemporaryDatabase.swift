import Foundation
import GRDB

enum TemporaryDatabase {
    static func emptyFileURL() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("NoopLocalAccessTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("whoop.sqlite")
        FileManager.default.createFile(atPath: url.path, contents: Data())
        return url
    }

    static func seeded() throws -> URL {
        let url = try emptyFileURL()
        let dbQueue = try DatabaseQueue(path: url.path)
        try dbQueue.write { db in
            try createSchema(db)
            try seed(db)
        }
        return url
    }

    static func foreignNoopLike() throws -> URL {
        let url = try emptyFileURL()
        let dbQueue = try DatabaseQueue(path: url.path)
        try dbQueue.write { db in
            try db.execute(sql: "CREATE TABLE device(id TEXT PRIMARY KEY)")
            try db.execute(sql: "CREATE TABLE hrSample(deviceId TEXT NOT NULL, ts INTEGER NOT NULL, bpm INTEGER NOT NULL, PRIMARY KEY(deviceId, ts))")
        }
        return url
    }

    private static func createSchema(_ db: Database) throws {
        try db.execute(sql: "CREATE TABLE grdb_migrations(identifier TEXT PRIMARY KEY)")
        try db.execute(sql: """
            CREATE TABLE dailyMetric(
                deviceId TEXT NOT NULL, day TEXT NOT NULL, totalSleepMin DOUBLE, efficiency DOUBLE,
                deepMin DOUBLE, remMin DOUBLE, lightMin DOUBLE, disturbances INTEGER,
                restingHr INTEGER, avgHrv DOUBLE, recovery DOUBLE, strain DOUBLE,
                exerciseCount INTEGER, spo2Pct DOUBLE, skinTempDevC DOUBLE, respRateBpm DOUBLE,
                steps INTEGER, activeKcalEst DOUBLE, PRIMARY KEY(deviceId, day)
            )
            """)
        try db.execute(sql: """
            CREATE TABLE metricSeries(
                deviceId TEXT NOT NULL, day TEXT NOT NULL, key TEXT NOT NULL, value DOUBLE NOT NULL,
                PRIMARY KEY(deviceId, day, key)
            )
            """)
        try db.execute(sql: """
            CREATE TABLE appleDaily(
                deviceId TEXT NOT NULL, day TEXT NOT NULL, steps INTEGER, activeKcal DOUBLE,
                basalKcal DOUBLE, vo2max DOUBLE, avgHr INTEGER, maxHr INTEGER,
                walkingHr INTEGER, weightKg DOUBLE, PRIMARY KEY(deviceId, day)
            )
            """)
        try db.execute(sql: """
            CREATE TABLE sleepSession(
                deviceId TEXT NOT NULL, startTs INTEGER NOT NULL, endTs INTEGER NOT NULL,
                efficiency DOUBLE, restingHr INTEGER, avgHrv DOUBLE, stagesJSON TEXT,
                PRIMARY KEY(deviceId, startTs)
            )
            """)
        try db.execute(sql: """
            CREATE TABLE workout(
                deviceId TEXT NOT NULL, startTs INTEGER NOT NULL, endTs INTEGER NOT NULL,
                sport TEXT NOT NULL, source TEXT NOT NULL, durationS DOUBLE, energyKcal DOUBLE,
                avgHr INTEGER, maxHr INTEGER, strain DOUBLE, distanceM DOUBLE, zonesJSON TEXT,
                notes TEXT, PRIMARY KEY(deviceId, startTs, sport)
            )
            """)
        try db.execute(sql: "CREATE TABLE hrSample(deviceId TEXT NOT NULL, ts INTEGER NOT NULL, bpm INTEGER NOT NULL, PRIMARY KEY(deviceId, ts))")
        try db.execute(sql: "CREATE TABLE ppgHrSample(deviceId TEXT NOT NULL, ts INTEGER NOT NULL, bpm DOUBLE NOT NULL, conf DOUBLE NOT NULL, PRIMARY KEY(deviceId, ts))")
        try db.execute(sql: "CREATE TABLE rrInterval(deviceId TEXT NOT NULL, ts INTEGER NOT NULL, rrMs INTEGER NOT NULL, PRIMARY KEY(deviceId, ts, rrMs))")
        try db.execute(sql: "CREATE TABLE rawBatch(batchId TEXT PRIMARY KEY, deviceId TEXT NOT NULL, byteSize INTEGER NOT NULL)")
    }

    private static func seed(_ db: Database) throws {
        try db.execute(sql: """
            INSERT INTO dailyMetric(deviceId, day, totalSleepMin, efficiency, restingHr, avgHrv, recovery, strain)
            VALUES
                ('my-whoop', '2026-06-10', 420, 91, 48, 72, 67, 12.5),
                ('my-whoop-noop', '2026-06-11', 410, 88, 50, 66, 61, 10.0)
            """)
        try db.execute(sql: """
            INSERT INTO metricSeries(deviceId, day, key, value)
            VALUES
                ('my-whoop', '2026-06-10', 'hrv', 72),
                ('my-whoop-noop', '2026-06-11', 'hrv', 66),
                ('apple-health', '2026-06-11', 'hrv', 64)
            """)
        try db.execute(sql: """
            INSERT INTO appleDaily(deviceId, day, steps, activeKcal, vo2max, avgHr, maxHr, weightKg)
            VALUES ('apple-health', '2026-06-11', 8000, 420, 47.2, 69, 151, 82.5)
            """)
        try db.execute(sql: """
            INSERT INTO sleepSession(deviceId, startTs, endTs, efficiency, restingHr, avgHrv)
            VALUES ('my-whoop', 1000, 2000, 91, 48, 72)
            """)
        try db.execute(sql: """
            INSERT INTO workout(deviceId, startTs, endTs, sport, source, durationS, energyKcal, avgHr, maxHr, strain)
            VALUES ('my-whoop', 3000, 4800, 'run', 'whoop', 1800, 310, 140, 171, 8.5)
            """)
        try db.execute(sql: "INSERT INTO hrSample(deviceId, ts, bpm) VALUES ('my-whoop', 100, 70), ('my-whoop', 101, 72)")
        try db.execute(sql: "INSERT INTO ppgHrSample(deviceId, ts, bpm, conf) VALUES ('my-whoop', 102, 73.2, 0.8)")
        try db.execute(sql: "INSERT INTO rrInterval(deviceId, ts, rrMs) VALUES ('my-whoop', 101, 850)")
        try db.execute(sql: "INSERT INTO rawBatch(batchId, deviceId, byteSize) VALUES ('batch-1', 'my-whoop', 12)")
    }

    static func withEvents() throws -> URL {
        let url = try seeded()
        let dbQueue = try DatabaseQueue(path: url.path)
        try dbQueue.write { db in
            try db.execute(sql: """
                CREATE TABLE event(
                    deviceId TEXT NOT NULL, ts INTEGER NOT NULL, kind TEXT NOT NULL,
                    payloadJSON TEXT NOT NULL, PRIMARY KEY(deviceId, ts, kind)
                )
                """)
            try db.execute(sql: """
                INSERT INTO event(deviceId, ts, kind, payloadJSON) VALUES
                    ('my-whoop', 103, 'TEST', '{"source":"fixture"}'),
                    ('other-device', 999, 'TEST', '{"source":"other"}')
                """)
        }
        return url
    }

    static func withRRQualityRows() throws -> URL {
        let url = try seeded()
        let dbQueue = try DatabaseQueue(path: url.path)
        try dbQueue.write { db in
            try db.execute(sql: "ALTER TABLE rrInterval ADD COLUMN srcChannel INTEGER")
            try db.execute(sql: "ALTER TABLE rrInterval ADD COLUMN tsSuspect INTEGER")
            try db.execute(sql: """
                INSERT INTO rrInterval(deviceId, ts, rrMs, srcChannel, tsSuspect) VALUES
                    ('my-whoop', 102, 810, 2, NULL),
                    ('my-whoop', 103, 820, 1, 1),
                    ('my-whoop', 104, 830, 3, 0),
                    ('my-whoop', 105, 840, 2, 0),
                    ('my-whoop', 106, 850, NULL, 1),
                    ('other-device', 999, 890, NULL, NULL)
                """)
        }
        return url
    }

    static func withoutRRInterval() throws -> URL {
        let url = try seeded()
        let dbQueue = try DatabaseQueue(path: url.path)
        try dbQueue.write { db in
            try db.execute(sql: "DROP TABLE rrInterval")
        }
        return url
    }

    static func withoutSleepSession() throws -> URL {
        let url = try seeded()
        let dbQueue = try DatabaseQueue(path: url.path)
        try dbQueue.write { db in
            try db.execute(sql: "DROP TABLE sleepSession")
        }
        return url
    }
}
