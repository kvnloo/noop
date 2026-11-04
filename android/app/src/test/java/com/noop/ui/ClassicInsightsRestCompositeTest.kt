package com.noop.ui

import com.noop.analytics.EffectRanker
import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classic Insights Rest input to behaviour ranking, correlations and experiments.
 * These are synthetic outcomes, never wearer or medical observations.
 *
 * Tests the actual pure series function the classic Insights model consumes.
 * Avoids initializing the localized Outcome enum in a headless JVM.
 */
class ClassicInsightsRestCompositeTest {
    private val factor = "Did you have caffeine late in the day?"

    @Test fun rankingUsesCompositeRatherThanTheDailyEfficiencyFraction() {
        val days = (1..10).map { n ->
            DailyMetric(
                deviceId = "whoop-test-noop",
                day = "2026-09-%02d".format(n),
                efficiency = 0.94,  // Unchanged across groups, and not the Rest score.
                recovery = 70.0,
            )
        }
        val yes = days.take(5).map { it.day }.toSet()
        val no = days.drop(5).map { it.day }.toSet()
        val rest = days.mapIndexed { n, d -> d.day to (if (n < 5) 60.0 + n else 80.0 + n - 5) }.toMap()

        val series = classicInsightsRestSeries(days, rest)
        assertEquals(days.map { it.day to rest.getValue(it.day) }, series)
        assertTrue(series.none { it.second == 0.94 })

        // This is the production comparison engine, not invented local math.
        val ranked = EffectRanker.rankNoLag(
            mapOf(factor to yes), mapOf(factor to no), series.toMap(), "Rest",
        )
        assertEquals(1, ranked.size)
        assertEquals(62.0, ranked.single().meanWith, 1e-9)
        assertEquals(82.0, ranked.single().meanWithout, 1e-9)
        assertEquals(-20.0, ranked.single().delta, 1e-9)
    }

    @Test fun noCompositeMeansNoRestEvenWhenEfficiencyExists() {
        val day = DailyMetric(deviceId = "whoop-test-noop", day = "2026-09-11", efficiency = 0.99)
        assertTrue(classicInsightsRestSeries(listOf(day), emptyMap()).isEmpty())
    }

    @Test fun noOutOfWindowValuesAndNoExtraDailyFallback() {
        val day = DailyMetric(deviceId = "whoop-test-noop", day = "2026-09-12", efficiency = 0.91)
        val series = classicInsightsRestSeries(
            listOf(day), mapOf(day.day to 83.0, "2026-09-13" to 99.0),
        )
        assertEquals(listOf(day.day to 83.0), series)
    }

    @Test fun importOnlyRestWorksWithoutAnEfficiencyColumn() {
        val day = DailyMetric(deviceId = "my-whoop", day = "2026-09-14")
        assertEquals(
            listOf(day.day to 76.0),
            classicInsightsRestSeries(listOf(day), mapOf(day.day to 76.0)),
        )
    }
}
