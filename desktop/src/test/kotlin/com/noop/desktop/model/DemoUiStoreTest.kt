package com.noop.desktop.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoUiStoreTest {
    @Test
    fun navigationAndOverlayArePureIntentReductions() {
        val store = DemoUiStore()

        store.dispatch(NoopIntent.Navigate(NoopDestination.LIVE))
        store.dispatch(NoopIntent.ToggleFrameOverlay)

        assertEquals(NoopDestination.LIVE, store.state.value.destination)
        assertTrue(store.state.value.frameOverlayVisible)
    }

    @Test
    fun disconnectClearsTransientLiveReadings() {
        val store = DemoUiStore()

        store.dispatch(NoopIntent.ToggleConnection)

        assertEquals(ConnectionPhase.DISCONNECTED, store.state.value.live.connection)
        assertNull(store.state.value.live.heartRateBpm)
        assertNull(store.state.value.live.rrMilliseconds)
        assertNull(store.state.value.live.streamAgeMilliseconds)
    }

    @Test
    fun disconnectedStoreIgnoresDemoPulse() {
        val store = DemoUiStore()
        store.dispatch(NoopIntent.ToggleConnection)
        val disconnected = store.state.value

        store.dispatch(NoopIntent.DemoPulse(monotonicNanos = 99_000_000_000L))

        assertEquals(disconnected, store.state.value)
        assertFalse(store.state.value.live.connection == ConnectionPhase.STREAMING)
    }

    @Test
    fun modelRejectsOutOfRangeNormalizedValues() {
        assertFailsWith<IllegalArgumentException> {
            MetricSnapshot("charge", "Charge", "120", "%", "invalid", 1.2f)
        }
    }
}
