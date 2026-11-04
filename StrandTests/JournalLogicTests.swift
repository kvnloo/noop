import XCTest
import WhoopStore
@testable import Strand

/// Pins native journal-entry merging and the production typed catalog. Canonical question strings
/// remain exact-match keys to the effects engines on both platforms.
final class JournalLogicTests: XCTestCase {

    private func isolatedDefaults() -> UserDefaults {
        let name = "JournalLogicTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        addTeardownBlock { UserDefaults(suiteName: name)?.removePersistentDomain(forName: name) }
        return defaults
    }

    @MainActor
    private func makeCatalog(custom: [String] = [], hidden: [String] = []) -> JournalCatalogStore {
        let defaults = isolatedDefaults()
        defaults.set(custom, forKey: JournalCatalogBackupKeys.legacyCustom)
        defaults.set(hidden, forKey: JournalCatalogBackupKeys.legacyHidden)
        return JournalCatalogStore(defaults: defaults)
    }

    private func e(_ day: String, _ q: String, _ yes: Bool) -> JournalEntry {
        JournalEntry(day: day, question: q, answeredYes: yes, notes: nil)
    }

    func testNativeWinsOnCollision() {
        let imported = [e("2026-06-09", "Did you drink any alcohol?", false)]
        let native = [e("2026-06-09", "Did you drink any alcohol?", true)]
        let merged = Repository.mergeJournal(imported: imported, native: native)
        XCTAssertEqual(merged.count, 1)
        XCTAssertTrue(merged[0].answeredYes)
    }

    func testDisjointKeysUnionAndSort() {
        let imported = [e("2026-06-09", "B?", true)]
        let native = [e("2026-06-10", "A?", false), e("2026-06-09", "A?", true)]
        let merged = Repository.mergeJournal(imported: imported, native: native)
        XCTAssertEqual(merged.count, 3)
        // Sorted day ASC then question ASC, matches the DAO/store read order.
        XCTAssertEqual(merged.map(\.question), ["A?", "B?", "A?"])
        XCTAssertEqual(merged.map(\.day), ["2026-06-09", "2026-06-09", "2026-06-10"])
    }

    func testClearNativeNoRevealsImportedYesAndLeavesUnansweredDayBoundaries() async throws {
        // Matched with JournalClearImportedRoomTest: a native No masks an imported Yes only
        // while that native row exists. Adjacent unlogged dates must never become No.
        let store = try await WhoopStore.inMemory()
        let question = "Did you view a screen in bed?"
        let otherQuestion = "Did you read before bed?"
        let importedSource = "my-whoop"
        let nativeSource = "noop-journal"
        let day = "2026-06-09"
        let from = "2026-06-08"
        let to = "2026-06-10"

        try await store.upsertJournal([
            e(day, question, true),
            e("2026-06-11", question, true), // Outside the queried window.
        ], deviceId: importedSource)
        try await store.upsertJournal([
            e(day, question, false),
            e(day, otherQuestion, true),     // Clearing one answer must not clear the other.
            e("2026-06-07", question, false), // Outside the queried window.
        ], deviceId: nativeSource)

        func visible() async throws -> [JournalEntry] {
            let imported = try await store.journalEntries(deviceId: importedSource, from: from, to: to)
            let native = try await store.journalEntries(deviceId: nativeSource, from: from, to: to)
            return Repository.mergeJournal(imported: imported, native: native)
        }

        let before = try await visible()
        let beforeQuestion = Dictionary(uniqueKeysWithValues:
            before.filter { $0.question == question }.map { ($0.day, $0.answeredYes) })
        XCTAssertEqual(beforeQuestion, [day: false], "explicit native No overrides imported Yes")
        XCTAssertNil(beforeQuestion[from], "June 8 was never answered")
        XCTAssertNil(beforeQuestion[to], "June 10 was never answered")
        XCTAssertTrue(before.contains { $0.day == day && $0.question == otherQuestion && $0.answeredYes })

        let removed = try await store.deleteJournal(deviceId: nativeSource, day: day, question: question)
        XCTAssertEqual(removed, 1)

        let after = try await visible()
        let afterQuestion = Dictionary(uniqueKeysWithValues:
            after.filter { $0.question == question }.map { ($0.day, $0.answeredYes) })
        XCTAssertEqual(afterQuestion, [day: true], "clear reveals the persisted imported Yes")
        XCTAssertNil(afterQuestion[from], "clearing cannot invent an answer on June 8")
        XCTAssertNil(afterQuestion[to], "clearing cannot invent an answer on June 10")
        XCTAssertTrue(after.contains { $0.day == day && $0.question == otherQuestion && $0.answeredYes })

        let imported = try await store.journalEntries(deviceId: importedSource, from: from, to: to)
        XCTAssertEqual(imported.map(\.answeredYes), [true], "the imported source is untouched")
        let native = try await store.journalEntries(deviceId: nativeSource, from: from, to: to)
        XCTAssertEqual(native.map(\.question), [otherQuestion], "only one native natural key was deleted")
        let secondClear = try await store.deleteJournal(deviceId: nativeSource, day: day, question: question)
        XCTAssertEqual(secondClear, 0, "repeated clear remains a no-op")
    }

    @MainActor
    func testCatalogAdoptsImportedCasing() {
        let cat = makeCatalog().resolvedItems(imported: ["DID YOU DRINK ANY ALCOHOL?"]).map(\.canonical)
        XCTAssertEqual(cat.first, "DID YOU DRINK ANY ALCOHOL?")
        // The starter alcohol question deduped case-insensitively: 9 starters survive + 1 imported.
        XCTAssertEqual(cat.count, JournalCatalogStore.starterQuestions.count)
    }

    @MainActor
    func testCustomsAppendAndBlanksDrop() {
        let store = makeCatalog(custom: ["  ", "Did you nap?", "did you NAP?"])
        let cat = store.resolvedItems(imported: []).map(\.canonical)
        XCTAssertEqual(Array(cat.prefix(JournalCatalogStore.starterQuestions.count)),
                       JournalCatalogStore.starterQuestions)
        XCTAssertEqual(cat.last, "Did you nap?")
        XCTAssertEqual(cat.count, JournalCatalogStore.starterQuestions.count + 1)
    }

    @MainActor
    func testImportedMagnesiumWithTrailingWhitespaceDoesNotDoublePrompt() {
        // #224: a WHOOP export leaves a trailing newline / non-breaking space on the cell, so the
        // imported "Did you take magnesium?\n" must fold onto the starter, NOT add a second row.
        let cat = makeCatalog().resolvedItems(
            imported: ["Did you take magnesium?\n", "Did you take  magnesium?", "Did you take magnesium?\u{00a0}"])
            .map(\.canonical)
        let magCount = cat.filter {
            $0.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
                .caseInsensitiveCompare("Did you take magnesium?") == .orderedSame
        }.count
        XCTAssertEqual(magCount, 1)
        // No net growth, both imported variants dedupe against the starter.
        XCTAssertEqual(cat.count, JournalCatalogStore.starterQuestions.count)
    }

    @MainActor
    func testHiddenQuestionsFilteredOutCaseInsensitively() {
        // Hide one starter (different casing) + one custom; both must drop from the merged catalog.
        let store = makeCatalog(custom: ["Did you nap?"],
                                hidden: ["did you drink any alcohol?", "DID YOU NAP?"])
        let cat = store.resolvedItems(imported: []).map(\.canonical)
        XCTAssertFalse(cat.contains { $0.caseInsensitiveCompare("Did you drink any alcohol?") == .orderedSame })
        XCTAssertFalse(cat.contains { $0.caseInsensitiveCompare("Did you nap?") == .orderedSame })
        XCTAssertEqual(cat.count, JournalCatalogStore.starterQuestions.count - 1)
    }

    @MainActor
    func testSavedMetadataSurvivesNormalizedImportAndReload() throws {
        let defaults = isolatedDefaults()
        let store = JournalCatalogStore(defaults: defaults)
        let canonical = "Did you take magnesium?"
        store.rename(canonical, to: "Magnesium dose")
        store.setKind(canonical, to: .numeric(unitLabel: "mg"))
        store.setGroup(canonical, to: .supplements)
        store.items[0].sortIndex = 7
        let saved = try XCTUnwrap(store.item(for: canonical))
        let imported = [" DID YOU TAKE  MAGNESIUM?\n", canonical]

        let reloaded = JournalCatalogStore(defaults: defaults)
        for catalog in [store, reloaded] {
            let matches = catalog.resolvedItems(imported: imported).filter {
                JournalCatalogStore.norm($0.canonical) == JournalCatalogStore.norm(canonical)
            }
            XCTAssertEqual(matches, [saved], "saved key, display, type, group and order survive imported aliases")
        }
    }

    @MainActor
    func testHiddenImportedAliasCanBeRestoredWithoutDuplicatingItsSavedItem() throws {
        let store = makeCatalog(hidden: ["did you drink any alcohol?"])
        let imported = [" DID YOU DRINK ANY ALCOHOL?\n", "Did you drink any alcohol?"]
        let key = JournalCatalogStore.norm(imported[0])
        XCTAssertFalse(store.resolvedItems(imported: imported).contains {
            JournalCatalogStore.norm($0.canonical) == key
        })
        let hidden = store.resolvedItems(imported: imported, includeHidden: true).filter {
            JournalCatalogStore.norm($0.canonical) == key
        }
        XCTAssertEqual(hidden.count, 1)
        XCTAssertTrue(try XCTUnwrap(hidden.first).hidden)
        store.restore(imported[0])
        let restored = store.resolvedItems(imported: imported).filter {
            JournalCatalogStore.norm($0.canonical) == key
        }
        XCTAssertEqual(restored.count, 1)
        XCTAssertFalse(try XCTUnwrap(restored.first).hidden)
        XCTAssertEqual(restored.first?.canonical, hidden.first?.canonical)
    }

    // MARK: - v2 catalog (#322): legacy migration, rename key-stability, grouping, numeric type

    func testLegacyMigrationFoldsTwoArraysIntoItems() {
        // The one-time fold: customs become .bool/.other custom items; hidden starters become hidden
        // marker items; a hidden custom keeps its hidden flag on the single custom item (no dupe).
        let items = JournalCatalogStore.migrateLegacy(
            custom: ["Did you nap?", "Vitamin D"],
            hidden: ["Did you drink any alcohol?", "Vitamin D"])
        // Two customs materialised, both marked custom.
        let customs = items.filter { $0.custom }
        XCTAssertEqual(Set(customs.map(\.canonical)), ["Did you nap?", "Vitamin D"])
        // The hidden custom kept its flag on the SAME item (deduped by norm, not a second row).
        let vitaminD = items.filter { JournalCatalogStore.norm($0.canonical) == JournalCatalogStore.norm("Vitamin D") }
        XCTAssertEqual(vitaminD.count, 1, "a hidden custom must not duplicate")
        XCTAssertTrue(vitaminD[0].hidden)
        // The hidden starter became a hidden non-custom marker in its default group.
        let alcohol = items.first { JournalCatalogStore.norm($0.canonical) == JournalCatalogStore.norm("Did you drink any alcohol?") }
        XCTAssertNotNil(alcohol)
        XCTAssertTrue(alcohol!.hidden)
        XCTAssertFalse(alcohol!.custom)
        XCTAssertEqual(alcohol!.group, .nutrition)
    }

    @MainActor
    func testRenameKeepsCanonicalStableSoHistorySurvives() {
        // THE key-stability guarantee (#322): renaming an item changes only the display label; the
        // stored canonical (the DB/engine join key) is untouched, so all logged + imported history, 
        // which is keyed on the canonical question string, still lines up after a rename.
        let store = makeCatalog()
        store.items = []   // start from a clean catalog for a deterministic assertion
        let canonical = "Did you have caffeine late in the day?"

        // Before: display resolves to the canonical verbatim.
        XCTAssertEqual(store.displayName(for: canonical), canonical)

        store.rename(canonical, to: "Caffeine")

        // After: the DISPLAY changed, the KEY did not.
        XCTAssertEqual(store.displayName(for: canonical), "Caffeine")
        let item = store.item(for: canonical)
        XCTAssertNotNil(item)
        XCTAssertEqual(item!.canonical, canonical,
                       "rename must NEVER change the canonical, history is keyed on it")
        XCTAssertEqual(item!.displayName, "Caffeine")

        // A journal write / effect lookup for this behaviour still keys on the canonical, so a row
        // logged BEFORE the rename (under the canonical) is found AFTER the rename by the same key.
        let resolved = store.resolvedItems(imported: [], includeHidden: false)
        let caffeine = resolved.first { $0.canonical == canonical }
        XCTAssertEqual(caffeine?.display, "Caffeine")
        XCTAssertEqual(caffeine?.canonical, canonical,
                       "the engine key is preserved end to end, logged/imported days still join")

        // Clearing the rename (blank) falls back to the canonical.
        store.rename(canonical, to: "   ")
        XCTAssertEqual(store.displayName(for: canonical), canonical)
        XCTAssertEqual(store.item(for: canonical)?.canonical, canonical)
    }

    @MainActor
    func testSetGroupAndKindPreserveCanonical() {
        let store = makeCatalog()
        store.items = []
        let canonical = "Did you take magnesium?"
        store.setGroup(canonical, to: .supplements)
        store.setKind(canonical, to: .numeric(unitLabel: "mg"))
        let item = store.item(for: canonical)
        XCTAssertEqual(item?.canonical, canonical, "regroup/retype never touch the key")
        XCTAssertEqual(item?.group, .supplements)
        XCTAssertTrue(item?.kind.isNumeric ?? false)
        XCTAssertEqual(item?.kind.unitLabel, "mg")
    }

    @MainActor
    func testResolvedItemsGroupStartersByDefaultAndDropHidden() {
        let store = makeCatalog()
        store.items = []
        let resolved = store.resolvedItems(imported: [], includeHidden: false)
        // Every starter is present with a default group and .bool kind.
        XCTAssertEqual(resolved.count, JournalCatalogStore.starterQuestions.count)
        let alcohol = resolved.first { $0.canonical == "Did you drink any alcohol?" }
        XCTAssertEqual(alcohol?.group, .nutrition)
        XCTAssertFalse(alcohol?.kind.isNumeric ?? true)
        // Hidden items are dropped unless includeHidden.
        store.remove("Did you drink any alcohol?")
        let afterHide = store.resolvedItems(imported: [], includeHidden: false)
        XCTAssertFalse(afterHide.contains { $0.canonical == "Did you drink any alcohol?" })
        let withHidden = store.resolvedItems(imported: [], includeHidden: true)
        XCTAssertTrue(withHidden.contains { $0.canonical == "Did you drink any alcohol?" && $0.hidden })
    }

    @MainActor
    func testAddCustomNumericItem() {
        let store = makeCatalog()
        store.items = []
        store.addCustom("Water (L)", kind: .numeric(unitLabel: "L"), group: .nutrition)
        XCTAssertTrue(store.isCustom("Water (L)"))
        let item = store.item(for: "Water (L)")
        XCTAssertEqual(item?.group, .nutrition)
        XCTAssertEqual(item?.kind.unitLabel, "L")
    }

    func testNumericJournalKeyIsNamespaced() {
        // The InsightsView folds a numeric journal series under a namespaced key that can never
        // collide with a fixed metric outcome ("recovery" / "hrv" / …).
        let key = InsightsView.numericJournalKey("Caffeine (mg)")
        XCTAssertTrue(key.hasPrefix("journal.numeric:"))
        XCTAssertNotEqual(key, "recovery")
        XCTAssertNotEqual(key, "hrv")
    }
}
