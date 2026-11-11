package com.noop.ble

import android.os.Looper
import com.noop.data.*
import com.noop.oura.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import android.content.Context
import android.content.SharedPreferences
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.Proxy

/** Real coordinator/source/mapping/repository, with Android shadow Bluetooth and an in-memory DAO. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SourceCoordinatorConcurrentTest {
    // The transport never authenticates here. Supply an empty test-only store rather than asking
    // Robolectric to create an Android Keystore master key or reading any real installation key.
    @Suppress("UNCHECKED_CAST")
    private fun prefsCache() = SecurePrefs::class.java.getDeclaredField("cache")
        .apply { isAccessible = true }.get(null) as MutableMap<String, SharedPreferences>
    @Before fun emptyInstallKeyStore() {
        prefsCache()["noop_oura_secure_prefs"] = RuntimeEnvironment.getApplication()
            .getSharedPreferences("empty-test-oura", Context.MODE_PRIVATE)
    }
    @After fun clearInstallKeyStore() { prefsCache().remove("noop_oura_secure_prefs") }

    private fun row(id: String, kind: SourceKind, status: DeviceStatus) = PairedDeviceRow(
        id, if (kind == SourceKind.oura) "Oura" else "WHOOP", "test", null,
        sourceKind = kind.name, capabilities = "hr,sleep", status = status.name,
        addedAt = 1, lastSeenAt = 1,
    )

    private class Harness(val rows: MutableList<PairedDeviceRow>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stored = mutableListOf<HrSample>()
        val shown = mutableListOf<Int>()
        var stops = 0
        var starts = 0
        val registryDao = Proxy.newProxyInstance(DeviceRegistryDao::class.java.classLoader,
            arrayOf(DeviceRegistryDao::class.java)) { _, method, _ ->
            when (method.name) {
                "pairedDevices" -> rows.toList()
                "activeDeviceId" -> rows.firstOrNull { it.status == "active" }?.id
                else -> error("Unexpected registry call ${method.name}")
            }
        } as DeviceRegistryDao
        val registry = DeviceRegistry(registryDao, object : DeviceRegistry.Transactor {
            override suspend fun <R> run(block: suspend () -> R): R = block()
        })
        val dao = Proxy.newProxyInstance(WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java)) { _, method, args ->
            when (method.name) {
                "insertHr" -> {
                    @Suppress("UNCHECKED_CAST") val samples = args[0] as List<HrSample>
                    stored.addAll(samples)
                    samples.map { 1L }
                }
                else -> error("Unexpected repository call ${method.name}")
            }
        } as WhoopDao
        val repo = WhoopRepository(dao)
        val coordinator = SourceCoordinator(registry, RuntimeEnvironment.getApplication(), repo,
            stopWhoop = { stops++ }, startWhoop = { starts++ }, liveSink = { hr, _ -> shown.add(hr) },
            scope = scope, log = {}, initialActiveDeviceId = rows.firstOrNull { it.status == "active" }?.id)
        fun ring() = SourceCoordinator::class.java.getDeclaredField("activeSource")
            .apply { isAccessible = true }.get(coordinator) as? OuraLiveSource
        fun close() { ring()?.stop(); scope.cancel() }
    }

    @Test fun whoopPrimaryAlsoStartsPairedOuraAndKeepsTheSameRingOnPrimarySwitches() = runBlocking {
        val h = Harness(mutableListOf(row("my-whoop", SourceKind.liveBLE, DeviceStatus.active),
            row("oura-test", SourceKind.oura, DeviceStatus.paired)))
        try {
            assertTrue(h.coordinator.reconcileActiveDevice("my-whoop"))
            val ring = h.ring()
            assertNotNull("A paired ring must run even when WHOOP is primary", ring)
            assertTrue(h.coordinator.reconcileActiveDevice("oura-test"))
            assertTrue(h.coordinator.reconcileActiveDevice("my-whoop"))
            assertSame(ring, h.ring())
            assertEquals(0, h.stops)
            assertEquals(0, h.starts) // existing WHOOP startup owns the initial connection
        } finally { h.close() }
    }

    @Test fun selectingOuraDoesNotPauseWhoopAndSamplesKeepTheirOwnDeviceIds() = runBlocking {
        val h = Harness(mutableListOf(row("my-whoop", SourceKind.liveBLE, DeviceStatus.paired),
            row("oura-test", SourceKind.oura, DeviceStatus.active)))
        try {
            assertTrue(h.coordinator.reconcileActiveDevice("oura-test"))
            assertEquals("Oura must not stop the independent WHOOP client", 0, h.stops)
            assertEquals("Ring-primary startup must re-enable the bootstrapped WHOOP client", 1, h.starts)
            val ring = requireNotNull(h.ring())
            OuraLiveSource::class.java.getDeclaredField("driver").apply { isAccessible = true }
                .set(ring, OuraDriver(OuraRingGen.GEN3, authKey = null))
            OuraLiveSource::class.java.getDeclaredMethod("emit", List::class.java)
                .apply { isAccessible = true }.invoke(ring, listOf(OuraEvent.Hr(OuraHR(200, 72, 0))))
            OuraLiveSource::class.java.getDeclaredMethod("flush").apply { isAccessible = true }.invoke(ring)
            h.repo.insert(StreamBatch(hr = listOf(HrRow(ts = 123, bpm = 65))), "my-whoop")
            assertEquals(setOf("oura-test", "my-whoop"), h.stored.map { it.deviceId }.toSet())
        } finally { h.close() }
    }

    @Test fun backgroundRingDoesNotOverwriteWhoopLiveReadout() = runBlocking {
        val h = Harness(mutableListOf(row("my-whoop", SourceKind.liveBLE, DeviceStatus.active),
            row("oura-test", SourceKind.oura, DeviceStatus.paired)))
        try {
            assertTrue(h.coordinator.reconcileActiveDevice("my-whoop"))
            val ring = requireNotNull(h.ring())
            OuraLiveSource::class.java.getDeclaredField("driver").apply { isAccessible = true }
                .set(ring, OuraDriver(OuraRingGen.GEN3, authKey = null))
            OuraLiveSource::class.java.getDeclaredMethod("emit", List::class.java)
                .apply { isAccessible = true }.invoke(ring, listOf(OuraEvent.Hr(OuraHR(200, 72, 0))))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(h.shown.isEmpty())
        } finally { h.close() }
    }

    @Test fun archivedRingIsNotStartedAndArchivingLiveRingTearsItDown() = runBlocking {
        val h = Harness(mutableListOf(row("my-whoop", SourceKind.liveBLE, DeviceStatus.active),
            row("oura-test", SourceKind.oura, DeviceStatus.paired)))
        try {
            assertTrue(h.coordinator.reconcileActiveDevice("my-whoop"))
            assertNotNull(h.ring())
            h.rows[1] = h.rows[1].copy(status = DeviceStatus.archived.name)
            assertTrue(h.coordinator.reconcileActiveDevice("my-whoop"))
            assertNull(h.ring())
            assertEquals(0, h.stops)
        } finally { h.close() }
    }
}
