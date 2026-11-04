package com.noop.ble

import com.noop.oura.OuraAuthStatus
import com.noop.oura.OuraDriverPhase
import com.noop.oura.OuraRingGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The bounded gesture-capture arm gate ([OuraLiveSource.gestureCaptureRefusal]). */
class OuraGestureCaptureGateTest {

    private fun refusal(
        seconds: Int = 120,
        gen: OuraRingGen = OuraRingGen.GEN3,
        phase: OuraDriverPhase? = OuraDriverPhase.Streaming,
        authenticated: Boolean = true,
        writeLink: Boolean = true,
        armed: Boolean = false,
    ) = OuraLiveSource.gestureCaptureRefusal(seconds, gen, phase, authenticated, writeLink, armed)

    @Test
    fun armsWhileStreaming() = assertNull(refusal())

    @Test
    fun armsOnAGen4Ring() {
        // The S25 ring answers GetProductInfo with hardware id BLB_04B, which re-points it to Gen 4.
        assertNull(refusal(gen = OuraRingGen.GEN4))
    }

    @Test
    fun armsWhileAHistoryDrainIsInFlight() {
        // The S25 capture: auth OK, live HR streaming, driver parked in FetchingHistory. That ring is
        // authenticated and connected; the drain must not lock the user out of onboarding.
        assertNull(refusal(phase = OuraDriverPhase.FetchingHistory))
    }

    @Test
    fun refusesBeforeAuthenticationAndNamesThePhase() {
        assertEquals(
            "driver phase is Authenticating, not Streaming or FetchingHistory",
            refusal(phase = OuraDriverPhase.Authenticating),
        )
        assertEquals(
            "driver phase is EnablingLiveHR, not Streaming or FetchingHistory",
            refusal(phase = OuraDriverPhase.EnablingLiveHR),
        )
        assertEquals(
            "driver phase is NeedsKeyInstall, not Streaming or FetchingHistory",
            refusal(phase = OuraDriverPhase.NeedsKeyInstall),
        )
        assertEquals(
            "driver phase is AuthFailed, not Streaming or FetchingHistory",
            refusal(phase = OuraDriverPhase.AuthFailed(OuraAuthStatus.AUTH_ERROR)),
        )
        assertEquals("driver phase is Stopped, not Streaming or FetchingHistory", refusal(phase = OuraDriverPhase.Stopped))
        assertEquals("driver phase is none, not Streaming or FetchingHistory", refusal(phase = null))
    }

    @Test
    fun keepsTheOtherPreconditions() {
        assertEquals("duration 0s is outside 1-300 s", refusal(seconds = 0))
        assertEquals("duration 301s is outside 1-300 s", refusal(seconds = 301))
        assertEquals(
            "ring generation is ${OuraRingGen.GEN5.displayName}, not Gen 3 or Gen 4",
            refusal(gen = OuraRingGen.GEN5),
        )
        assertEquals("link is not authenticated", refusal(authenticated = false))
        assertEquals("no GATT write characteristic", refusal(writeLink = false))
        assertEquals("capture is already armed", refusal(armed = true))
    }
}
