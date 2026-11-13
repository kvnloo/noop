package com.noop.ble

import com.noop.data.DeviceRegistry
import com.noop.data.DeviceRegistryDao
import com.noop.data.PairedDeviceRow
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCoordinatorLifecycleTest {
    private fun device(id: String, brand: String, kind: String, status: String) = PairedDeviceRow(
        id = id, brand = brand, model = brand, nickname = null,
        sourceKind = kind, capabilities = "hr", status = status,
        addedAt = 1L, lastSeenAt = 1L, peripheralId = "$id-address",
    )

    private class Source(val deviceId: String) : LiveHrSource {
        var connections = 0
        var stops = 0
        var connected = false
        override fun connect(address: String) { connections++; connected = true }
        override fun scan() { connections++; connected = true }
        override fun stop() { stops++; connected = false }
    }

    private class Harness(var rows: List<PairedDeviceRow>) {
        val sources = mutableListOf<Source>()
        var whoopRunning = true
        var whoopStops = 0
        var whoopStarts = 0
        var whoopId = "my-whoop"
        private val dao = Proxy.newProxyInstance(
            DeviceRegistryDao::class.java.classLoader, arrayOf(DeviceRegistryDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "pairedDevices" -> rows
                "activeDeviceId" -> rows.firstOrNull { it.status == "active" }?.id
                else -> error("unexpected registry operation ${method.name}")
            }
        } as DeviceRegistryDao
        val coordinator = SourceCoordinator(
            context = null,
            registry = DeviceRegistry(dao, object : DeviceRegistry.Transactor {
                override suspend fun <R> run(block: suspend () -> R): R = block()
            }),
            repository = null,
            liveSink = { _, _ -> },
            startWhoop = { whoopRunning = true; whoopStarts++ },
            stopWhoop = { whoopRunning = false; whoopStops++ },
            setWhoopActiveDeviceId = { whoopId = it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            log = {}, straplog = {},
            sourceFactory = { id, _ -> Source(id).also { sources.add(it) } },
        )
    }

    private fun harness() = Harness(listOf(
        device("whoop-test", "WHOOP", "liveBLE", "active"),
        device("oura-test", "Oura", "oura", "paired"),
    ))

    @Test fun whoopSelectionAlsoConnectsThePairedRing() = runBlocking {
        val h = harness()
        assertTrue(h.coordinator.reconcileActiveDevice("whoop-test"))
        assertEquals("the paired ring needs its own live connection", 1, h.sources.size)
        assertTrue(h.whoopRunning)
        assertEquals("whoop-test", h.whoopId)
        assertEquals("oura-test", h.sources.single().deviceId)
        assertTrue(h.sources.single().connected)
        assertEquals(0, h.whoopStops)
    }

    @Test fun selectingTheRingDoesNotDisconnectWhoop() = runBlocking {
        val h = harness()
        h.coordinator.reconcileActiveDevice("whoop-test")
        assertTrue(h.coordinator.reconcileActiveDevice("oura-test"))
        assertEquals("selecting Oura must not tear down WHOOP", 0, h.whoopStops)
        assertTrue(h.whoopRunning)
        assertEquals("whoop-test", h.whoopId)
        assertEquals("oura-test", h.sources.single().deviceId)
        assertEquals("oura-test", h.coordinator.activeDeviceId.value)
    }

    @Test fun selectingWhoopAgainKeepsTheRingConnection() = runBlocking {
        val h = harness()
        h.coordinator.reconcileActiveDevice("oura-test")
        val ring = h.sources.single()
        assertTrue(h.coordinator.reconcileActiveDevice("whoop-test"))
        assertEquals("a UI selection is not a ring disconnect", 0, ring.stops)
        assertTrue(ring.connected)
        assertTrue(h.whoopRunning)
        assertEquals(1, ring.connections)
    }

    @Test fun anArchivedRingIsNotConnected() = runBlocking {
        val h = harness()
        h.rows = h.rows.map { if (it.sourceKind == "oura") it.copy(status = "archived") else it }
        assertTrue(h.coordinator.reconcileActiveDevice("whoop-test"))
        assertTrue(h.sources.isEmpty())
        assertEquals(0, h.whoopStops)
    }

    @Test fun anExclusiveGenericStrapStillPausesWhoop() = runBlocking {
        val h = harness()
        h.rows = h.rows + device("polar-test", "Polar", "liveBLE", "paired")
        assertTrue(h.coordinator.reconcileActiveDevice("polar-test"))
        assertFalse(h.whoopRunning)
        assertEquals("polar-test", h.sources.last().deviceId)
        assertTrue(h.coordinator.reconcileActiveDevice("whoop-test"))
        assertTrue(h.whoopRunning)
        assertEquals(1, h.sources.first().stops)
    }
}
