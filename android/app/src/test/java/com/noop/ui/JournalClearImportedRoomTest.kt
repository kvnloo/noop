package com.noop.ui

import androidx.room.Room
import com.noop.data.JournalEntry
import com.noop.data.WhoopDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Room-backed twin of JournalLogicTests.testClearNativeNoRevealsImportedYesAndLeavesUnansweredDayBoundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class JournalClearImportedRoomTest {

    @Test
    fun clearNativeNoRevealsImportedYesAndLeavesUnansweredDayBoundaries() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            WhoopDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.whoopDao()
            val question = "Did you view a screen in bed?"
            val otherQuestion = "Did you read before bed?"
            val importedSource = "my-whoop"
            val nativeSource = JOURNAL_DEVICE_ID
            val day = "2026-06-09"
            val from = "2026-06-08"
            val to = "2026-06-10"

            dao.upsertJournal(listOf(
                JournalEntry(importedSource, day, question, answeredYes = true),
                JournalEntry(importedSource, "2026-06-11", question, answeredYes = true),
            ))
            dao.upsertJournal(listOf(
                JournalEntry(nativeSource, day, question, answeredYes = false),
                JournalEntry(nativeSource, day, otherQuestion, answeredYes = true),
                JournalEntry(nativeSource, "2026-06-07", question, answeredYes = false),
            ))

            suspend fun visible(): List<JournalEntry> = mergeJournalEntries(
                imported = dao.journal(importedSource, from, to),
                native = dao.journal(nativeSource, from, to),
            )

            val before = visible()
            val beforeQuestion = before.filter { it.question == question }.associate { it.day to it.answeredYes }
            assertEquals(mapOf(day to false), beforeQuestion)
            assertFalse("June 8 was never answered", beforeQuestion.containsKey(from))
            assertFalse("June 10 was never answered", beforeQuestion.containsKey(to))
            assertTrue(before.any { it.day == day && it.question == otherQuestion && it.answeredYes })

            dao.deleteJournalEntry(nativeSource, day, question)

            val after = visible()
            val afterQuestion = after.filter { it.question == question }.associate { it.day to it.answeredYes }
            assertEquals("clear reveals the persisted imported Yes", mapOf(day to true), afterQuestion)
            assertFalse("clearing cannot invent an answer on June 8", afterQuestion.containsKey(from))
            assertFalse("clearing cannot invent an answer on June 10", afterQuestion.containsKey(to))
            assertTrue(after.any { it.day == day && it.question == otherQuestion && it.answeredYes })

            val imported = dao.journal(importedSource, from, to)
            assertEquals(listOf(true), imported.map { it.answeredYes })
            val native = dao.journal(nativeSource, from, to)
            assertEquals(listOf(otherQuestion), native.map { it.question })
            dao.deleteJournalEntry(nativeSource, day, question)
            assertEquals("repeated clear must not alter effective answers", after, visible())
        } finally {
            db.close()
        }
    }
}
