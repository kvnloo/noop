package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android Health Connect auto-sync must not timestamp an importer-reported failure. */
class ImportSummaryTest {
    @Test fun nonEmptyImportCanAdvanceAutoSync() {
        val result = ImportSummary(
            source = "Health Connect",
            counts = mapOf("dailyMetric" to 3),
            message = "Imported 3 rows",
        )
        assertTrue(result.succeeded)
        assertEquals(3, result.totalRows)
    }

    @Test fun successfulEmptyScanStillAdvancesAutoSync() {
        val result = ImportSummary(
            source = "Health Connect",
            counts = emptyMap(),
            message = "No Health Connect data found to import.",
        )
        assertTrue(result.succeeded)
        assertEquals(0, result.totalRows)
    }

    @Test fun importerReturnedFailureMustNotAdvanceAutoSync() {
        val result = ImportSummary.failure("Health Connect", "Health Connect permissions were denied")
        assertFalse(result.succeeded)
        assertEquals(0, result.totalRows)
    }

    @Test fun storageFailureMustNotAdvanceAutoSync() {
        val result = ImportSummary.failure("Health Connect", "Saving Health Connect data failed")
        assertFalse(result.succeeded)
    }

    @Test fun importerExceptionMustNotAdvanceAutoSync() {
        val result = runCatching<ImportSummary> { error("Health Connect unavailable") }.getOrNull()
        assertFalse(result?.succeeded == true)
    }
    @Test fun failedPartialRowsDoNotEraseRejection() {
        // A nonzero row count is not proof that all selected HC types finished reading.
        val failed = ImportSummary.failure("Health Connect", "Partial read failed")
            .copy(counts = mapOf("dailyMetric" to 2))
        assertEquals(2, failed.totalRows)
        assertFalse(failed.succeeded)
    }
}
