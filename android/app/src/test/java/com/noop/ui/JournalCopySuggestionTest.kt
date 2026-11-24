package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalCopySuggestionTest {
    @Test fun previousAnswersAreOnlySuggestionsWhenCurrentDayIsEmpty() {
        val previous = mapOf("read" to true, "caffeine" to false)
        val result = journalCopySuggestion(0L, emptyMap(), previous)
        assertEquals(previous, result)
        assertEquals(false, result["caffeine"])
    }

    @Test fun manualTodayAnswerSuppressesSuggestions() {
        assertTrue(journalCopySuggestion(0L, mapOf("read" to false),
            mapOf("read" to true)).isEmpty())
    }

    @Test fun backfillAndTomorrowNeverOfferACopy() {
        val previous = mapOf("read" to true)
        assertTrue(journalCopySuggestion(1L, emptyMap(), previous).isEmpty())
        assertTrue(journalCopySuggestion(-1L, emptyMap(), previous).isEmpty())
    }

    @Test fun noPreviousLogMeansNoSuggestion() {
        assertTrue(journalCopySuggestion(0L, emptyMap(), emptyMap()).isEmpty())
    }

    @Test fun confirmationDoesNotOverwriteARacingManualAnswer() {
        val candidate = mapOf("read" to true, "late caffeine" to false)
        assertEquals(
            mapOf("late caffeine" to false),
            missingJournalCopyAnswers(candidate, mapOf("read" to false)),
        )
        assertTrue(missingJournalCopyAnswers(candidate, candidate).isEmpty())
    }

    @Test fun importedCurrentDayEvidenceSuppressesCopyOffer() {
        val importedToday = mapOf("read" to true)
        assertTrue(journalCopySuggestion(0L, importedToday, mapOf("read" to false)).isEmpty())
    }

    @Test fun confirmationDoesNotReplaceImportedAnswers() {
        val candidate = mapOf("read" to false, "walk" to true)
        val imported = mapOf("read" to true)
        assertEquals(mapOf("walk" to true), missingJournalCopyAnswers(candidate, imported))
    }
    @Test fun numericRowsCannotBeCopiedAsDoseFreeYesEvidence() {
        val yesterday = "2026-10-18"
        val rows = listOf(
            com.noop.data.JournalEntry(JOURNAL_DEVICE_ID, yesterday, "Caffeine (mg)", true, numericValue = 120.0),
            com.noop.data.JournalEntry(JOURNAL_DEVICE_ID, yesterday, "Read a page?", true),
            com.noop.data.JournalEntry(JOURNAL_DEVICE_ID, yesterday, "Avoided screen?", false),
        )
        val eligible = setOf("caffeine (mg)", "read a page?", "avoided screen?")
        assertEquals(
            mapOf("Read a page?" to true, "Avoided screen?" to false),
            previousBooleanJournalAnswers(rows, yesterday, eligible),
        )
    }

    @Test fun onlyVisibleBooleanCatalogQuestionsAreCopyable() {
        val yesterday = "2026-10-18"
        val rows = listOf(
            com.noop.data.JournalEntry(JOURNAL_DEVICE_ID, yesterday, "Did I stretch?", true),
            com.noop.data.JournalEntry(JOURNAL_DEVICE_ID, yesterday, "Hidden custom?", false),
            com.noop.data.JournalEntry(JOURNAL_DEVICE_ID, "2026-10-17", "Did I stretch?", false),
        )
        assertEquals(
            mapOf("Did I stretch?" to true),
            previousBooleanJournalAnswers(rows, yesterday, setOf(normJournalKey(" DID I STRETCH? "))),
        )
        assertTrue(previousBooleanJournalAnswers(rows, yesterday, emptySet()).isEmpty())
    }

}
