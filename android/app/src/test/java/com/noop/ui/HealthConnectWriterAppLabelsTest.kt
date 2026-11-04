package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Writing-APP attribution only. These values never assert a watch model was detected. */
class HealthConnectWriterAppLabelsTest {
    @Test fun recognizesCommonRecordWriterApps() {
        assertEquals("Samsung Health", healthConnectWriterAppLabels["com.sec.android.app.shealth"])
        assertEquals("Fitbit", healthConnectWriterAppLabels["com.fitbit.FitbitMobile"])
        assertEquals("Garmin Connect", healthConnectWriterAppLabels["com.garmin.android.apps.connectmobile"])
        assertEquals("Google Fit", healthConnectWriterAppLabels["com.google.android.apps.fitness"])
        assertEquals("Oura", healthConnectWriterAppLabels["com.ouraring.oura"])
        assertEquals("Strava", healthConnectWriterAppLabels["com.strava"])
    }

    @Test fun unknownWriterAppDoesNotInheritAnInventedWearable() {
        assertNull(healthConnectWriterAppLabels["org.example.unrecognised"])
        val id = "org.example.unrecognised"
        assertEquals(id, healthConnectWriterAppLabels[id] ?: id)
    }

    @Test fun labelsAreDistinctAndDoNotPretendToIdentifyWatchModels() {
        val names = healthConnectWriterAppLabels.values
        assertEquals(names.size, names.toSet().size)
        assertFalse(names.any { "watch" in it.lowercase() })
    }
}
