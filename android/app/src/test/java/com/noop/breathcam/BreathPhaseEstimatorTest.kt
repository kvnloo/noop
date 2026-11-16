package com.noop.breathcam

import com.noop.breathcam.BreathPhase.EXHALE
import com.noop.breathcam.BreathPhase.HOLD
import com.noop.breathcam.BreathPhase.INHALE
import com.noop.breathcam.BreathPhase.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BreathPhaseEstimatorTest {

    @Test
    fun `recovers several injected breathing rates and their phases`() {
        // Multiple values, not one (AGENTS.md): slow resonance paces, ordinary rest, and fast.
        for (bpm in listOf(4.0, 5.5, 6.0, 10.0, 15.0)) {
            val trace = BreathSynth.trace(BreathSynth.paced(bpm), 120_000)
            val est = BreathSynth.run(trace)
            val rate = est.last().rateBpm
            assertTrue("$bpm bpm: rate $rate", rate != null && abs(rate - bpm) < 0.3)
            val acc = BreathSynth.phaseAccuracy(trace, est.map { it.phase })
            assertTrue("$bpm bpm: accuracy $acc", acc >= 0.92)
        }
    }

    @Test
    fun `tracks asymmetric breaths and reports inhale and exhale durations`() {
        // 6 br/min with a 4 s inhale and a 6 s exhale, then 1:2 at 5 br/min.
        for ((inh, exh) in listOf(4_000L to 6_000L, 4_000L to 8_000L)) {
            val trace = BreathSynth.trace(BreathSynth.pattern(INHALE to inh, EXHALE to exh), 120_000)
            val est = BreathSynth.run(trace)
            val last = est.last()
            assertEquals(60_000.0 / (inh + exh), last.rateBpm!!, 0.3)
            assertEquals(inh.toDouble(), last.lastInhaleMs!!.toDouble(), 600.0)
            assertEquals(exh.toDouble(), last.lastExhaleMs!!.toDouble(), 600.0)
        }
    }

    @Test
    fun `detects holds after inhale and after exhale and tells them apart`() {
        for (holdMs in listOf(3_000L, 5_000L)) {
            val trace = BreathSynth.trace(
                BreathSynth.pattern(INHALE to 4_000, HOLD to holdMs, EXHALE to 4_000, HOLD to holdMs),
                150_000,
            )
            val est = BreathSynth.run(trace)
            val acc = BreathSynth.phaseAccuracy(trace, est.map { it.phase }, graceMs = 1_600)
            assertTrue("hold $holdMs: accuracy $acc", acc >= 0.9)
            // Hold kind: the sample in the middle of each hold after warm-up.
            var afterInhale = 0; var afterExhale = 0; var wrongKind = 0
            for (i in trace.tMs.indices) {
                if (trace.tMs[i] < 20_000 || est[i].phase != HOLD || trace.truth[i] != HOLD) continue
                val prevMoving = (i downTo 0).map { trace.truth[it] }.first { it == INHALE || it == EXHALE }
                when {
                    prevMoving == INHALE && est[i].holdKind == HoldKind.AFTER_INHALE -> afterInhale++
                    prevMoving == EXHALE && est[i].holdKind == HoldKind.AFTER_EXHALE -> afterExhale++
                    else -> wrongKind++
                }
            }
            assertTrue(afterInhale > 0 && afterExhale > 0)
            assertTrue("wrong hold kind $wrongKind", wrongKind <= (afterInhale + afterExhale) / 20)
            val cycle = 8_000 + 2 * holdMs
            assertEquals(60_000.0 / cycle, est.last().rateBpm!!, 0.25)
        }
    }

    @Test
    fun `a slow continuous breath is never called a hold`() {
        for (bpm in listOf(3.5, 4.0, 6.0)) {
            val est = BreathSynth.run(BreathSynth.trace(BreathSynth.paced(bpm), 120_000))
            assertTrue("$bpm bpm", est.drop(15 * 15).none { it.phase == HOLD })
        }
    }

    @Test
    fun `inverted motion is recovered with polarity -1`() {
        val trace = BreathSynth.trace(BreathSynth.paced(6.0), 90_000)
        val inverted = trace.copy(position = DoubleArray(trace.position.size) { -trace.position[it] })
        val wrong = BreathSynth.phaseAccuracy(trace, BreathSynth.run(inverted).map { it.phase })
        val right = BreathSynth.phaseAccuracy(trace, BreathSynth.run(inverted, polarity = -1).map { it.phase })
        assertTrue("wrong polarity should fail ($wrong)", wrong < 0.1)
        assertTrue("right polarity $right", right >= 0.92)
    }

    @Test
    fun `stays usable with sensor noise and posture drift`() {
        for (bpm in listOf(6.0, 10.0)) {
            val trace = BreathSynth.trace(BreathSynth.paced(bpm), 120_000, amplitude = 1.0, noiseSd = 0.04, driftPerMin = 1.5)
            val est = BreathSynth.run(trace)
            val acc = BreathSynth.phaseAccuracy(trace, est.map { it.phase })
            assertTrue("$bpm bpm noisy: accuracy $acc", acc >= 0.85)
            assertEquals(bpm, est.last().rateBpm!!, 0.5)
        }
    }

    @Test
    fun `a body movement goes unknown and recovers`() {
        val trace = BreathSynth.trace(BreathSynth.paced(6.0), 90_000)
        val y = trace.position.copyOf()
        // A 12 px lurch over 0.3 s at 40 s, staying shifted (they sat back).
        for (i in y.indices) {
            val t = trace.tMs[i]
            if (t >= 40_000) y[i] += if (t >= 40_300) 12.0 else 12.0 * (t - 40_000) / 300.0
        }
        val est = BreathSynth.run(trace.copy(position = y))
        val during = est.indices.filter { trace.tMs[it] in 40_200..41_000 }
        assertTrue(during.all { est[it].phase == UNKNOWN })
        val after = BreathSynth.phaseAccuracy(trace, est.map { it.phase }, warmupMs = 52_000)
        assertTrue("after recovery $after", after >= 0.9)
    }

    @Test
    fun `no motion at all is unknown with no rate`() {
        val e = BreathPhaseEstimator()
        var last: BreathEstimate? = null
        for (i in 0 until 15 * 30) last = e.offer(i * 66L, 3.0)
        assertEquals(UNKNOWN, last!!.phase)
        assertNull(last.rateBpm)
        assertEquals(0.0, last.confidence, 0.0)
    }

    @Test
    fun `lost tracking frames go unknown`() {
        val trace = BreathSynth.trace(BreathSynth.paced(6.0), 60_000)
        val e = BreathPhaseEstimator()
        val est = trace.tMs.indices.map { e.offer(trace.tMs[it], trace.position[it], valid = trace.tMs[it] !in 30_000..33_000) }
        assertTrue(est.indices.filter { trace.tMs[it] in 30_000..33_000 }.all { est[it].phase == UNKNOWN })
    }

    @Test
    fun `pacer polarity check votes from agreement`() {
        val agree = PacerPolarityCheck(minSamples = 50)
        val disagree = PacerPolarityCheck(minSamples = 50)
        for (i in 0 until 100) {
            val pacer = if ((i / 20) % 2 == 0) 1 else -1
            agree.offer(pacer, if (i % 10 == 0) -pacer else pacer)
            disagree.offer(pacer, -pacer)
        }
        assertEquals(1, agree.verdict())
        assertEquals(-1, disagree.verdict())
        assertNull(PacerPolarityCheck(minSamples = 50).verdict())
    }
}
