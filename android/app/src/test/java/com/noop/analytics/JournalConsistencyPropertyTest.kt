package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Array-run oracle, not the production epoch-day map/set algorithm. Swift fixture twin. */
class JournalConsistencyPropertyTest {
    @Test fun everyTriStateWeekAgainstIndependentRunOracle() {
        val days = (13..19).map { "2026-10-$it" }
        val windows = listOf(Int.MIN_VALUE, 1, 3, 7, 14, 90, Int.MAX_VALUE)
        // 3^7 combinations: null = no row, false = explicit No, true = explicit Yes.
        for (pattern in 0 until 2187) {
            var digits = pattern
            val states: List<Boolean?> = List(7) {
                val digit = digits % 3
                digits /= 3
                if (digit == 0) null else digit == 2
            }
            val logged = states.indices.filter { states[it] != null }
            val keys = logged.map { days[it] }
            val answers = logged.map { states[it]!! }
            // Count contiguous array runs without constructing day-key sets.
            var longest = 0
            var run = 0
            for (state in states) {
                run = if (state == true) run + 1 else 0
                longest = maxOf(longest, run)
            }
            val active = if (states.last() == null) states.dropLast(1) else states
            val current = active.asReversed().takeWhile { it == true }.size
            for (requested in windows) {
                val window = requested.coerceIn(1, 90)
                val recent = states.takeLast(minOf(window, states.size))
                val yes = recent.count { it == true }
                val no = recent.count { it == false }
                val expected = JournalConsistency.Summary(
                    windowDays = window, yesDays = yes, noDays = no,
                    unloggedDays = window - yes - no, currentYesStreak = current,
                    longestYesStreak = longest, todayAnswer = states.last(),
                )
                assertEquals("pattern=$pattern, window=$requested", expected,
                    JournalConsistency.summarize(keys, answers, days.last(), requested))
            }
        }
    }

    @Test fun laterEditsWinWithoutDependingOnChronologicalInputOrder() {
        val expected = JournalConsistency.summarize(
            listOf("2026-10-17", "2026-10-18", "2026-10-19"),
            listOf(true, false, true), "2026-10-19",
        )!!
        val actual = JournalConsistency.summarize(
            listOf("2026-10-19", "2026-10-18", "2026-10-17", "2026-10-20",
                   "2026-10-18", "2026-10-19", "2026-10-32", "2026-10-20"),
            listOf(false, true, true, true, false, true, true, false), "2026-10-19",
        )
        assertEquals(expected, actual)
        assertEquals(expected, JournalConsistency.summarize(
            listOf("2026-10-19", "2026-10-18", "2026-10-17",
                   "2026-10-19", "2026-10-18", "2026-10-17"),
            listOf(true, false, true, true, false, true), "2026-10-19",
        ))
    }

    @Test fun gregorianCenturyAndYearBoundaryRuns() {
        val cases = listOf(
            listOf("1900-02-28", "1900-03-01"),
            listOf("2000-02-28", "2000-02-29", "2000-03-01"),
            listOf("2100-02-28", "2100-03-01"),
            listOf("2400-02-28", "2400-02-29", "2400-03-01"),
            listOf("2026-12-31", "2027-01-01"),
            listOf("2026-03-07", "2026-03-08", "2026-03-09"),
            listOf("2026-10-31", "2026-11-01", "2026-11-02"),
        )
        for (days in cases) {
            val actual = JournalConsistency.summarize(days, days.map { true }, days.last())!!
            assertEquals(days.toString(), days.size, actual.yesDays)
            assertEquals(days.toString(), days.size, actual.currentYesStreak)
            assertEquals(days.toString(), days.size, actual.longestYesStreak)
        }
        for (day in listOf("1900-02-29", "2100-02-29")) {
            assertNull(JournalConsistency.summarize(emptyList(), emptyList(), day))
        }
    }

    @Test fun malformedKeysCannotOverwriteAnExplicitAnswer() {
        val malformed = listOf("2026-00-19", "2026-13-19", "2026-10-00", "2026-04-31",
            "2026-1-19", "2026-10-019", " 2026-10-19", "2026-10-19\n",
            "2026/10/19", "２０２６-10-19", "2026-10-١٩", "2026-10-19T00:00:00Z")
        for (day in malformed) {
            assertNull(day, JournalConsistency.summarize(emptyList(), emptyList(), day))
        }
        val actual = JournalConsistency.summarize(
            listOf("2026-10-19") + malformed, listOf(false) + malformed.map { true }, "2026-10-19",
        )!!
        assertEquals(0, actual.yesDays)
        assertEquals(1, actual.noDays)
        assertEquals(6, actual.unloggedDays)
        assertEquals(0, actual.currentYesStreak)
        assertEquals(0, actual.longestYesStreak)
        assertEquals(false, actual.todayAnswer)
    }
}
