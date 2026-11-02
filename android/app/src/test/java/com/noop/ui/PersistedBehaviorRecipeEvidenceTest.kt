package com.noop.ui

import androidx.room.Room
import com.noop.analytics.EffectRanker
import com.noop.data.JournalEntry
import com.noop.data.MetricSeriesRow
import com.noop.data.WhoopDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Android/Room twin of PersistedBehaviorRecipeEvidenceTests.swift.
 * Synthetic rows only: no wearable, participant data, or causal/medical claims.
 */
@RunWith(RobolectricTestRunner::class)
class PersistedBehaviorRecipeEvidenceTest {
    private data class Recipe(
        val factor: String,
        val metric: String,
        val yes: List<Double>,
        val no: List<Double>,
        val unanswered: List<Double>,
        val meanYes: Double,
        val meanNo: Double,
    )

    @Test fun twoRecipesRoundTripThroughRoomWithoutInventedControls() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), WhoopDatabase::class.java,
        ).build()
        try {
            val dao = db.whoopDao()
            val days = (1..12).map { "2026-09-%02d".format(it) }
            val yesDays = days.take(5)
            val noDays = days.drop(5).take(5)
            val unansweredDays = days.takeLast(2)
            val recipes = listOf(
                Recipe(
                    "Did you have caffeine late in the day?", "sleep_efficiency",
                    listOf(82.0, 80.0, 84.0, 78.0, 81.0),
                    listOf(87.0, 89.0, 85.0, 86.0, 88.0),
                    listOf(10.0, 99.0), 81.0, 87.0,
                ),
                Recipe(
                    "Did you view a screen in bed?", "sleep_performance",
                    listOf(60.0, 62.0, 61.0, 59.0, 63.0),
                    listOf(73.0, 71.0, 75.0, 72.0, 74.0),
                    listOf(0.0, 100.0), 61.0, 73.0,
                ),
            )

            for (recipe in recipes) {
                assertTrue(STARTER_JOURNAL_QUESTIONS.contains(recipe.factor))
                dao.upsertJournal(yesDays.map {
                    JournalEntry("my-whoop", it, recipe.factor, true)
                } + JournalEntry("my-whoop", noDays[0], recipe.factor, true))
                // Native No overrides a stale imported Yes at the same (day, question).
                dao.upsertJournal(noDays.map {
                    JournalEntry("noop-journal", it, recipe.factor, false)
                })
                dao.upsertMetricSeries(
                    (days zip (recipe.yes + recipe.no + recipe.unanswered)).map { (day, value) ->
                        MetricSeriesRow("my-whoop", day, recipe.metric, value)
                    },
                )

                val imported = dao.journal("my-whoop", days.first(), days.last())
                val native = dao.journal("noop-journal", days.first(), days.last())
                val metric = dao.metricSeries("my-whoop", recipe.metric, days.first(), days.last())
                assertEquals(6, imported.size)
                assertEquals(5, native.size)
                assertEquals(12, metric.size)

                val merged = mergeJournalEntries(imported, native)
                val withDays = merged.filter { it.answeredYes }.map { it.day }.toSet()
                val withoutDays = merged.filter { !it.answeredYes }.map { it.day }.toSet()
                assertEquals(10, merged.size)
                assertEquals(yesDays.toSet(), withDays)
                assertEquals(noDays.toSet(), withoutDays)
                assertTrue(unansweredDays.none { it in withDays || it in withoutDays })

                val values = metric.associate { it.day to it.value }
                val effect = requireNotNull(EffectRanker.effect(
                    withDays, withoutDays, values, recipe.factor, recipe.metric,
                ))
                assertEquals(5, effect.nWith)
                assertEquals(5, effect.nWithout)
                assertEquals(recipe.meanYes, effect.meanWith, 1e-9)
                assertEquals(recipe.meanNo, effect.meanWithout, 1e-9)
                assertEquals(recipe.meanYes - recipe.meanNo, effect.delta, 1e-9)
            }
        } finally {
            db.close()
        }
    }

    /** A metric from another source cannot silently fill missing WHOOP days.
     *  Repeat Room reads (including after an imported Journal replacement) remain source-scoped. */
    @Test fun missingWearableCoverageStaysAbsentAfterReimportAndRepeatedReads() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), WhoopDatabase::class.java,
        ).build()
        try {
            val dao = db.whoopDao()
            val factor = "Did you have caffeine late in the day?"
            val yesDays = listOf(
                "2026-12-28", "2026-12-29", "2026-12-30", "2026-12-31", "2027-01-01",
            )
            val noDays = listOf(
                "2027-01-02", "2027-01-03", "2027-01-04", "2027-01-05", "2027-01-06",
            )
            val unansweredDay = "2027-01-07"
            val imported = yesDays.map {
                JournalEntry("my-whoop", it, factor, true)
            } + JournalEntry("my-whoop", noDays[0], factor, true)
            val native = noDays.map {
                JournalEntry("noop-journal", it, factor, false)
            }
            dao.upsertJournal(imported)
            dao.upsertJournal(native)

            val coveredDays = yesDays.dropLast(1) + noDays.dropLast(1) + unansweredDay
            val coveredValues = listOf(82.0, 80.0, 84.0, 78.0, 87.0, 89.0, 85.0, 86.0, 1000.0)
            dao.upsertMetricSeries((coveredDays zip coveredValues).map { (day, value) ->
                MetricSeriesRow("my-whoop", day, "sleep_efficiency", value)
            })
            dao.upsertMetricSeries(listOf(
                MetricSeriesRow("unverified-wearable", yesDays.last(), "sleep_efficiency", 0.0),
                MetricSeriesRow("unverified-wearable", noDays.last(), "sleep_efficiency", 100.0),
            ))

            repeat(2) { pass ->
                if (pass == 1) {
                    dao.deleteJournalRange("my-whoop", yesDays.first(), unansweredDay)
                    dao.upsertJournal(imported)
                }
                val importedRead = dao.journal("my-whoop", yesDays.first(), unansweredDay)
                val nativeRead = dao.journal("noop-journal", yesDays.first(), unansweredDay)
                val metricRead = dao.metricSeries(
                    "my-whoop", "sleep_efficiency", yesDays.first(), unansweredDay,
                )
                val otherRead = dao.metricSeries(
                    "unverified-wearable", "sleep_efficiency", yesDays.first(), unansweredDay,
                )
                assertEquals(6, importedRead.size)
                assertEquals(5, nativeRead.size)
                assertEquals(9, metricRead.size)
                assertEquals(coveredDays.toSet(), metricRead.map { it.day }.toSet())
                assertEquals(2, otherRead.size)
                val merged = mergeJournalEntries(importedRead, nativeRead)
                val withDays = merged.filter { it.answeredYes }.map { it.day }.toSet()
                val withoutDays = merged.filter { !it.answeredYes }.map { it.day }.toSet()
                assertEquals(yesDays.toSet(), withDays)
                assertEquals(noDays.toSet(), withoutDays)
                assertTrue(unansweredDay !in withDays && unansweredDay !in withoutDays)

                val outcomes = metricRead.associate { it.day to it.value }
                assertTrue(yesDays.last() !in outcomes && noDays.last() !in outcomes)
                val effect = requireNotNull(EffectRanker.effect(
                    withDays, withoutDays, outcomes, factor, "sleep_efficiency",
                ))
                assertEquals(4, effect.nWith)
                assertEquals(4, effect.nWithout)
                assertEquals(-5.75, effect.delta, 1e-9)
                assertTrue(EffectRanker.rank(
                    behaviors = mapOf(factor to withDays),
                    controls = mapOf(factor to withoutDays),
                    outcomeByDay = outcomes, outcome = "sleep_efficiency",
                ).isEmpty())
            }
        } finally {
            db.close()
        }
    }

}
