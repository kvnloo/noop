package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same fixtures as StrandAnalyticsTests/JournalConsistencyTests.swift. */
class JournalConsistencyTest {
    private fun summary(
        days: List<String>,
        yes: List<Boolean>,
        today: String = "2026-10-19",
        window: Int = 7,
    ) = JournalConsistency.summarize(days, yes, today, window)

    @Test fun blankDaysAreNotFalse() {
        val s = summary(emptyList(), emptyList())!!
        assertEquals(0, s.yesDays)
        assertEquals(0, s.noDays)
        assertEquals(7, s.unloggedDays)
        assertNull(s.todayAnswer)
        assertEquals(0, s.currentYesStreak)
    }

    @Test fun explicitYesNoAndMissingRemainDistinct() {
        val s = summary(listOf("2026-10-16", "2026-10-17", "2026-10-19"),
                        listOf(true, false, true), window = 4)!!
        assertEquals(listOf(2, 1, 1), listOf(s.yesDays, s.noDays, s.unloggedDays))
        assertEquals(listOf(1, 1), listOf(s.currentYesStreak, s.longestYesStreak))
        assertEquals(true, s.todayAnswer)
    }

    @Test fun unloggedTodayRetainsYesterdaysRun() {
        val s = summary(listOf("2026-10-17", "2026-10-18"), listOf(true, true))!!
        assertEquals(listOf(2, 2), listOf(s.currentYesStreak, s.longestYesStreak))
        assertNull(s.todayAnswer)
    }

    @Test fun explicitNoTodayBreaksStreakWithoutErasingHistory() {
        val s = summary(listOf("2026-10-17", "2026-10-18", "2026-10-19"),
                        listOf(true, true, false))!!
        assertEquals(listOf(0, 2), listOf(s.currentYesStreak, s.longestYesStreak))
        assertEquals(false, s.todayAnswer)
        assertEquals(1, s.noDays)
    }

    @Test fun laterEditWinsFutureAndInvalidRowsIgnored() {
        val s = summary(
            listOf("2026-10-18", "2026-10-18", "2026-10-20", "not-a-day", "2026-10-19"),
            listOf(true, false, true, true, true), window = 2,
        )!!
        assertEquals(listOf(1, 1, 0), listOf(s.yesDays, s.noDays, s.unloggedDays))
        assertEquals(listOf(1, 1), listOf(s.currentYesStreak, s.longestYesStreak))
    }

    @Test fun leapDayUsesCivilDaysNotSeconds() {
        val s = summary(listOf("2024-02-28", "2024-02-29", "2024-03-01"),
                        listOf(true, true, true), today = "2024-03-01")!!
        assertEquals(listOf(3, 3), listOf(s.currentYesStreak, s.longestYesStreak))
    }

    @Test fun historicLongestNotLimitedToWindow() {
        val s = summary(
            listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-19"),
            listOf(true, true, true, true),
        )!!
        assertEquals(listOf(1, 3), listOf(s.currentYesStreak, s.longestYesStreak))
        assertEquals(listOf(1, 0, 6), listOf(s.yesDays, s.noDays, s.unloggedDays))
    }

    @Test fun windowClampedAndPairedPrefixOnly() {
        val one = summary(listOf("2026-10-19", "2026-10-18"), listOf(true), window = 0)!!
        assertEquals(listOf(1, 1, 0), listOf(one.windowDays, one.yesDays, one.unloggedDays))
        val capped = summary(emptyList(), emptyList(), window = 999)!!
        assertEquals(listOf(90, 90), listOf(capped.windowDays, capped.unloggedDays))
    }

    @Test fun impossibleCalendarDatesNeverBecomeObservations() {
        val s = summary(
            listOf("2026-10-32", "2026-02-29", "2026-11-01"),
            listOf(true, true, false), today = "2026-11-01",
        )!!
        assertEquals(listOf(0, 1, 6), listOf(s.yesDays, s.noDays, s.unloggedDays))
        assertEquals(listOf(0, 0), listOf(s.currentYesStreak, s.longestYesStreak))
        assertEquals(false, s.todayAnswer)
        assertNull(summary(emptyList(), emptyList(), today = "2026-02-29"))
        assertNull(summary(emptyList(), emptyList(), today = "2026-1-1"))
    }

    @Test fun badTodayReturnsNoSummary() {
        assertNull(summary(listOf("2026-10-19"), listOf(true), today = "bad"))
    }
}
