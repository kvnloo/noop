package com.noop.ui

import com.noop.data.DailyMetric
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsStrapSwitchTest {
    @Test fun switchingStrapsReplacesEveryOutcomeAndTheDateWindow() = runTest {
        val activeId = MutableStateFlow("A")
        val fixtures = mapOf("A" to fixture("A", 1..10, 0), "B" to fixture("B", 2..11, 20))
        val daysByStrap = fixtures.mapValues { MutableStateFlow(it.value.days) }
        val snapshots = insightsDailySnapshots(
            activeId,
            daysByStrap::getValue,
        ) { id, from, to ->
            fixtures.getValue(id).restByDay.filterKeys { it in from..to }
        }
        val observed = mutableListOf<InsightsDailySnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            snapshots.collect { observed += it }
        }
        runCurrent()
        assertEquals(listOf(expectedOutcomes(fixtures.getValue("A"))), observed.map(::outcomes))

        // Keep the same pipeline/collector alive through the in-process switch.
        observed.clear()
        activeId.value = "B"
        runCurrent()
        val expected = expectedOutcomes(fixtures.getValue("B"))
        // Full-map equality checks the values, rejects A-only day 01, and requires B's day 11.
        assertEquals(listOf(expected), observed.map(::outcomes))
    }

    private fun fixture(id: String, window: IntRange, offset: Int): InsightsDailySnapshot {
        val days = window.map { n ->
            DailyMetric(
                deviceId = id,
                day = "2026-10-" + n.toString().padStart(2, '0'),
                recovery = 40.0 + offset + n,
                avgHrv = 30.0 + offset + n,
                restingHr = 50 + offset + n,
            )
        }
        val rest = days.mapIndexed { index, day -> day.day to (60.0 + offset + index) }.toMap()
        return InsightsDailySnapshot(days, rest)
    }

    private fun expectedOutcomes(daily: InsightsDailySnapshot) = mapOf(
        Outcome.Recovery to daily.days.associate { it.day to requireNotNull(it.recovery) },
        Outcome.Hrv to daily.days.associate { it.day to requireNotNull(it.avgHrv) },
        Outcome.Rhr to daily.days.associate { it.day to requireNotNull(it.restingHr).toDouble() },
        Outcome.Sleep to daily.restByDay,
    )

    private fun outcomes(daily: InsightsDailySnapshot) =
        classicInsightsSeries(daily.days, daily.restByDay).mapValues { (_, series) -> series.toMap() }
}
