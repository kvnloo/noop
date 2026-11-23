package com.noop.ui

import com.noop.data.DailyMetric
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest

internal data class InsightsDailySnapshot(
    val days: List<DailyMetric> = emptyList(),
    val restByDay: Map<String, Double> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun insightsDailySnapshots(
    activeStrapIds: Flow<String>,
    daysFor: (String) -> Flow<List<DailyMetric>>,
    restFor: suspend (String, String, String) -> Map<String, Double>,
): Flow<InsightsDailySnapshot> =
    activeStrapIds.flatMapLatest { id ->
        // Switch the day window and its Rest lookup together. Cancelling this
        // branch also cancels any lookup still running for the previous strap.
        daysFor(id).mapLatest { days ->
            val rest = if (days.isEmpty()) emptyMap() else {
                restFor(id, days.minOf { it.day }, days.maxOf { it.day })
            }
            InsightsDailySnapshot(days, rest)
        }
    }
