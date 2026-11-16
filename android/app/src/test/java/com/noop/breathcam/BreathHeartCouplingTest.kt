package com.noop.breathcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

class BreathHeartCouplingTest {

    /** Breath at [bpm]; HR = 64 + [halfSwing]·(lung volume shape), delayed by [lagMs]. */
    private fun run(bpm: Double, halfSwing: Double, lagMs: Long, durationMs: Long = 120_000): BreathHeartCoupling {
        val trace = BreathSynth.trace(BreathSynth.paced(bpm), durationMs)
        val periodMs = 60_000.0 / bpm
        // Lung volume of a raised-cosine paced breath is -cos(2πt/T): HR follows it, lagMs later.
        val hr = { t: Double -> 64.0 - halfSwing * cos(2 * PI * (t - lagMs) / periodMs) }
        val beats = BreathSynth.beats(durationMs, hr)
        val e = BreathPhaseEstimator()
        val c = BreathHeartCouplingTracker()
        var bi = 0
        var lastRate: Double? = null
        for (i in trace.tMs.indices) {
            val t = trace.tMs[i]
            while (bi < beats.size && beats[bi].first <= t) { c.onBeat(beats[bi].first, beats[bi].second); bi++ }
            val est = e.offer(t, trace.position[i])
            c.onBreath(t, est)
            lastRate = est.rateBpm ?: lastRate
        }
        return c.evaluate(trace.tMs.last(), lastRate)
    }

    @Test
    fun `measures RSA amplitude for two injected swings at two rates`() {
        for ((bpm, half) in listOf(6.0 to 3.0, 6.0 to 7.0, 10.0 to 2.0, 10.0 to 5.0)) {
            val r = run(bpm, half, lagMs = 0)
            assertNotNull(r.rsaBpm)
            assertEquals("$bpm bpm swing ${2 * half}", 2 * half, r.rsaBpm!!, 0.2 * 2 * half)
            assertTrue(r.cyclesScored >= 2)
        }
    }

    @Test
    fun `recovers the breath to heart-rate lag for several injected lags`() {
        for ((bpm, lag) in listOf(6.0 to 0L, 6.0 to 1_500L, 6.0 to 3_000L, 10.0 to 1_000L)) {
            val r = run(bpm, 5.0, lag)
            assertNotNull(r.lagMs)
            assertEquals("$bpm bpm lag $lag", lag.toDouble(), r.lagMs!!.toDouble(), 600.0)
            val expectedDeg = 360.0 * lag / (60_000.0 / bpm)
            assertEquals(expectedDeg, r.phaseDeg!!, 25.0)
            assertTrue(r.correlation!! > 0.8)
        }
    }

    @Test
    fun `no rate means no lag, but RSA can still be scored`() {
        val c = BreathHeartCouplingTracker()
        assertNull(c.evaluate(10_000, null).lagMs)
        assertNull(c.evaluate(10_000, null).rsaBpm)
    }

    @Test
    fun `beat times backdate a packet from its arrival`() {
        assertEquals(listOf(8_200L, 9_000L, 10_000L), BreathHeartCouplingTracker.beatTimes(10_000, listOf(850, 800, 1000)))
    }

    /** Synthetic person: RSA peaks at [peakBpm]; they follow the pacer within ±0.2 br/min. */
    private fun seek(peakBpm: Double, startBpm: Double = 6.0, seed: Long = 5): ResonanceSeeker {
        val s = ResonanceSeeker(startBpm)
        val rnd = Random(seed)
        repeat(200) {
            val pace = s.targetBpm
            val rsa = 14.0 - 6.0 * (pace - peakBpm) * (pace - peakBpm) + 0.3 * rnd.nextGaussian()
            s.onCycle(pace + 0.2 * rnd.nextGaussian(), rsa)
        }
        return s
    }

    @Test
    fun `seeker settles on different injected resonance paces`() {
        for ((peak, start) in listOf(5.5 to 6.0, 6.5 to 6.0, 4.5 to 6.0, 5.0 to 5.5)) {
            val s = seek(peak, start)
            assertEquals(ResonanceSeeker.State.SETTLED, s.state)
            assertEquals("peak $peak", peak, s.targetBpm, 1e-9)
            assertEquals(peak, s.bestBpm()!!, 1e-9)
        }
    }

    @Test
    fun `seeker ignores breaths that did not follow the pacer`() {
        val s = ResonanceSeeker(6.0)
        repeat(20) { s.onCycle(9.0, 30.0) } // breathing at 9 while paced at 6
        assertEquals(ResonanceSeeker.State.PROBING, s.state)
        assertNull(s.meanRsaAt(6.0))
        assertEquals(0.0, s.adherence!!, 1e-9)
    }

    @Test
    fun `within the margin the pace nearer 0 degrees wins`() {
        val s = ResonanceSeeker(6.0, cyclesPerProbe = 3)
        // 6.0 and 5.5 tie on RSA; 5.5 sits at 10°, 6.0 at 70°; 6.5 is clearly worse.
        repeat(60) {
            val pace = s.targetBpm
            val (rsa, ph) = when {
                abs(pace - 6.0) < 1e-9 -> 10.0 to 70.0
                abs(pace - 5.5) < 1e-9 -> 10.1 to 10.0
                else -> 6.0 to 90.0
            }
            s.onCycle(pace, rsa, ph)
        }
        assertEquals(5.5, s.bestBpm()!!, 1e-9)
    }
}
