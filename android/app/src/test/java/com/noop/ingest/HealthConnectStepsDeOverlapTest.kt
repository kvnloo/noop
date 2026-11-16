package com.noop.ingest

import com.noop.data.ImportSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #589 - per-SOURCE de-overlap for Health Connect steps + calories. A phone AND a watch both write the
 * same walk to Health Connect, so summing across sources double-counts (~2x). The importer sums WITHIN a
 * source (keyed by dataOrigin.packageName) and takes the MAX source per day, mirroring the de-overlap
 * already shipped on iOS/macOS (stepsBySource.values.max()) and the Android XML importer (maxOrNull()).
 * These pin the pure reduction helpers used by both [HealthConnectImporter.import] and
 * [HealthConnectImporter.refreshTodaySteps].
 */
class HealthConnectStepsDeOverlapTest {

    @Test fun twoStepSourcesTakeMaxNotSum() {
        // Phone reports 8000, watch 9500 for the same day. Summing -> 17500 (the #589 double-count);
        // de-overlap keeps the MAX source = 9500.
        val bySource = mapOf("com.google.android.apps.fitness" to 8_000L, "com.whoop.android" to 9_500L)
        assertEquals(9_500L, HealthConnectImporter.maxSourceLong(bySource))
    }

    @Test fun twoCalorieSourcesTakeMaxNotSum() {
        // Active calories: phone 420, watch 510 -> de-overlap to MAX 510, not the 930 cross-source sum.
        val bySource = mapOf("com.google.android.apps.fitness" to 420.0, "com.whoop.android" to 510.0)
        assertEquals(510.0, HealthConnectImporter.maxSourceDouble(bySource), 0.001)
    }

    @Test fun singleSourcePassesThroughUnchanged() {
        assertEquals(7_200L, HealthConnectImporter.maxSourceLong(mapOf("com.whoop.android" to 7_200L)))
        assertEquals(640.0, HealthConnectImporter.maxSourceDouble(mapOf("com.whoop.android" to 640.0)), 0.001)
    }

    @Test fun emptyMapIsZero() {
        assertEquals(0L, HealthConnectImporter.maxSourceLong(emptyMap()))
        assertEquals(0.0, HealthConnectImporter.maxSourceDouble(emptyMap()), 0.001)
    }

    @Test fun threeSourcesTakeTheSingleLargest() {
        // A phone, a watch and a bike computer all logging the same day's calories -> the largest wins,
        // never the sum of all three.
        val bySource = mapOf("phone" to 300.0, "watch" to 880.0, "bike" to 450.0)
        assertEquals(880.0, HealthConnectImporter.maxSourceDouble(bySource), 0.001)
    }

    @Test fun duplicatedCrossSourceIntervalsAreClippedAndMaxedRegardlessOfReadOrder() {
        // Workout energy uses real per-record interval clipping; step totals use
        // per-day, per-writer sums and do NOT promise interval-level deduplication.
        // Window [100,200): phone=60+30, watch=100, mirror=100, unknown=50.
        val records = listOf(
            HealthConnectImporter.KcalRecord(170, 230, 60.0, "phone"),
            HealthConnectImporter.KcalRecord(80, 220, 140.0, "watch"),
            HealthConnectImporter.KcalRecord(50, 150, 120.0, "phone"),
            HealthConnectImporter.KcalRecord(80, 220, 140.0, "mirror"), // duplicate coverage
            HealthConnectImporter.KcalRecord(110, 190, 50.0, ""), // unknown writer
        )
        assertEquals(100.0, HealthConnectImporter.sumKcalInWindow(records, 100, 200)!!, 0.001)
        assertEquals(100.0, HealthConnectImporter.sumKcalInWindow(records.reversed(), 100, 200)!!, 0.001)
    }

    @Test fun unknownWriterRetainsItsMeasuredTotal() {
        // An empty package is unlabelled provenance, not a reason to drop its value.
        assertEquals(6_100L, HealthConnectImporter.maxSourceLong(
            mapOf("phone" to 5_800L, "" to 6_100L),
        ))
        assertEquals(310.0, HealthConnectImporter.maxSourceDouble(
            mapOf("phone" to 250.0, "" to 310.0),
        ), 0.001)
    }

    @Test fun rejectedAndCompletedEmptySummariesDoNotInventColumnCoverage() {
        // Main's ImportSummary does not expose an import-success flag; #55 separately
        // owns retry/success classification. Only assert existing empty-data fields.
        val rejected = ImportSummary.failure(
            HealthConnectImporter.SOURCE, "Health Connect read failed",
        )
        val completedEmpty = ImportSummary(
            source = HealthConnectImporter.SOURCE,
            counts = emptyMap(),
            message = "No Health Connect data found to import.",
        )
        for (result in listOf(rejected, completedEmpty)) {
            assertEquals(0, result.totalRows)
            assertEquals(0, result.columnCoverageRows)
            assertTrue(result.columnCoverage.isEmpty())
            assertNull(result.firstDay)
            assertNull(result.lastDay)
        }
    }

    @Test fun disjointSameWriterRecordCannotSubtractFromWorkoutCalories() {
        // Negative control: HealthConnectImporter.kt:1336 skips overlap <= 0L.
        // Removing it would subtract 80 kcal from the same writer's 100 kcal in-window total.
        val records = listOf(
            HealthConnectImporter.KcalRecord(100, 200, 100.0, "phone"),
            HealthConnectImporter.KcalRecord(300, 400, 80.0, "phone"),
        )
        assertEquals(100.0, HealthConnectImporter.sumKcalInWindow(records, 100, 200)!!, 0.001)
    }
}
