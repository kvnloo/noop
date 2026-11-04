package com.noop.ingest

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import com.noop.data.AppleDaily
import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import com.noop.ui.NoopPrefs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Cancellation during Health Connect's live steps top-up must not be treated as a recoverable
 * permission, read or Room error. The existing reader is exercised with a throwing client;
 * a caller-supplied test client and proxy DAO exercise the real steps preflight and Room paths.
 * All five regressions assert observable behavior, never source-text patterns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConnectStepsCancellationTest {
    private fun invokePaginatedStepsRead(failure: Exception): Any? {
        val client = Proxy.newProxyInstance(
            HealthConnectClient::class.java.classLoader,
            arrayOf(HealthConnectClient::class.java),
        ) { _, method, _ ->
            check(method.name == "readRecords") { "Unexpected Health Connect call: ${method.name}" }
            throw failure
        } as HealthConnectClient

        // No new importer API or parity-ledger declaration: exercise the existing private reader.
        val readAll = HealthConnectImporter::class.java.declaredMethods.single {
            it.name == "readAll" && it.parameterTypes.last() == Continuation::class.java
        }
        readAll.isAccessible = true
        return readAll.invoke(
            HealthConnectImporter,
            client,
            StepsRecord::class,
            TimeRangeFilter.between(
                Instant.parse("2026-11-03T00:00:00Z"),
                Instant.parse("2026-11-04T00:00:00Z"),
            ),
            "com.noop.whoop",
            { _: StepsRecord -> error("The failing reader must not emit records") },
            object : Continuation<Boolean> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Boolean>) {
                    error("The synchronous failing client must not suspend")
                }
            },
        )
    }

    @Test
    fun cancelledStepsReadRethrowsInsteadOfReturningFalse() {
        val cancellation = CancellationException("steps refresh cancelled")
        val thrown = assertThrows(InvocationTargetException::class.java) {
            invokePaginatedStepsRead(cancellation)
        }
        assertSame(cancellation, thrown.targetException)
    }

    @Test
    fun ordinaryStepsReadFailureStillReturnsFalse() {
        assertEquals(false, invokePaginatedStepsRead(IllegalStateException("provider unavailable")))
    }

    private fun preparedContext(): Pair<Context, Map<String, *>> {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(NoopPrefs.NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        HealthConnectImporter.setSelectedCategories(
            context, setOf(HealthConnectImporter.ImportCategory.ACTIVITY),
        )
        // A cancelled refresh must preserve the existing auto-sync timestamp and every other pref.
        NoopPrefs.setHcLastSync(context, 12_345L)
        return context to preferences.all.toMap()
    }

    private fun assertNoStamp(context: Context, before: Map<String, *>) {
        val after = context.getSharedPreferences(NoopPrefs.NAME, Context.MODE_PRIVATE).all
        assertEquals("Cancelled steps refresh changed preferences (including hcLastSync)", before, after)
    }

    private fun fakeClient(
        permissionFailure: Exception? = null,
        records: List<StepsRecord> = emptyList(),
        calls: MutableList<String> = mutableListOf(),
    ): HealthConnectClient {
        val controller = Proxy.newProxyInstance(
            PermissionController::class.java.classLoader,
            arrayOf(PermissionController::class.java),
        ) { _, method, _ ->
            check(method.name == "getGrantedPermissions") {
                "Unexpected permission call: " + method.name
            }
            calls.add("permissionPreflight")
            if (permissionFailure != null) throw permissionFailure
            setOf(HealthPermission.getReadPermission(StepsRecord::class))
        } as PermissionController
        return Proxy.newProxyInstance(
            HealthConnectClient::class.java.classLoader,
            arrayOf(HealthConnectClient::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPermissionController" -> controller
                "readRecords" -> {
                    calls.add("readRecords")
                    // The SDK response constructor is internal; build this test fixture reflectively.
                    val constructor = ReadRecordsResponse::class.java.declaredConstructors.single {
                        it.parameterCount == 2
                    }
                    constructor.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    constructor.newInstance(records, null) as ReadRecordsResponse<StepsRecord>
                }
                else -> error("Unexpected Health Connect call: " + method.name)
            }
        } as HealthConnectClient
    }

    private fun repository(
        calls: MutableList<String>,
        stored: AppleDaily? = null,
        readFailure: Exception? = null,
        writeFailure: Exception? = null,
    ): WhoopRepository {
        // WhoopRepository delegates suspend reads/writes to WhoopDao. The proxy completes
        // synchronously or throws at the real DAO boundary, without requiring a Room database.
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "appleDaily" -> {
                    calls.add("appleDaily")
                    if (readFailure != null) throw readFailure
                    listOfNotNull(stored)
                }
                "upsertAppleDaily" -> {
                    calls.add("upsertAppleDaily")
                    if (writeFailure != null) throw writeFailure
                    Unit
                }
                else -> error("Unexpected Room DAO call: " + method.name)
            }
        } as WhoopDao
        return WhoopRepository(dao)
    }

    @Test
    fun cancelledPermissionPreflightPropagatesWithoutRoomOrSyncStamp() {
        val (context, before) = preparedContext()
        val cancellation = CancellationException("permission preflight cancelled")
        val daoCalls = mutableListOf<String>()
        val clientCalls = mutableListOf<String>()
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                HealthConnectImporter.refreshTodaySteps(
                    context, repository(daoCalls),
                    testClient = fakeClient(permissionFailure = cancellation, calls = clientCalls),
                )
            }
        }
        assertSame(cancellation, thrown)
        assertEquals(listOf("permissionPreflight"), clientCalls)
        assertTrue("No DAO work is allowed after cancelled permissions", daoCalls.isEmpty())
        assertNoStamp(context, before)
    }

    @Test
    fun cancelledStoredDayLookupPropagatesWithoutWriteOrSyncStamp() {
        val (context, before) = preparedContext()
        val cancellation = CancellationException("Room row lookup cancelled")
        val daoCalls = mutableListOf<String>()
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                HealthConnectImporter.refreshTodaySteps(
                    context, repository(daoCalls, readFailure = cancellation),
                    testClient = fakeClient(),
                )
            }
        }
        assertSame(cancellation, thrown)
        assertEquals(listOf("appleDaily"), daoCalls)
        assertNoStamp(context, before)
    }

    @Test
    fun cancelledRoomUpsertPropagatesWithoutReplacingStepsOrSyncStamp() {
        val (context, before) = preparedContext()
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone)
        val start = day.atStartOfDay(zone).toInstant().plusSeconds(60)
        val prior = AppleDaily(
            deviceId = HealthConnectImporter.HC_DEVICE,
            day = day.toString(),
            steps = 17,
        )
        val record = StepsRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = start.plusSeconds(120),
            endZoneOffset = zone.rules.getOffset(start.plusSeconds(120)),
            count = 42L,
            metadata = Metadata.manualEntry(),
        )
        val cancellation = CancellationException("Room upsert cancelled")
        val daoCalls = mutableListOf<String>()
        val clientCalls = mutableListOf<String>()
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                HealthConnectImporter.refreshTodaySteps(
                    context, repository(daoCalls, stored = prior, writeFailure = cancellation),
                    testClient = fakeClient(records = listOf(record), calls = clientCalls),
                )
            }
        }
        assertSame(cancellation, thrown)
        assertEquals(listOf("permissionPreflight", "readRecords"), clientCalls)
        assertEquals(listOf("appleDaily", "upsertAppleDaily"), daoCalls)
        assertEquals(17, prior.steps)
        assertNoStamp(context, before)
    }
}
