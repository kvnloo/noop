import XCTest
import WhoopStore
import StrandAnalytics
@testable import Strand

/// Uses the real migrated GRDB tables, not in-memory dictionaries masquerading as storage.
/// All values are synthetic: this proves a data path, not an intervention or health effect.
final class PersistedBehaviorRecipeEvidenceTests: XCTestCase {
    func testTwoRecipesRoundTripThroughSQLiteWithoutInventedControls() async throws {
        let store = try await WhoopStore.inMemory()
        let days = (1...12).map { String(format: "2026-09-%02d", $0) }
        let yesDays = Array(days.prefix(5))
        let noDays = Array(days.dropFirst(5).prefix(5))
        let unansweredDays = Array(days.suffix(2))

        let recipes: [(factor: String, metric: String, withValues: [Double],
                       withoutValues: [Double], unansweredValues: [Double],
                       meanWith: Double, meanWithout: Double)] = [
            ("Did you have caffeine late in the day?", "sleep_efficiency",
             [82, 80, 84, 78, 81], [87, 89, 85, 86, 88], [10, 99], 81, 87),
            ("Did you view a screen in bed?", "sleep_performance",
             [60, 62, 61, 59, 63], [73, 71, 75, 72, 74], [0, 100], 61, 73),
        ]

        for recipe in recipes {
            XCTAssertTrue(JournalCatalogStore.starterQuestions.contains(recipe.factor))
            XCTAssertEqual(MetricCatalog.metric(key: recipe.metric, source: "my-whoop")?.key,
                           recipe.metric)

            let imported = yesDays.map {
                JournalEntry(day: $0, question: recipe.factor, answeredYes: true, notes: nil)
            } + [
                // Imported Yes is stale; native No must win for this natural key.
                JournalEntry(day: noDays[0], question: recipe.factor, answeredYes: true, notes: nil)
            ]
            let native = noDays.map {
                JournalEntry(day: $0, question: recipe.factor, answeredYes: false, notes: nil)
            }
            try await store.upsertJournal(imported, deviceId: "my-whoop")
            try await store.upsertJournal(native, deviceId: "noop-journal")

            let samples = zip(days, recipe.withValues + recipe.withoutValues + recipe.unansweredValues)
                .map { MetricPoint(day: $0.0, key: recipe.metric, value: $0.1) }
            try await store.upsertMetricSeries(samples, deviceId: "my-whoop")

            // Read back through production storage before invoking production merge/analytics.
            let importedRead = try await store.journalEntries(
                deviceId: "my-whoop", from: days[0], to: days[11])
            let nativeRead = try await store.journalEntries(
                deviceId: "noop-journal", from: days[0], to: days[11])
            let metricRead = try await store.metricSeries(
                deviceId: "my-whoop", key: recipe.metric, from: days[0], to: days[11])

            XCTAssertEqual(importedRead.count, 6)
            XCTAssertEqual(nativeRead.count, 5)
            XCTAssertEqual(metricRead.count, 12)

            let merged = Repository.mergeJournal(imported: importedRead, native: nativeRead)
            let withDays = Set(merged.filter { $0.answeredYes }.map(\.day))
            let withoutDays = Set(merged.filter { !$0.answeredYes }.map(\.day))
            XCTAssertEqual(merged.count, 10)
            XCTAssertEqual(withDays, Set(yesDays))
            XCTAssertEqual(withoutDays, Set(noDays))
            XCTAssertTrue(Set(unansweredDays).isDisjoint(with: withDays.union(withoutDays)))

            let values = Dictionary(uniqueKeysWithValues: metricRead.map { ($0.day, $0.value) })
            let effect = try XCTUnwrap(BehaviorInsights.effect(
                behaviorDays: withDays, controlDays: withoutDays, outcomeByDay: values,
                behavior: recipe.factor, outcome: recipe.metric))
            XCTAssertEqual(effect.nWith, 5)
            XCTAssertEqual(effect.nWithout, 5)
            XCTAssertEqual(effect.meanWith, recipe.meanWith, accuracy: 1e-9)
            XCTAssertEqual(effect.meanWithout, recipe.meanWithout, accuracy: 1e-9)
            XCTAssertEqual(effect.delta, recipe.meanWith - recipe.meanWithout, accuracy: 1e-9)
        }
    }

    /// Unknown coverage is an absent (source, day, metric) row, not a zero.
    /// Repeat reads and source-scoped WHOOP reimport must not fill it from another device.
    func testMissingWearableCoverageStaysAbsentAfterReimportAndRepeatedReads() async throws {
        let store = try await WhoopStore.inMemory()
        let factor = "Did you have caffeine late in the day?"
        let yesDays = ["2026-12-28", "2026-12-29", "2026-12-30", "2026-12-31", "2027-01-01"]
        let noDays = ["2027-01-02", "2027-01-03", "2027-01-04", "2027-01-05", "2027-01-06"]
        let unansweredDay = "2027-01-07"
        let imported = yesDays.map {
            JournalEntry(day: $0, question: factor, answeredYes: true, notes: nil)
        } + [JournalEntry(day: noDays[0], question: factor, answeredYes: true, notes: nil)]
        let native = noDays.map {
            JournalEntry(day: $0, question: factor, answeredYes: false, notes: nil)
        }
        try await store.upsertJournal(imported, deviceId: "my-whoop")
        try await store.upsertJournal(native, deviceId: "noop-journal")

        let coveredDays = Array(yesDays.dropLast()) + Array(noDays.dropLast()) + [unansweredDay]
        let coveredValues: [Double] = [82, 80, 84, 78, 87, 89, 85, 86, 1000]
        try await store.upsertMetricSeries(zip(coveredDays, coveredValues).map {
            MetricPoint(day: $0.0, key: "sleep_efficiency", value: $0.1)
        }, deviceId: "my-whoop")
        // Another source having values on the missing days must not masquerade as WHOOP coverage.
        try await store.upsertMetricSeries([
            MetricPoint(day: yesDays[4], key: "sleep_efficiency", value: 0),
            MetricPoint(day: noDays[4], key: "sleep_efficiency", value: 100),
        ], deviceId: "unverified-wearable")

        for pass in 0..<2 {
            if pass == 1 {
                try await store.replaceJournalRange(
                    imported, deviceId: "my-whoop",
                    from: yesDays[0], to: unansweredDay
                )
            }
            let importedRead = try await store.journalEntries(
                deviceId: "my-whoop", from: yesDays[0], to: unansweredDay)
            let nativeRead = try await store.journalEntries(
                deviceId: "noop-journal", from: yesDays[0], to: unansweredDay)
            let coveredRead = try await store.metricSeries(
                deviceId: "my-whoop", key: "sleep_efficiency",
                from: yesDays[0], to: unansweredDay)
            let otherRead = try await store.metricSeries(
                deviceId: "unverified-wearable", key: "sleep_efficiency",
                from: yesDays[0], to: unansweredDay)
            XCTAssertEqual(importedRead.count, 6)
            XCTAssertEqual(nativeRead.count, 5)
            XCTAssertEqual(Set(coveredRead.map(\.day)), Set(coveredDays))
            XCTAssertEqual(coveredRead.count, 9)
            XCTAssertEqual(otherRead.count, 2)

            let merged = Repository.mergeJournal(imported: importedRead, native: nativeRead)
            let withDays = Set(merged.filter { $0.answeredYes }.map(\.day))
            let withoutDays = Set(merged.filter { !$0.answeredYes }.map(\.day))
            XCTAssertEqual(withDays, Set(yesDays))
            XCTAssertEqual(withoutDays, Set(noDays))
            XCTAssertFalse(withDays.contains(unansweredDay))
            XCTAssertFalse(withoutDays.contains(unansweredDay))
            let outcomes = Dictionary(uniqueKeysWithValues: coveredRead.map { ($0.day, $0.value) })
            XCTAssertNil(outcomes[yesDays[4]])
            XCTAssertNil(outcomes[noDays[4]])
            let effect = try XCTUnwrap(BehaviorInsights.effect(
                behaviorDays: withDays, controlDays: withoutDays, outcomeByDay: outcomes,
                behavior: factor, outcome: "sleep_efficiency"
            ))
            XCTAssertEqual(effect.nWith, 4)
            XCTAssertEqual(effect.nWithout, 4)
            XCTAssertEqual(effect.delta, -5.75, accuracy: 1e-9)
            XCTAssertTrue(EffectRanker.rank(
                behaviors: [factor: withDays], controls: [factor: withoutDays],
                outcomeByDay: outcomes, outcome: "sleep_efficiency"
            ).isEmpty)
        }
    }

}
