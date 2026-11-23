package com.noop.ble

import android.app.Application
import android.os.Looper
import com.noop.protocol.DeviceFamily
import com.noop.ui.NoopPrefs
import com.noop.ui.NotifPrefs
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class ManualWakeCaptureTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = NoopPrefs.of(context)
    private val suppressionKey = "noopContinuousHrvWakeSuppressedWindow"
    private lateinit var client: WhoopBleClient
    private var windowId = 0

    @Before
    fun setUp() {
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = (minute - 60).mod(1440)
        val end = (minute + 60).mod(1440)
        NotifPrefs.setInt(context, NotifPrefs.QUIET_START, start)
        NotifPrefs.setInt(context, NotifPrefs.QUIET_END, end)
        windowId = requireNotNull(WhoopBleClient.activeOvernightWindowId(now, start, end))
        NoopPrefs.setContinuousHrvOvernight(context, true)
        client = WhoopBleClient(context)
        // A synthetic 5/MG link lets us test post-connect reconciliation without sending BLE commands.
        ReflectionHelpers.setField(client, "connectedFamily", DeviceFamily.WHOOP5)
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) client.shutdown()
    }

    private fun connect(batteryPct: Double = 80.0) {
        val state = ReflectionHelpers.getField<MutableStateFlow<LiveState>>(client, "_state")
        state.value = LiveState(connected = true, bonded = true, batteryPct = batteryPct)
        client.setKeepStreamForData(true)
    }

    private fun realtimeArmed(): Boolean = ReflectionHelpers.getField(client, "realtimeArmed")

    @Test
    fun wakeWhileDisconnectedLeavesSameWindowAvailableOnReconnect() {
        client.setKeepStreamForData(true)
        assertFalse(realtimeArmed())

        client.stopOvernightCaptureOnManualWake()

        assertFalse(prefs.contains(suppressionKey))
        connect()
        assertTrue(realtimeArmed())
    }

    @Test
    fun wakeWhileRunningPersistsSuppressionForThisWindow() {
        connect()
        assertTrue(realtimeArmed())

        client.stopOvernightCaptureOnManualWake()

        assertEquals(windowId, prefs.getInt(suppressionKey, 0))
        assertFalse(realtimeArmed())
        connect()
        assertFalse(realtimeArmed())
    }

    @Test
    fun wakeWhileBatteryPausedDoesNotSuppressEvenWithLiveScreenStreaming() {
        client.setPauseCaptureOnPowerSave(enabled = true, thresholdPct = 20)
        connect(batteryPct = 10.0)
        assertFalse(realtimeArmed())
        client.startRealtime()
        assertTrue(realtimeArmed())

        client.stopOvernightCaptureOnManualWake()

        assertFalse(prefs.contains(suppressionKey))
        assertTrue(realtimeArmed())
        client.stopRealtime()
        assertFalse(realtimeArmed())
        client.setPauseCaptureOnPowerSave(enabled = false, thresholdPct = 20)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(realtimeArmed())
    }
}
