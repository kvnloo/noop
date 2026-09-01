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

    static func withSleepStages(now: Int = Int(Date().timeIntervalSince1970)) throws -> URL {
        let url = try seeded()
        let dbQueue = try DatabaseQueue(path: url.path)
        try dbQueue.write { db in
            let olderStart = now - 90_000
            let olderJSON = """
            [{"start":\(olderStart),"end":\(olderStart + 600),"stage":"light"},{"start":\(olderStart + 600),"end":\(olderStart + 1200),"stage":"deep"},{"start":\(olderStart + 1200),"end":\(olderStart + 1800),"stage":"wake"}]
            """
            try db.execute(
                sql: """
                INSERT INTO sleepSession(deviceId, startTs, endTs, efficiency, restingHr, avgHrv, stagesJSON)
                VALUES (?, ?, ?, 90, 49, 68, ?)
                """,
                arguments: ["my-whoop", olderStart, olderStart + 1800, olderJSON]
            )

            let newerStart = now - 3_600
            let newerJSON = """
            [{"start":\(newerStart),"end":\(newerStart + 300),"stage":"light"},{"start":\(newerStart + 300),"end":\(newerStart + 900),"stage":"deep"},{"start":\(newerStart + 900),"end":\(newerStart + 1500),"stage":"rem"},{"start":\(newerStart + 1500),"end":\(newerStart + 1800),"stage":"awake"}]
            """
            try db.execute(
                sql: """
                INSERT INTO sleepSession(deviceId, startTs, endTs, efficiency, restingHr, avgHrv, stagesJSON)
                VALUES (?, ?, ?, 92, 48, 71, ?)
                """,
                arguments: ["my-whoop", newerStart, newerStart + 1800, newerJSON]
            )

            let importedStart = now - 180_000
            try db.execute(
                sql: """
                INSERT INTO sleepSession(deviceId, startTs, endTs, efficiency, restingHr, avgHrv, stagesJSON)
                VALUES (?, ?, ?, 88, 51, 60, ?)
                """,
                arguments: ["my-whoop", importedStart, importedStart + 25_200, "{\"light\":100,\"deep\":50,\"rem\":40,\"awake\":10}"]
            )
        }
        return url
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
                    ('my-whoop', 100, 'ALPHA', '{"ok":true,"n":1}'),
                    ('my-whoop', 101, 'ALPHA', 'not-json'),
                    ('my-whoop', 102, 'ALPHA', '{"later":true}'),
                    ('my-whoop', 103, 'BETA', '{"other":1}'),
                    ('other-device', 102, 'ALPHA', '{"skip":1}')
                """)
        }
        return url
    }

    static func withRRIntervals() throws -> URL {
        let url = try seeded()
        let dbQueue = try DatabaseQueue(path: url.path)
        try dbQueue.write { db in
            try db.execute(sql: "DROP TABLE rrInterval")
            try db.execute(sql: """
                CREATE TABLE rrInterval(
                    deviceId TEXT NOT NULL, ts INTEGER NOT NULL, rrMs INTEGER NOT NULL,
                    seq INTEGER NOT NULL DEFAULT 0, ord INTEGER,
                    srcChannel INTEGER, tsSuspect INTEGER,
                    PRIMARY KEY(deviceId, ts, rrMs, seq)
                )
                """)
            try db.execute(sql: """
                INSERT INTO rrInterval(deviceId, ts, rrMs, seq, ord, srcChannel, tsSuspect) VALUES
                    ('my-whoop', 100, 800, 0, 0, NULL, NULL),
                    ('my-whoop', 101, 790, 0, 0, 1, NULL),
                    ('my-whoop', 101, 810, 1, 1, 1, NULL),
                    ('my-whoop', 101, 820, 2, 2, 2, NULL),
                    ('my-whoop', 102, 830, 0, 0, NULL, 1),
                    ('my-whoop', 103, 840, 0, 0, 3, 0),
                    ('other-device', 103, 850, 0, 0, NULL, NULL)
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
