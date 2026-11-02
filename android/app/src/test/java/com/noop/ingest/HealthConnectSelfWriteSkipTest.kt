package com.noop.ingest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #528 — the importer must NOT re-ingest records NOOP itself wrote to Health Connect. Without this,
 * turning on "share back" and then importing would re-read NOOP's own daily totals and sum them on
 * top of the original source records (cumulative steps / active energy / sleep would ~double). Pins
 * the pure package-match decision used at [HealthConnectImporter] readAll's choke point.
 */
class HealthConnectSelfWriteSkipTest {

    private val self = "com.noop.whoop"

    @Test fun skipsRecordsNoopWroteItself() {
        assertTrue(HealthConnectImporter.isSelfWritten(self, self))
    }

    @Test fun keepsRecordsFromOtherSources() {
        assertFalse(HealthConnectImporter.isSelfWritten("com.google.android.apps.fitness", self))
        assertFalse(HealthConnectImporter.isSelfWritten("com.sec.android.app.shealth", self))
        assertFalse(HealthConnectImporter.isSelfWritten("com.noop.whoop.demo", self)) // different pkg
    }

    @Test fun keepsWhenOriginUnknownOrSelfBlank() {
        // Err toward keeping data: if we can't determine self, skip nothing.
        assertFalse(HealthConnectImporter.isSelfWritten("", self))
        assertFalse(HealthConnectImporter.isSelfWritten(self, ""))
    }

    @Test fun installedPackageVariantIsMatchedExactly() {
        // Release, debug, and demo are different Health Connect writer identities.
        // Match the package of the CURRENT installed app, not a release-id prefix.
        val installedDebug = "com.noop.whoop.debug"
        assertTrue(HealthConnectImporter.isSelfWritten(installedDebug, installedDebug))

        for (otherWriter in listOf(
            "com.noop.whoop",
            "com.noop.whoop.demo",
            "com.noop.whoop.debug.extra",
            "COM.NOOP.WHOOP.DEBUG",
            "com.noop.whoop.debug ",
        )) {
            assertFalse(
                "Must retain records from a different writer: $otherWriter",
                HealthConnectImporter.isSelfWritten(otherWriter, installedDebug),
            )
        }
    }

    @Test fun blankInstalledAndBlankOriginAreNotTreatedAsSelfWrites() {
        // Negative control: HealthConnectImporter.kt:1137 guards equality with selfPackage.isNotEmpty().
        // Without that guard, "" == "" would skip an unknown-origin record.
        assertFalse(HealthConnectImporter.isSelfWritten("", ""))
    }
}
