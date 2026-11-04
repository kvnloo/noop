package com.noop.ble.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/** The debug recorder's CSV and the detector's per-burst diagnostics (synthetic samples only). */
class OuraAcmRecorderTest {

    @Test
    fun writesHeaderFramesMarksGesturesAndBursts() {
        val w = StringWriter()
        val rec = OuraAcmRecorder(w, "normal", "Oura Ring 4")
        val wire = byteArrayOf(0x33, 0x0e, 0x32, 0x4e, 0x5e, 0x02, 0x99.toByte(), 0x02,
            0x8c.toByte(), 0x01, 0xfe.toByte(), 0x00, 0x94.toByte(), 0x00, 0xdf.toByte(), 0xfc.toByte())
        rec.frame(1000, wire, OuraAcmCodec.decode(wire))
        rec.frame(1040, byteArrayOf(0x33, 0x01, 0x00), emptyList())
        rec.sample(1080, 1078, 1, 2, 3)
        rec.mark(1500, "normal")
        rec.gesture(1600, "DOUBLE_TAP")
        rec.burst(OuraTapDetector.Burst(1200, 812, 150, 2, 200, 300, OuraTapDetector.Verdict.GESTURE,
            OuraTapDetector.Gesture.DOUBLE_TAP))
        rec.close()
        rec.mark(9999, "after-close")   // ignored
        assertEquals(
            "# noop-oura-acm v2 label=normal gen=OuraRing4\n" +
                "f,1000,330e324e5e0299028c01fe009400dffc,606,665,396,254,148,-801\n" +
                "f,1040,330100\n" +
                "s,1080,1078,1,2,3\n" +
                "m,1500,normal\n" +
                "g,1600,DOUBLE_TAP\n" +
                "b,1200,812,150,2,200,300,GESTURE,DOUBLE_TAP,0,-1\n",
            w.toString(),
        )
        assertEquals(2, rec.frames)
        assertEquals(1, rec.undecoded)
        assertEquals(1, rec.marks)
    }

    @Test
    fun detectorReportsBurstsWithoutChangingDecisions() {
        val bursts = ArrayList<OuraTapDetector.Burst>()
        val observed = OuraTapDetector(observer = { bursts.add(it) })
        val plain = OuraTapDetector()
        var ms = 0L
        fun feed(x: Int) {
            assertEquals(plain.sample(ms, x, 0, 0), observed.sample(ms, x, 0, 0))
            ms += 20
        }
        fun quiet(n: Int) = repeat(n) { feed(1000) }
        // A decaying ring on x, as a tap produces (jerk peaks at 1.8 x amplitude).
        fun tap(amp: Int) = intArrayOf(100, -80, 55, -35, 20, -10).forEach { feed(1000 + amp * it / 100) }
        quiet(20)
        tap(120)                    // peak jerk 216: a burst, but below minPeak
        quiet(20)
        tap(500)                    // one impact
        quiet(30)                   // longer than the 25-sample stillness window, so the hand reads still
        tap(500); quiet(4); tap(500) // two impacts 200 ms apart
        quiet(20)
        assertEquals(3, bursts.size)
        assertEquals(OuraTapDetector.Verdict.TOO_WEAK, bursts[0].verdict)
        assertEquals(216, bursts[0].peak)
        assertEquals(OuraTapDetector.Verdict.IMPACT_COUNT, bursts[1].verdict)
        assertEquals(1, bursts[1].impacts)
        assertEquals(OuraTapDetector.Verdict.GESTURE, bursts[2].verdict)
        assertEquals(OuraTapDetector.Gesture.DOUBLE_TAP, bursts[2].gesture)
        assertEquals(200L, bursts[2].firstSpacingMs)
        assertFalse(bursts.any { it.quietLevel < 150 })
        assertTrue(bursts.all { it.atMs > 0 })
    }
}
