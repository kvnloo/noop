package com.noop.breathcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class BreathMotionTest {

    private val w = 120
    private val h = 160

    @Test
    fun `every rotation yields the same upright profile`() {
        val roi = UprightRect(0.1, 0.4, 0.9, 0.95)
        val upright = UprightMapping(0, w, h).verticalProfile(BreathSynth.frame(w, h, 0.0, 0), roi)
        for (r in listOf(90, 180, 270)) {
            val f = BreathSynth.frame(w, h, 0.0, r)
            val m = UprightMapping(r, f.width, f.height)
            assertEquals(w, m.uprightWidth)
            assertEquals(h, m.uprightHeight)
            val p = m.verticalProfile(f, roi)
            assertEquals(upright.size, p.size)
            for (i in p.indices) assertEquals("rotation $r row $i", upright[i], p[i], 1e-9)
        }
    }

    @Test
    fun `tracker recovers several sub-pixel shifts, in both directions, at every rotation`() {
        val roi = BreathRegions.strips[3]
        for (r in listOf(0, 90, 270)) {
            val m = if (r % 180 == 0) UprightMapping(r, w, h) else UprightMapping(r, h, w)
            for (d in listOf(-2.6, -1.1, -0.35, 0.0, 0.4, 1.3, 2.8)) {
                val tr = ProfileShiftTracker()
                tr.offer(m.verticalProfile(BreathSynth.frame(w, h, 0.0, r), roi))
                val s = tr.offer(m.verticalProfile(BreathSynth.frame(w, h, d, r), roi))
                assertTrue("rotation $r shift $d valid", s.valid)
                assertEquals("rotation $r shift $d", d, s.displacementUpPx, 0.12)
            }
        }
    }

    @Test
    fun `tracker keeps accumulating past the search window by re-keying`() {
        val roi = BreathRegions.strips[1]
        val m = UprightMapping(0, w, h)
        val tr = ProfileShiftTracker()
        var last = ShiftSample(0.0, 1.0, false)
        var d = 0.0
        while (d <= 14.0) {
            last = tr.offer(m.verticalProfile(BreathSynth.frame(w, h, d, 0), roi))
            d += 0.7
        }
        assertEquals(d - 0.7, last.displacementUpPx, 0.6)
    }

    @Test
    fun `a flat region is not tracked`() {
        val f = LumaFrame(ByteArray(w * h) { 90 }, w, h)
        val tr = ProfileShiftTracker()
        val m = UprightMapping(0, w, h)
        tr.offer(m.verticalProfile(f, BreathRegions.strips[0]))
        assertFalse(tr.offer(m.verticalProfile(f, BreathRegions.strips[0])).valid)
    }

    @Test
    fun `selector moves to the strip that carries the breathing`() {
        val sel = StripSelector(3)
        val rnd = Random(3)
        var picked = -1
        for (i in 0 until 15 * 40) {
            val t = i * 1000L / 15
            val breath = kotlin.math.sin(2 * Math.PI * 0.1 * t / 1000.0)
            picked = sel.offer(
                t,
                doubleArrayOf(0.3 * rnd.nextGaussian(), 0.3 * rnd.nextGaussian(), breath + 0.05 * rnd.nextGaussian()),
            )
        }
        assertEquals(2, picked)
        assertTrue(abs(sel.scores[2]) > 5 * sel.scores[0])
    }

    @Test
    fun `pipeline recovers breathing rate from rendered torso frames at two rates`() {
        for ((bpm, rotation) in listOf(6.0 to 270, 10.0 to 90)) {
            val trace = BreathSynth.trace(BreathSynth.paced(bpm), 90_000, amplitude = 1.2)
            val p = BreathCameraPipeline()
            val rnd = Random(11)
            val est = trace.tMs.indices.map {
                p.onFrame(BreathSynth.frame(w, h, trace.position[it], rotation, noiseSd = 2.0, rnd = rnd), rotation, trace.tMs[it])
            }
            val rate = est.last().rateBpm
            assertTrue("$bpm bpm: rate was $rate", rate != null && abs(rate - bpm) < 0.5)
            val acc = BreathSynth.phaseAccuracy(trace, est.map { it.phase }, warmupMs = 20_000)
            assertTrue("$bpm bpm: phase accuracy $acc", acc >= 0.85)
        }
    }
}
