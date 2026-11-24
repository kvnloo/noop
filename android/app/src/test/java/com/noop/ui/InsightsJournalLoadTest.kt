package com.noop.ui

import com.noop.data.JournalEntry
import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsJournalLoadTest {
    @Test fun loadingTodayOnlySuggestsAnswersUntilCopy() = runBlocking {
        val today = "2026-10-19"
        val yesterday = "2026-10-18"
        val previousRows = listOf(
            JournalEntry(JOURNAL_DEVICE_ID, yesterday, "Read a page?", true),
            JournalEntry(JOURNAL_DEVICE_ID, yesterday, "Saw a screen?", false),
        )
        val catalog = previousRows.map { JournalCatalogItem(canonical = it.question, custom = true) }
        val writes = mutableListOf<List<JournalEntry>>()
        val dao = Proxy.newProxyInstance(WhoopDao::class.java.classLoader, arrayOf(WhoopDao::class.java)) {
            _, method, args ->
            when (method.name) {
                "journal" -> previousRows.filter {
                    it.deviceId == args[0] && it.day >= args[1] as String && it.day <= args[2] as String
                }
                "upsertJournal" -> {
                    @Suppress("UNCHECKED_CAST")
                    writes.add((args[0] as List<JournalEntry>).toList())
                    Unit
                }
                else -> error("Unexpected Journal DAO call: ${method.name}")
            }
        } as WhoopDao
        val repo = WhoopRepository(dao)

        val state = loadInsightsJournal(repo, 0L, today, yesterday, catalog)

        assertEquals(mapOf("Read a page?" to true, "Saw a screen?" to false), state.suggestedPreviousAnswers)
        assertTrue(state.dayAnswers.isEmpty())
        assertEquals("Loading today's Journal must not upsert", 0, writes.size)

        copyInsightsJournalAnswers(repo, today, state.suggestedPreviousAnswers)

        assertEquals(listOf(previousRows.map { it.copy(day = today) }), writes)
    }
}
