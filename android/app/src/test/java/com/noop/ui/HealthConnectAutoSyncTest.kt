package com.noop.ui

import com.noop.data.ImportSummary
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectAutoSyncTest {
    private class Sync {
        var lastSync = 1_000L
        var now = 10_000L
        var imports = 0
        val stamps = mutableListOf<Long>()

        suspend fun run(summary: ImportSummary) {
            syncHealthConnectImportIfStale(
                force = false,
                last = lastSync,
                now = now,
                intervalMs = 5_000L,
                import = { imports++; summary },
                stamp = { stamps.add(now); lastSync = now },
            )
        }
    }

    @Test
    fun rejectedImportLeavesStampUntouchedAndNextCallRetries() = runBlocking {
        val sync = Sync()
        sync.run(ImportSummary.failure("Health Connect", "Import rejected"))
        val stampAfterRejection = sync.lastSync

        sync.now += 1
        sync.run(ImportSummary("Health Connect", emptyMap(), message = "Nothing new"))

        assertEquals("The next foreground call must retry", 2, sync.imports)
        assertEquals(1_000L, stampAfterRejection)
        assertEquals(listOf(sync.now), sync.stamps)
    }

    @Test
    fun allReadsFailedDoesNotStamp() = runBlocking {
        val sync = Sync()
        sync.run(ImportSummary.failure("Health Connect", "All selected reads failed"))

        assertEquals(1, sync.imports)
        assertTrue(sync.stamps.isEmpty())
        assertEquals(1_000L, sync.lastSync)
    }

    @Test
    fun emptySuccessStampsAndThrottlesTheNextCall() = runBlocking {
        val sync = Sync()
        val summary = ImportSummary("Health Connect", emptyMap(), message = "Nothing new")
        sync.run(summary)
        val completionTime = sync.now
        sync.now += 1
        sync.run(summary)

        assertEquals(1, sync.imports)
        assertEquals(listOf(completionTime), sync.stamps)
        assertEquals(completionTime, sync.lastSync)
    }

    @Test
    fun partialSuccessStampsWhenOneReadContributedRows() = runBlocking {
        val sync = Sync()
        // At least one selected read completed and contributed data despite another read failing.
        sync.run(ImportSummary("Health Connect", mapOf("dailyMetric" to 1), message = "Imported one row"))

        assertEquals(1, sync.imports)
        assertEquals(listOf(sync.now), sync.stamps)
        assertEquals(sync.now, sync.lastSync)
    }

    @Test
    fun viewModelRoutesTheImportAndBothStampWritesThroughTheTestedPath() {
        val source = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .flatMap { dir ->
                sequenceOf(
                    File(dir, "app/src/main/java/com/noop/ui/AppViewModel.kt"),
                    File(dir, "android/app/src/main/java/com/noop/ui/AppViewModel.kt"),
                )
            }
            .first { it.isFile }
            .readText()
            .substringAfter("fun syncHealthConnectIfStale(")
            .substringBefore("\n    }")
        val wiring = Regex(
            """(?s)syncHealthConnectImportIfStale\(.*?import = \{.*?HealthConnectImporter\.import\(.*?stamp = \{.*?NoopPrefs\.setHcLastSync\(appContext, t\).*?_hcLastSync\.value = t""",
        )
        assertTrue("Foreground auto-sync must gate both timestamp writes on the import outcome", wiring.containsMatchIn(source))
    }
}
