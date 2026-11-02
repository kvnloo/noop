package com.noop.ui

import com.noop.analytics.BehaviorEffect
import com.noop.analytics.EffectRanker
import com.noop.data.JournalEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android twin of the fork-only macOS BehaviorRecipeEvidenceTests.
 * All outcomes are synthetic; no human data or causal/clinical claims.
 */
class BehaviorRecipeEvidenceTest {
    private val days = (1..12).map { "2026-09-%02d".format(it) }

    private fun compare(
        factor: String,
        metricKey: String,
        yesValues: List<Double>,
        noValues: List<Double>,
        missingValues: List<Double>,
    ): BehaviorEffect {
        assertTrue(STARTER_JOURNAL_QUESTIONS.contains(factor))
        assertEquals(5, yesValues.size)
        assertEquals(5, noValues.size)
        assertEquals(2, missingValues.size)

        val yesDays = days.take(5)
        val noDays = days.drop(5).take(5)
        val unknownDays = days.takeLast(2)
        val imported = yesDays.map {
            JournalEntry(deviceId = "my-whoop", day = it, question = factor, answeredYes = true)
        } + JournalEntry(
            deviceId = "my-whoop", day = noDays[0], question = factor, answeredYes = true,
        )
        val native = noDays.map {
            JournalEntry(deviceId = "noop-journal", day = it, question = factor, answeredYes = false)
        }
        val merged = mergeJournalEntries(imported, native)
        assertEquals(10, merged.size)

        val yes = merged.filter { it.question == factor && it.answeredYes }.map { it.day }.toSet()
        val no = merged.filter { it.question == factor && !it.answeredYes }.map { it.day }.toSet()
        assertEquals(yesDays.toSet(), yes)
        assertEquals(noDays.toSet(), no)
        assertTrue(unknownDays.none { it in yes || it in no })

        val values = (days zip (yesValues + noValues + missingValues)).toMap()
        val direct = requireNotNull(EffectRanker.effect(yes, no, values, factor, metricKey))
        // Exercise the lag-selection API used by InsightsHubScreen as well.
        // On these synthetic fixtures lag 0 beats +1/+2 on effect size.
        val ranked = EffectRanker.rank(
            behaviors = mapOf(factor to yes),
            controls = mapOf(factor to no),
            outcomeByDay = values,
            outcome = metricKey,
        )
        assertEquals(1, ranked.size)
        assertEquals(0, ranked.single().lag)
        assertEquals(direct.delta, ranked.single().effect.delta, 1e-9)
        return direct
    }

    @Test fun lateCaffeineVsSleepEfficiencyUsesExistingPaths() {
        val result = compare(
            factor = "Did you have caffeine late in the day?",
            metricKey = "sleep_efficiency",
            yesValues = listOf(82.0, 80.0, 84.0, 78.0, 81.0),
            noValues = listOf(87.0, 89.0, 85.0, 86.0, 88.0),
            missingValues = listOf(10.0, 99.0),
        )
        assertEquals(5, result.nWith)
        assertEquals(5, result.nWithout)
        assertEquals(81.0, result.meanWith, 1e-9)
        assertEquals(87.0, result.meanWithout, 1e-9)
        assertEquals(-6.0, result.delta, 1e-9)
    }

    @Test fun screenInBedVsSleepPerformanceUsesExistingPaths() {
        val result = compare(
            factor = "Did you view a screen in bed?",
            metricKey = "sleep_performance",
            yesValues = listOf(60.0, 62.0, 61.0, 59.0, 63.0),
            noValues = listOf(73.0, 71.0, 75.0, 72.0, 74.0),
            missingValues = listOf(0.0, 100.0),
        )
        assertEquals(5, result.nWith)
        assertEquals(5, result.nWithout)
        assertEquals(61.0, result.meanWith, 1e-9)
        assertEquals(73.0, result.meanWithout, 1e-9)
        assertEquals(-12.0, result.delta, 1e-9)
    }

    @Test fun uncoveredOutcomesAndUnansweredDaysFailClosed() {
        val factor = "Did you have caffeine late in the day?"
        val yes = days.take(5).toSet()
        val no = days.drop(5).take(5).toSet()
        val outcomes = ((days.take(4) + days.drop(5).take(4)) zip listOf(
            82.0, 80.0, 84.0, 78.0, 87.0, 89.0, 85.0, 86.0,
        )).toMap() + (days[10] to 1000.0)
        // Missing WHOOP samples, even on explicit Yes/No Journal days, are not zero outcomes.
        assertTrue(days[4] !in outcomes && days[9] !in outcomes)

        val effect = requireNotNull(EffectRanker.effect(
            yes, no, outcomes, factor, "sleep_efficiency",
        ))
        assertEquals(4, effect.nWith)
        assertEquals(4, effect.nWithout)
        assertEquals(81.0, effect.meanWith, 1e-9)
        assertEquals(86.75, effect.meanWithout, 1e-9)
        assertEquals(-5.75, effect.delta, 1e-9)
        assertTrue(!effect.significant)
        // No lag qualifies below the five-per-group gate, even with an unlogged metric day.
        assertTrue(EffectRanker.rank(
            behaviors = mapOf(factor to yes), controls = mapOf(factor to no),
            outcomeByDay = outcomes, outcome = "sleep_efficiency",
        ).isEmpty())
        assertTrue(EffectRanker.effect(
            yes, no, mapOf(days[10] to 1000.0), factor, "sleep_efficiency",
        ) == null)
    }

    @Test fun wakeDayNextMorningJoinAcrossYearBoundary() {
        val journalDays = listOf(
            "2026-12-26", "2026-12-27", "2026-12-28", "2026-12-29",
            "2026-12-30", "2026-12-31", "2027-01-01", "2027-01-02",
            "2027-01-03", "2027-01-04",
        )
        val nextMornings = listOf(
            "2026-12-27", "2026-12-28", "2026-12-29", "2026-12-30",
            "2026-12-31", "2027-01-01", "2027-01-02", "2027-01-03",
            "2027-01-04", "2027-01-05",
        )
        val outcome = (nextMornings zip listOf(
            82.0, 80.0, 84.0, 78.0, 81.0, 87.0, 89.0, 85.0, 86.0, 88.0,
        )).toMap()
        val factor = "Did you have caffeine late in the day?"
        val ranked = EffectRanker.rank(
            behaviors = mapOf(factor to journalDays.take(5).toSet()),
            controls = mapOf(factor to journalDays.takeLast(5).toSet()),
            outcomeByDay = outcome, outcome = "sleep_efficiency",
        )
        assertEquals(1, ranked.size)
        assertEquals(1, ranked.single().lag)
        assertEquals(5, ranked.single().effect.nWith)
        assertEquals(5, ranked.single().effect.nWithout)
        assertEquals(-6.0, ranked.single().effect.delta, 1e-9)
    }

}
