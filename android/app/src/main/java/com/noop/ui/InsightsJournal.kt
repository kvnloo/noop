package com.noop.ui

import com.noop.data.JournalEntry
import com.noop.data.WhoopRepository

internal data class InsightsJournalState(
    val behaviours: Map<String, Set<String>>,
    val controls: Map<String, Set<String>>,
    val numericJournalSeries: Map<String, Map<String, Double>>,
    val importedQuestions: List<String>,
    val dayAnswers: Map<String, Boolean>,
    val dayNumeric: Map<String, Double>,
    val suggestedPreviousAnswers: Map<String, Boolean>,
)

/** Load the Journal tab state without treating suggestions as current-day observations. */
internal suspend fun loadInsightsJournal(
    repo: WhoopRepository,
    dayOffset: Long,
    key: String,
    previousDay: String,
    catalogItems: List<JournalCatalogItem>,
): InsightsJournalState {
    val imported = repo.journal("my-whoop", "0000-01-01", "9999-12-31")
    val native = repo.journal(JOURNAL_DEVICE_ID, "0000-01-01", "9999-12-31")
    val entries = mergeJournalEntries(imported, native)
    val byBehaviour = mutableMapOf<String, MutableSet<String>>()
    val controlsByBehaviour = mutableMapOf<String, MutableSet<String>>()
    // #322: a numeric log writes answeredYes=true too, so a numeric item lands in the with/without
    // split here unchanged; its per-day value is captured separately for a numeric series the effect
    // ranker can consume like any metric outcome (dose-response lands in the v5 hub). Additive.
    val numericByBehaviour = mutableMapOf<String, MutableMap<String, Double>>()
    for (e in entries) {
        val bucket = if (e.answeredYes) byBehaviour else controlsByBehaviour
        bucket.getOrPut(e.question) { mutableSetOf() }.add(e.day)
        e.numericValue?.let { v -> numericByBehaviour.getOrPut(e.question) { mutableMapOf() }[e.day] = v }
    }
    val importedQuestions = imported.map { it.question }.distinct()
    val answers = native.filter { it.day == key }.associate { it.question to it.answeredYes }
    // #322: the selected day's numeric values (native-only; imported WHOOP rows carry none).
    val dayNumeric = native.filter { it.day == key && it.numericValue != null }
        .associate { it.question to it.numericValue!! }
    // Reusing yesterday is a *suggestion*, never a logged Yes/No.
    // A historical row would otherwise be fabricated as though the user observed today.
    // Only the explicit Copy button below can persist a fresh day's answers.
    val yesterdayAnswers = if (dayOffset == 0L) {
        // A numeric journal row also says answeredYes=true, but without its actual
        // numericValue copying that bit would invent a dose-independent observation.
        // Only current, visible boolean prompts may be copied after confirmation.
        val eligible = resolveJournalItems(importedQuestions, catalogItems)
            .filterNot { it.kind.isNumeric }
            .mapTo(hashSetOf<String>()) { normJournalKey(it.canonical) }
        previousBooleanJournalAnswers(native, previousDay, eligible)
    } else emptyMap()
    // Imported WHOOP answers are observations too; do not offer a blanket copy that
    // would overwrite today's imported evidence with a native row.
    val currentRecorded = entries.filter { it.day == key }
        .associate { it.question to it.answeredYes }
    val suggestions = journalCopySuggestion(dayOffset, currentRecorded, yesterdayAnswers)
    return InsightsJournalState(
        behaviours = byBehaviour.mapValues { it.value.toSet() },
        controls = controlsByBehaviour.mapValues { it.value.toSet() },
        numericJournalSeries = numericByBehaviour.mapValues { it.value.toMap() },
        importedQuestions = importedQuestions,
        dayAnswers = answers,
        dayNumeric = dayNumeric,
        suggestedPreviousAnswers = suggestions,
    )
}

/** The persistence action invoked by the Copy button after checking the selected day. */
internal suspend fun copyInsightsJournalAnswers(
    repo: WhoopRepository,
    selectedDay: String,
    pending: Map<String, Boolean>,
) {
    // Check BOTH native and imported current-day evidence at tap
    // time. Copy only missing questions, never overwrite real
    // Yes/No observations that arrived since the suggestion.
    val native = repo.journal(JOURNAL_DEVICE_ID, selectedDay, selectedDay)
    val imported = repo.journal("my-whoop", selectedDay, selectedDay)
    val current = mergeJournalEntries(imported, native)
        .associate { it.question to it.answeredYes }
    val uncopied = missingJournalCopyAnswers(pending, current)
    if (uncopied.isNotEmpty()) {
        repo.upsertJournal(uncopied.map { (q, yes) ->
            JournalEntry(JOURNAL_DEVICE_ID, selectedDay, q, yes)
        })
    }
}
