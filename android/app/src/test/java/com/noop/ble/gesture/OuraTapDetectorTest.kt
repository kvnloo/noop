package com.noop.ble.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Synthetic fixtures built from shapes measured on a worn Oura Ring 4 (no recorded samples are in the
 * repository): ~50 Hz samples, gravity near (-680, -480, -460) counts, idle jitter of a few counts per
 * axis, and a tap that rings at ~25 Hz on x (half as much on z) and decays within ~120 ms. Measured
 * double taps had their two impacts 140–320 ms apart and a peak jerk of 800–4200 counts.
 */
class OuraTapDetectorTest {

    /** Feeds one detector at 20 ms per sample; [tap] adds a decaying ring onto the next samples. */
    private class Fixture(seed: Long = 7, private val noise: Int = 8) {
        val bursts = ArrayList<OuraTapDetector.Burst>()
        val detector = OuraTapDetector(observer = { bursts.add(it) })
        val gestures = ArrayList<OuraTapDetector.Gesture>()
        var t = 0L
        private var seed = seed
        private var index = 0
        private val pendingX = HashMap<Int, Int>()
        private val pendingZ = HashMap<Int, Int>()
        private val motion = IntArray(3)

        /** Deterministic value in -n..n. */
        fun rnd(n: Int): Int {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            return if (n > 0) ((seed shr 8) % (2 * n + 1)).toInt() - n else 0
        }

        fun tap(amp: Int) {
            for ((k, percent) in SHAPE.withIndex()) {
                pendingX.merge(index + k, amp * percent / 100, Int::plus)
                pendingZ.merge(index + k, -(amp * percent / 200), Int::plus)
            }
        }

        /** [motionStep] > 0 adds a random-walk hand movement of up to that many counts per sample and axis. */
        fun step(n: Int = 1, motionStep: Int = 0) {
            repeat(n) {
                if (motionStep > 0) {
                    for (a in 0..2) motion[a] = (motion[a] + rnd(motionStep)).coerceIn(-600, 600)
                }
                val dx = pendingX.remove(index) ?: 0
                val dz = pendingZ.remove(index) ?: 0
                val x = -680 + dx + motion[0] + rnd(noise)
                val y = -480 + motion[1] + rnd(noise)
                val z = -460 + dz + motion[2] + rnd(noise)
                detector.sample(t, x, y, z)?.let { gestures.add(it) }
                t += 20
                index++
            }
        }
    }

    private fun double(amp: Int, spacingSamples: Int, secondAmp: Int = amp): Fixture =
        Fixture().apply {
            step(50)
            tap(amp)
            step(spacingSamples)
            tap(secondAmp)
            step(40)
        }

    private val none = emptyList<OuraTapDetector.Gesture>()

    @Test fun doubleTapsAcrossTheMeasuredIntensityAndSpacingRange() {
        for (amp in listOf(300, 700, 1500)) {           // peak jerk ≈ 800, 1900, 4000 counts
            for (spacing in listOf(7, 10, 16)) {        // 140, 200, 320 ms
                val f = double(amp, spacing)
                assertEquals("amp=$amp spacing=${spacing * 20}ms", listOf(OuraTapDetector.Gesture.DOUBLE_TAP), f.gestures)
                val burst = f.bursts.single()
                assertEquals(2, burst.impacts)
                assertEquals(spacing * 20L, burst.firstSpacingMs)
            }
        }
    }

    @Test fun aLightDoubleTapWithAWeakerSecondTapIsRecognised() {
        val f = double(amp = 300, spacingSamples = 10, secondAmp = 150)
        assertEquals(listOf(OuraTapDetector.Gesture.DOUBLE_TAP), f.gestures)
        assertTrue(f.bursts.single().peak in 700..900)
    }

    @Test fun tripleAndQuadrupleTapsAreCountedNotReportedAsDouble() {
        for ((n, expected) in listOf(3 to OuraTapDetector.Gesture.TRIPLE_TAP, 4 to OuraTapDetector.Gesture.QUADRUPLE_TAP)) {
            val f = Fixture()
            f.step(50)
            repeat(n) { f.tap(500); f.step(10) }
            f.step(40)
            assertEquals(listOf(expected), f.gestures)
        }
    }

    @Test fun quintupleAndQuadrupleNeverBecomeCallActionsAtMeasuredCadence() {
        for (n in listOf(4, 5)) {
            val f = Fixture()
            f.step(50)
            repeat(n) { f.tap(700); f.step(10) }
            f.step(50)
            assertTrue("$n taps emitted ${f.gestures}", f.gestures.none {
                it == OuraTapDetector.Gesture.DOUBLE_TAP || it == OuraTapDetector.Gesture.TRIPLE_TAP
            })
        }
    }

    @Test fun aSingleTapOrTwoTapsTooFarApartAreNotGestures() {
        val single = Fixture().apply { step(50); tap(1500); step(60) }
        assertEquals(none, single.gestures)
        assertEquals(OuraTapDetector.Verdict.IMPACT_COUNT, single.bursts.single().verdict)

        val apart = double(700, 23)                     // 460 ms, beyond the measured 320 ms
        assertEquals(none, apart.gestures)
    }

    @Test fun aDoubleTapWhileTheRingSwaysIsRejectedAsMoving() {
        // Arm swing / handling: |a| swings by ~±200 counts at 1 Hz, gently enough (≤ ~40 counts per
        // sample) to keep the jerk under the quiet level, so the burst still opens after a quiet lead-in.
        val bursts = ArrayList<OuraTapDetector.Burst>()
        val d = OuraTapDetector(observer = { bursts.add(it) })
        val gestures = ArrayList<OuraTapDetector.Gesture>()
        val ring = HashMap<Int, Int>()
        for (start in listOf(60, 70)) for ((k, p) in SHAPE.withIndex()) ring.merge(start + k, 700 * p / 100, Int::plus)
        for (i in 0 until 120) {
            val sway = (300 * kotlin.math.sin(2 * Math.PI * i / 50.0)).toInt()
            d.sample(i * 20L, -680 + sway + (ring[i] ?: 0), -480, -460)?.let { gestures.add(it) }
        }
        assertEquals(none, gestures)
        val b = bursts.single()
        assertEquals(OuraTapDetector.Verdict.MOVING, b.verdict)
        assertTrue("stillness ${b.stillness}", b.stillness > 60)
        // The same double from a still hand is a gesture, with a near-zero stillness value.
        val still = double(700, 10)
        assertEquals(listOf(OuraTapDetector.Gesture.DOUBLE_TAP), still.gestures)
        assertTrue(still.bursts.single().stillness < 20)
    }

    @Test fun aDoubleTooWeakToStandOutIsReportedButNotCounted() {
        val f = double(amp = 80, spacingSamples = 10)   // peak jerk ≈ 220: above the quiet level, below minPeak
        assertEquals(none, f.gestures)
        assertEquals(OuraTapDetector.Verdict.TOO_WEAK, f.bursts.single().verdict)
    }

    @Test fun tapsInsideContinuousMotionNeedAQuietLeadIn() {
        val f = Fixture()
        f.step(50)
        f.step(100, motionStep = 150)
        f.tap(700)
        f.step(10, motionStep = 150)
        f.tap(700)
        f.step(60)
        assertEquals(none, f.gestures)
    }

    @Test fun idleWornNoiseAndTypingLikeActivityProduceNoGestures() {
        val idle = Fixture().apply { step(3_000) }      // 60 s
        assertEquals(none, idle.gestures)

        val typing = Fixture(seed = 11)                 // ~30 s of wrist motion plus keystroke impulses
        typing.step(50)
        repeat(300) {
            typing.tap(200 + abs(typing.rnd(500)))
            typing.step(4 + abs(typing.rnd(4)), motionStep = 120)
        }
        typing.step(60)
        assertEquals(none, typing.gestures)
    }

    @Test fun backToBackDoublesAtTheRecordedCadenceAreEachRecognised() {
        val f = Fixture()
        f.step(50)
        repeat(3) { f.tap(600); f.step(10); f.tap(600); f.step(45) }   // one double every 1.1 s
        f.step(20)
        assertEquals(List(3) { OuraTapDetector.Gesture.DOUBLE_TAP }, f.gestures)
    }

    @Test fun flushClosesAnOpenBurstWhenSamplesStop() {
        val f = Fixture()
        f.step(50)
        f.tap(700)
        f.step(10)
        f.tap(700)
        f.step(6)                                       // ringing has ended, quiet tail not yet complete
        assertEquals(none, f.gestures)
        assertNull(f.detector.flush(f.t + 40))
        assertEquals(OuraTapDetector.Gesture.DOUBLE_TAP, f.detector.flush(f.t + 400))
        assertNull(f.detector.flush(f.t + 800))
    }

    @Test fun resetOrClockRollbackDiscardsAnUnfinishedBurst() {
        val f = Fixture()
        f.step(50)
        f.tap(700)
        f.step(3)
        f.detector.reset()                              // e.g., BLE disconnect
        f.step(7)
        f.tap(700)
        f.step(40)
        assertEquals(none, f.gestures)

        val g = Fixture()
        g.step(50)
        g.tap(700)
        g.step(3)
        g.detector.sample(g.t - 500, -680, -480, -460)  // monotonic clock regression
        g.step(7)
        g.tap(700)
        g.step(40)
        assertEquals(none, g.gestures)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidConfigurationIsRejected() {
        OuraTapDetector(minImpactSpacingSamples = 10, maxImpactSpacingSamples = 5)
    }

    private companion object {
        /** Percent of the tap amplitude on x for consecutive samples; z gets minus half of it. */
        val SHAPE = intArrayOf(100, -80, 55, -35, 20, -10)
    }
}
