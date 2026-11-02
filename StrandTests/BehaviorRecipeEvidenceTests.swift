import XCTest
import WhoopStore
import StrandAnalytics
@testable import Strand

/// Fork-only integration witnesses for two existing NOOP Journal -> metric recipes.
///
/// These are synthetic observations, NOT results from people, devices or a
/// preregistered experiment. They test existing production contracts without
/// introducing an experiment store, new metric key or causal claim.
final class BehaviorRecipeEvidenceTests: XCTestCase {
    private let days = (1...12).map { String(format: "2026-09-%02d", $0) }

    /// Exercise the exact starter question, metric descriptor, imported/native
    /// journal merge and the pure outcome comparison together. Days with a
    /// metric but no explicit Journal answer must NOT become No controls.
    private func compare(
        factor: String,
        metricKey: String,
        yesValues: [Double],
        noValues: [Double],
        missingValues: [Double]
    ) throws -> BehaviorEffect {
        XCTAssertTrue(JournalCatalogStore.starterQuestions.contains(factor))
        XCTAssertEqual(MetricCatalog.metric(key: metricKey, source: "my-whoop")?.key, metricKey)
        XCTAssertEqual(yesValues.count, 5)
        XCTAssertEqual(noValues.count, 5)
        XCTAssertEqual(missingValues.count, 2)

        let yesDays = Array(days.prefix(5))
        let noDays = Array(days.dropFirst(5).prefix(5))
        let unknownDays = Array(days.suffix(2))

        let imported = yesDays.map {
            JournalEntry(day: $0, question: factor, answeredYes: true, notes: nil)
        } + [
            // A stale imported Yes must not override the actual native No.
            JournalEntry(day: noDays[0], question: factor, answeredYes: true, notes: nil)
        ]
        let native = noDays.map {
            JournalEntry(day: $0, question: factor, answeredYes: false, notes: nil)
        }
        let merged = Repository.mergeJournal(imported: imported, native: native)
        XCTAssertEqual(merged.count, 10)

        let yes = Set(merged.filter { $0.question == factor && $0.answeredYes }.map(\.day))
        let no = Set(merged.filter { $0.question == factor && !$0.answeredYes }.map(\.day))
        XCTAssertEqual(yes, Set(yesDays))
        XCTAssertEqual(no, Set(noDays))
        XCTAssertTrue(Set(unknownDays).isDisjoint(with: yes.union(no)))

        let values = Dictionary(uniqueKeysWithValues:
            zip(yesDays + noDays + unknownDays, yesValues + noValues + missingValues))
        let direct = try XCTUnwrap(
            BehaviorInsights.effect(
                behaviorDays: yes,
                controlDays: no,
                outcomeByDay: values,
                behavior: factor,
                outcome: metricKey
            )
        )
        // Exercise the same lag-selection entry point the Insights Hub uses,
        // not only the direct day-0 helper. On these synthetic fixtures the
        // prespecified same-day association is stronger than lags +1/+2.
        let ranked = EffectRanker.rank(
            behaviors: [factor: yes],
            controls: [factor: no],
            outcomeByDay: values,
            outcome: metricKey
        )
        XCTAssertEqual(ranked.count, 1)
        XCTAssertEqual(ranked.first?.lag, 0)
        XCTAssertEqual(ranked.first?.effect.delta ?? .nan, direct.delta, accuracy: 1e-9)
        return direct
    }

    func testLateCaffeineAndSleepEfficiencyUseExistingPaths() throws {
        let result = try compare(
            factor: "Did you have caffeine late in the day?",
            metricKey: "sleep_efficiency",
            yesValues: [82, 80, 84, 78, 81],
            noValues: [87, 89, 85, 86, 88],
            missingValues: [10, 99]
        )
        XCTAssertEqual(result.nWith, 5)
        XCTAssertEqual(result.nWithout, 5)
        XCTAssertEqual(result.meanWith, 81, accuracy: 1e-9)
        XCTAssertEqual(result.meanWithout, 87, accuracy: 1e-9)
        XCTAssertEqual(result.delta, -6, accuracy: 1e-9)
    }

    func testScreenInBedAndSleepPerformanceUseExistingPaths() throws {
        let result = try compare(
            factor: "Did you view a screen in bed?",
            metricKey: "sleep_performance",
            yesValues: [60, 62, 61, 59, 63],
            noValues: [73, 71, 75, 72, 74],
            missingValues: [0, 100]
        )
        XCTAssertEqual(result.nWith, 5)
        XCTAssertEqual(result.nWithout, 5)
        XCTAssertEqual(result.meanWith, 61, accuracy: 1e-9)
        XCTAssertEqual(result.meanWithout, 73, accuracy: 1e-9)
        XCTAssertEqual(result.delta, -12, accuracy: 1e-9)
    }

    /// Uses the actual GRDB-backed in-memory database, rather than only projecting
    /// synthetic value arrays. This covers source-scoped writes, reads, native
    /// precedence after a WHOOP reimport, and explicit No versus unanswered days.
    /// No device, real participant data, or causal inference is involved.
    func testPersistedJournalAndMetricRowsRespectExplicitAnswersAfterReimport() async throws {
        let store = try await WhoopStore.inMemory()
        let question = "Did you have caffeine late in the day?"
        let days = (1...12).map { String(format: "2026-09-%02d", $0) }
        let yesDays = Array(days.prefix(5))
        let noDays = Array(days.dropFirst(5).prefix(5))
        let values: [Double] = [82, 80, 84, 78, 81, 87, 89, 85, 86, 88, 10, 99]

        // An earlier imported Yes conflicts with a newer explicit native No.
        // Importing data is not permission to rewrite the user's local answer.
        let imported = yesDays.map {
            JournalEntry(day: $0, question: question, answeredYes: true, notes: nil)
        } + [
            JournalEntry(day: noDays[0], question: question, answeredYes: true, notes: nil)
        ]
        let native = noDays.map {
            JournalEntry(day: $0, question: question, answeredYes: false, notes: nil)
        }
        try await store.upsertJournal(imported, deviceId: "my-whoop")
        try await store.upsertJournal(native, deviceId: "noop-journal")
        try await store.upsertMetricSeries(zip(days, values).map {
            MetricPoint(day: $0.0, key: "sleep_efficiency", value: $0.1)
        }, deviceId: "my-whoop")

        // Reimporting the historical span must not touch the native source.
        try await store.replaceJournalRange(imported, deviceId: "my-whoop",
                                            from: days[0], to: days[9])
        let importedRead = try await store.journalEntries(
            deviceId: "my-whoop", from: days[0], to: days[11])
        let nativeRead = try await store.journalEntries(
            deviceId: "noop-journal", from: days[0], to: days[11])
        let merged = Repository.mergeJournal(imported: importedRead, native: nativeRead)
        let positive = Set(merged.filter { $0.answeredYes }.map(\.day))
        let negative = Set(merged.filter { !$0.answeredYes }.map(\.day))
        XCTAssertEqual(positive, Set(yesDays))
        XCTAssertEqual(negative, Set(noDays))
        XCTAssertTrue(Set(days.suffix(2)).isDisjoint(with: positive.union(negative)))

        // Metric presence alone cannot create a Journal No control.
        let metrics = try await store.metricSeries(
            deviceId: "my-whoop", key: "sleep_efficiency", from: days[0], to: days[11])
        let outcome = Dictionary(uniqueKeysWithValues: metrics.map { ($0.day, $0.value) })
        XCTAssertEqual(outcome.count, 12)
        let effect = try XCTUnwrap(BehaviorInsights.effect(
            behaviorDays: positive,
            controlDays: negative,
            outcomeByDay: outcome,
            behavior: question,
            outcome: "sleep_efficiency"
        ))
        XCTAssertEqual(effect.nWith, 5)
        XCTAssertEqual(effect.nWithout, 5)
        XCTAssertEqual(effect.delta, -6, accuracy: 1e-9)

        // A deliberate native clear reveals the still-present imported value;
        // it does not erase the imported source or fabricate an unknown day.
        let removed = try await store.deleteJournal(
            deviceId: "noop-journal", day: noDays[0], question: question)
        XCTAssertEqual(removed, 1)
        let afterClear = Repository.mergeJournal(
            imported: try await store.journalEntries(
                deviceId: "my-whoop", from: days[0], to: days[11]),
            native: try await store.journalEntries(
                deviceId: "noop-journal", from: days[0], to: days[11])
        )
        XCTAssertTrue(afterClear.contains { $0.day == noDays[0] && $0.answeredYes })
        XCTAssertEqual(afterClear.filter { !$0.answeredYes }.count, 4)
        XCTAssertFalse(afterClear.contains { $0.day == days[10] || $0.day == days[11] })
    }


    /// Missing wearable samples are not zeroes, even when the user answered the Journal.
    /// Conversely a sampled day with no answer is neither a Yes nor an explicit No.
    func testUncoveredOutcomesAndUnansweredDaysFailClosed() throws {
        let factor = "Did you have caffeine late in the day?"
        let yes = Set(days.prefix(5))
        let no = Set(days.dropFirst(5).prefix(5))
        var outcomes = Dictionary(uniqueKeysWithValues: zip(
            Array(days.prefix(4)) + Array(days.dropFirst(5).prefix(4)),
            [82.0, 80.0, 84.0, 78.0, 87.0, 89.0, 85.0, 86.0]
        ))
        // Real metric coverage without a Journal answer must not create a control.
        outcomes[days[10]] = 1000
        // The fifth Yes and fifth explicit No have no wearable metric samples.
        XCTAssertNil(outcomes[days[4]])
        XCTAssertNil(outcomes[days[9]])

        let effect = try XCTUnwrap(BehaviorInsights.effect(
            behaviorDays: yes, controlDays: no, outcomeByDay: outcomes,
            behavior: factor, outcome: "sleep_efficiency"
        ))
        XCTAssertEqual(effect.nWith, 4)
        XCTAssertEqual(effect.nWithout, 4)
        XCTAssertEqual(effect.meanWith, 81, accuracy: 1e-9)
        XCTAssertEqual(effect.meanWithout, 86.75, accuracy: 1e-9)
        XCTAssertEqual(effect.delta, -5.75, accuracy: 1e-9)
        XCTAssertFalse(effect.significant)
        // With fewer than five paired samples per group, no lag may be promoted.
        XCTAssertTrue(EffectRanker.rank(
            behaviors: [factor: yes], controls: [factor: no],
            outcomeByDay: outcomes, outcome: "sleep_efficiency"
        ).isEmpty)
        XCTAssertNil(BehaviorInsights.effect(
            behaviorDays: yes, controlDays: no,
            outcomeByDay: [days[10]: 1000],
            behavior: factor, outcome: "sleep_efficiency"
        ))
    }

    /// Journal dates are wake-day keys; a next-morning result crosses New Year
    /// without shifting the question into an unlogged day or losing a paired row.
    func testWakeDayNextMorningJoinAcrossYearBoundary() {
        let journalDays = [
            "2026-12-26", "2026-12-27", "2026-12-28", "2026-12-29",
            "2026-12-30", "2026-12-31", "2027-01-01", "2027-01-02",
            "2027-01-03", "2027-01-04"
        ]
        let nextMornings = [
            "2026-12-27", "2026-12-28", "2026-12-29", "2026-12-30",
            "2026-12-31", "2027-01-01", "2027-01-02", "2027-01-03",
            "2027-01-04", "2027-01-05"
        ]
        let outcomes = Dictionary(uniqueKeysWithValues: zip(
            nextMornings, [82.0, 80.0, 84.0, 78.0, 81.0, 87.0, 89.0, 85.0, 86.0, 88.0]
        ))
        let factor = "Did you have caffeine late in the day?"
        let ranked = EffectRanker.rank(
            behaviors: [factor: Set(journalDays.prefix(5))],
            controls: [factor: Set(journalDays.suffix(5))],
            outcomeByDay: outcomes, outcome: "sleep_efficiency"
        )
        XCTAssertEqual(ranked.count, 1)
        XCTAssertEqual(ranked.first?.lag, 1)
        XCTAssertEqual(ranked.first?.effect.nWith, 5)
        XCTAssertEqual(ranked.first?.effect.nWithout, 5)
        XCTAssertEqual(ranked.first?.effect.delta ?? .nan, -6, accuracy: 1e-9)
    }

}
