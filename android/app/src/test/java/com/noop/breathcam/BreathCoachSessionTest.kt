package com.noop.breathcam

import kotlin.math.PI
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreathCoachSessionTest {

    /**
     * A simulated sitter who breathes at whatever pace the session suggests, whose heart-rate swing peaks
     * at [resonanceBpm]. Runs camera samples at 15 fps and R-R beats through the session, as live.
     */
    private fun simulate(resonanceBpm: Double, minutes: Int = 14): BreathCoachSession {
        val s = BreathCoachSession(startPaceBpm = 6.0, adaptPace = true)
        var theta = 0.0
        var lastBeat = 0.0
        var t = 0.0
        val dt = 1000.0 / 15
        while (t < minutes * 60_000.0) {
            val pace = s.state.suggestedPaceBpm ?: 6.0
            theta += 2 * PI * pace / 60.0 * dt / 1000.0
            val half = (7.0 - 8.0 * (pace - resonanceBpm) * (pace - resonanceBpm)).coerceAtLeast(0.5)
            val hr = 64.0 - half * cos(theta)
            if (t - lastBeat >= 60_000.0 / hr) {
                s.onRrPacket(t.toLong(), listOf((t - lastBeat).toInt()))
                lastBeat = t
            }
            val position = (1 - cos(theta)) / 2 // lung volume, 0..1, rising on the inhale
            val e = s.onEstimateFromPosition(t.toLong(), position)
            assertNotNull(e)
            t += dt
        }
        return s
    }

    private val estimators = HashMap<BreathCoachSession, BreathPhaseEstimator>()

    private fun BreathCoachSession.onEstimateFromPosition(tMs: Long, position: Double): BreathCoachState {
        val e = estimators.getOrPut(this) { BreathPhaseEstimator() }
        return onEstimate(tMs, e.offer(tMs, position))
    }

    @Test
    fun `adaptive pacer finds two different simulated resonance paces`() {
        for (res in listOf(5.0, 6.5)) {
            val s = simulate(res)
            assertEquals("resonance $res", ResonanceSeeker.State.SETTLED, s.state.seeking)
            assertEquals("resonance $res", res, s.state.suggestedPaceBpm!!, 1e-9)
            assertTrue((s.state.adherence ?: 0.0) > 0.8)
            assertEquals(res, s.state.rateBpm!!, 0.4)
            assertTrue("in-phase sim should read near 0°, was ${s.state.phaseDeg}", kotlin.math.abs(s.state.phaseDeg!!) < 40)
        }
    }
}
