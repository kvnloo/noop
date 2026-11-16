package com.noop.ble.gesture

import com.noop.ble.gesture.OuraGestureStandbyPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/** Always-listen decisions, the guided plan and its CSV rows, and frame-loss accounting (pure JVM). */
class OuraGestureStandbyTest {

    private fun next(
        enabled: Boolean = true, hold: Boolean = false, owns: Boolean = false, armed: Boolean = false,
        ready: Boolean = true, backoff: Boolean = true,
    ) = OuraGestureStandbyPolicy.next(enabled, hold, owns, armed, ready, backoff)

    @Test
    fun armsWhenOnAndTheRingIsReady() = assertEquals(Action.ARM, next())

    @Test
    fun reArmsAfterTheCaptureDroppedUnderneath() {
        // Link loss / re-auth / stall clears the capture: the owner no longer owns one and it is not armed.
        assertEquals(Action.ARM, next(owns = false, armed = false))
    }

    @Test
    fun waitsForTheRingAndForTheBackoff() {
        assertEquals(Action.IDLE, next(ready = false))
        assertEquals(Action.IDLE, next(backoff = false))
    }

    @Test
    fun neverStealsTheSetupScreensCapture() = assertEquals(Action.IDLE, next(owns = false, armed = true))

    @Test
    fun standsAsideForTheScreenAndWhenTurnedOff() {
        assertEquals(Action.DISARM, next(hold = true, owns = true, armed = true))
        assertEquals(Action.DISARM, next(enabled = false, owns = true, armed = true))
        assertEquals(Action.IDLE, next(enabled = false, owns = false, armed = true))
        assertEquals(Action.IDLE, next(enabled = false))
    }

    @Test
    fun backoffDoublesToACap() {
        assertEquals(10_000L, OuraGestureStandbyPolicy.backoffMs(1))
        assertEquals(20_000L, OuraGestureStandbyPolicy.backoffMs(2))
        assertEquals(120_000L, OuraGestureStandbyPolicy.backoffMs(9))
    }

    @Test
    fun oneDeliveryPerRefractoryWindow() {
        assertTrue(OuraGestureStandbyPolicy.deliverable(5_000, 0))
        assertFalse(OuraGestureStandbyPolicy.deliverable(6_000, 5_000))
        assertTrue(OuraGestureStandbyPolicy.deliverable(6_500, 5_000))
    }

    @Test
    fun shortGuidedPlanKeepsTheEssentialsAndScrollsInAboutFourMinutes() {
        val ids = OuraGuidedPlan.BLOCKS.map { it.id }
        assertEquals(listOf("double-normal", "triple-normal"), ids.take(2))
        assertEquals(ids.size, ids.toSet().size)
        for (c in listOf("double", "triple"))
            for (i in listOf("light", "normal", "firm")) assertTrue("$c-$i", "$c-$i" in ids)
        for (id in listOf("single-normal", "quadruple-normal", "quintuple-normal", "rapid-double-normal",
            "scroll-up", "scroll-down", "scroll-up-hold", "scroll-down-hold")) assertTrue(id, id in ids)
        assertEquals(0, OuraGuidedPlan.BLOCKS.count { it.kind == OuraGuidedPlan.Kind.NEGATIVE })
        assertTrue(OuraGuidedPlan.BLOCKS.filter { it.kind == OuraGuidedPlan.Kind.TAPS }.all { it.reps == 4 })
        val scrolls = OuraGuidedPlan.BLOCKS.filter { it.kind == OuraGuidedPlan.Kind.SCROLL }
        assertEquals(listOf(6, 6), scrolls.map { it.reps })
        assertEquals(listOf(5_000L, 5_000L),
            OuraGuidedPlan.BLOCKS.filter { it.kind == OuraGuidedPlan.Kind.SCROLL_HOLD }.map { it.durationMs })
        val minutes = OuraGuidedPlan.totalMs() / 60_000.0
        assertTrue("plan is $minutes min", minutes in 3.0..4.5)
        assertTrue(OuraGuidedPlan.totalMs(2) < OuraGuidedPlan.totalMs())
    }

    @Test
    fun oldPlanPositionResumesAfterTheSharedNormalBlocks() {
        assertEquals(0, OuraGuidedPlan.resumeFromLegacy(0))
        assertEquals(0, OuraGuidedPlan.resumeFromLegacy(1))
        assertEquals(2, OuraGuidedPlan.resumeFromLegacy(3))
    }

    @Test
    fun guidedCueAndRepOffsets() {
        val triple = OuraGuidedPlan.BLOCKS[1]
        assertEquals("Block 2 of ${OuraGuidedPlan.BLOCKS.size}: triple taps, normal, 4 times, one at each beep. Go.",
            OuraGuidedPlan.cue(1))
        assertEquals(List(4) { it * 2_500L }, OuraGuidedPlan.repOffsets(triple))
        val up = OuraGuidedPlan.BLOCKS.first { it.id == "scroll-up" }
        assertEquals(List(6) { it * 2_000L }, OuraGuidedPlan.repOffsets(up))
        assertEquals(emptyList<Long>(), OuraGuidedPlan.repOffsets(OuraGuidedPlan.BLOCKS.first { it.id == "scroll-up-hold" }))
    }

    @Test
    fun recorderWritesBlockBoundariesAndCues() {
        val w = StringWriter()
        val rec = OuraAcmRecorder(w, "guided", "Oura Ring 4")
        val b = OuraGuidedPlan.BLOCKS[0]
        rec.block(100, "start", 0, b)
        rec.cue(100, 0, 1)
        rec.block(25_000, "end", 0, b)
        rec.block(30_000, "skip", 10, OuraGuidedPlan.BLOCKS[10])
        rec.close()
        assertEquals(
            "# noop-oura-acm v2 label=guided gen=OuraRing4\n" +
                "k,100,start,0,double-normal,TAPS,2,normal,4,2000,8000\n" +
                "c,100,0,1\n" +
                "k,25000,end,0,double-normal,TAPS,2,normal,4,2000,8000\n" +
                "k,30000,skip,10,scroll-up,SCROLL,0,up,6,2000,12000\n",
            w.toString(),
        )
        assertEquals(1, rec.marks)
    }

    @Test
    fun frameCounterLossAccounting() {
        val wire = byteArrayOf(0x33, 0x0e, 0x32, 0x4e, 0x5e, 0x02, 0x99.toByte(), 0x02,
            0x8c.toByte(), 0x01, 0xfe.toByte(), 0x00, 0x94.toByte(), 0x00, 0xdf.toByte(), 0xfc.toByte())
        assertEquals(0x4e, OuraAcmCodec.sequence(wire))
        assertEquals(null, OuraAcmCodec.sequence(byteArrayOf(0x33, 0x01, 0x00)))
        assertEquals(0, OuraAcmCodec.framesLostBetween(10, 11))
        assertEquals(2, OuraAcmCodec.framesLostBetween(10, 13))
        assertEquals(1, OuraAcmCodec.framesLostBetween(255, 1))
        assertEquals(0, OuraAcmCodec.framesLostBetween(7, 7))
    }
}
