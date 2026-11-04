package com.noop.ui

/**
 * Previous-day answers are never current-day evidence without an explicit user action.
 * In particular, a missing answer must not be silently copied as an observed Yes or No.
 */
internal fun journalCopySuggestion(
    selectedDayOffset: Long,
    selectedAnswers: Map<String, Boolean>,
    previousAnswers: Map<String, Boolean>,
): Map<String, Boolean> =
    if (selectedDayOffset == 0L && selectedAnswers.isEmpty()) previousAnswers.toMap()
    else emptyMap()

/** A delayed tap cannot overwrite a new answer entered while the suggestion was showing. */
internal fun missingJournalCopyAnswers(
    suggested: Map<String, Boolean>,
    alreadyLogged: Map<String, Boolean>,
): Map<String, Boolean> = suggested.filterKeys { it !in alreadyLogged }

/**
 * Copy only previously recorded *boolean* answers for still-visible boolean Journal prompts.
 * A numeric entry writes answeredYes=true along with numericValue; copying only the Boolean
 * would turn a measured dose into a false completion. Hidden/deleted questions are also excluded.
 * [eligibleKeys] contains normalized canonical names from the current visible Journal catalog.
 */
internal fun previousBooleanJournalAnswers(
    previousRows: List<com.noop.data.JournalEntry>,
    previousDay: String,
    eligibleKeys: Set<String>,
): Map<String, Boolean> =
    previousRows.asSequence()
        .filter { it.day == previousDay && it.numericValue == null &&
            normJournalKey(it.question) in eligibleKeys }
        .associate { it.question to it.answeredYes }
