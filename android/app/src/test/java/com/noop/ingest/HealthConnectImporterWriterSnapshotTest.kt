package com.noop.ingest

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.room.Room
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.ui.NoopPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.reflect.KClass

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HealthConnectImporterWriterSnapshotTest {
    private val previous = setOf("test.writer.alpha", "test.writer.beta")
    private val seen = setOf("test.writer.alpha", "test.writer.gamma")
    private val steps = StepsRecord::class
    private val calories = TotalCaloriesBurnedRecord::class

    @Test
    fun completePopulatedImportReplacesWriterSnapshot() =
        assertImportSnapshot(emptySet(), expectedRows = 1, expected = seen)

    @Test
    fun partialPopulatedImportKeepsWriterSnapshot() =
        assertImportSnapshot(setOf(calories), expectedRows = 1, expected = previous)

    @Test
    fun allReadsFailOnEmptyCompletionAndKeepWriterSnapshot() =
        assertImportSnapshot(setOf(steps, calories), expectedRows = 0, expected = previous)

    @Test
    fun anEmptySuccessAfterAFailureDoesNotClearWriterSnapshot() =
        assertImportSnapshot(setOf(steps), expectedRows = 0, expected = previous)

    @Test
    fun completeEmptyImportClearsWriterSnapshot() =
        assertImportSnapshot(emptySet(), expectedRows = 0, expected = emptySet(), includeSteps = false)

    private fun assertImportSnapshot(
        failures: Set<KClass<out Record>>,
        expectedRows: Int,
        expected: Set<String>,
        includeSteps: Boolean = true,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(NoopPrefs.NAME, 0)
        val key = "hc.lastImportOriginPackages"
        prefs.edit().putStringSet(key, previous).apply()
        val attempted = mutableListOf<KClass<out Record>>()
        val now = Instant.now()
        val client = client { type ->
            attempted.add(type)
            if (type in failures) throw IOException("synthetic read failure")
            if (type == steps && includeSteps) {
                seen.map { writer ->
                    StepsRecord(
                        startTime = now.minusSeconds(120),
                        startZoneOffset = null,
                        endTime = now.minusSeconds(60),
                        endZoneOffset = null,
                        count = 100,
                        metadata = Metadata(dataOrigin = DataOrigin(writer)),
                    )
                }
            } else {
                emptyList()
            }
        }
        val db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).build()
        try {
            val summary = runBlocking {
                HealthConnectImporter.importFromClient(
                    context, WhoopRepository(db), 0.0,
                    setOf(HealthConnectImporter.ImportCategory.ACTIVITY),
                ) { client }
            }
            assertEquals(listOf(steps, calories), attempted)
            assertEquals(mapOf("appleDaily" to expectedRows).filterValues { it > 0 }, summary.counts)
            assertEquals(expected, prefs.getStringSet(key, null))
        } finally {
            db.close()
        }
    }

    // The fake provides real record responses but never binds to a Health Connect service.
    private fun client(read: (KClass<out Record>) -> List<Record>): HealthConnectClient {
        val permissions = object : PermissionController {
            override suspend fun getGrantedPermissions(): Set<String> =
                setOf(HealthPermission.getReadPermission(steps), HealthPermission.getReadPermission(calories))

            override suspend fun revokeAllPermissions() = error("Unexpected permission revocation")
        }
        return Proxy.newProxyInstance(
            HealthConnectClient::class.java.classLoader, arrayOf(HealthConnectClient::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getPermissionController" -> permissions
                "readRecords" -> {
                    val request = args[0] as ReadRecordsRequest<*>
                    // These members are internal to the AndroidX client in the pinned version.
                    val type = ReflectionHelpers.getField<KClass<out Record>>(request, "recordType")
                    ReflectionHelpers.callConstructor(
                        ReadRecordsResponse::class.java,
                        ReflectionHelpers.ClassParameter.from(List::class.java, read(type)),
                        ReflectionHelpers.ClassParameter.from(String::class.java, null),
                    )
                }
                else -> error("Unexpected Health Connect call: ${method.name}")
            }
        } as HealthConnectClient
    }
}
