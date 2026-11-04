package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM oracles for repeated physical taps and their offload replays (no strap required). */
class DoubleTapEventGateTest {
    private fun gate() = DoubleTapEventGate(windowSeconds = 45L, capacity = 32)

    @Test
    fun twoRealTapsHaveTheSameEventNameButTheirReplaysDoNotActAgain() {
        val gate = gate()
        val firstTs = 1_780_910_464L
        val secondTs = firstTs + 12L
        var dispatched = 0

        // Two separate live gestures, followed by a backfill replay of each.
        for (ts in listOf(firstTs, secondTs, firstTs, secondTs)) {
            if (gate.accepts(ts)) dispatched++
        }
        assertEquals(2, dispatched)
        assertFalse(gate.accepts(firstTs))
        assertFalse(gate.accepts(secondTs))

        // A genuinely new gesture still works even when no other EVENT intervenes.
        assertTrue(gate.accepts(secondTs + 13L))
        assertEquals(3, dispatched + 1)
    }

    @Test
    fun staleMemoryExpiresAndResetStartsAFreshLink() {
        val gate = gate()
        assertTrue(gate.accepts(1_000L))
        assertFalse(gate.accepts(1_000L))
        assertTrue(gate.accepts(1_046L)) // 46 s > the 45 s live window
        assertTrue(gate.accepts(1_000L)) // the old timestamp was evicted
        gate.reset()
        assertTrue(gate.accepts(1_000L))
    }

    @Test
    fun missingTimestampOnTheLivePathFailsOpen() {
        val gate = gate()
        assertTrue(gate.accepts(null))
        assertTrue(gate.accepts(0L))
        assertTrue(gate.accepts(null))
    }

    @Test
    fun identicalLastEventTextStillPublishesDistinctTapSequences() {
        val first = LiveState(lastEvent = "DOUBLE_TAP(14)", doubleTapSeq = 1L)
        val second = first.copy(doubleTapSeq = 2L)
        assertEquals(first.lastEvent, second.lastEvent)
        assertNotEquals(first, second) // MutableStateFlow must publish the second occurrence
        assertEquals(1L, pendingDoubleTaps(first.doubleTapSeq, second.doubleTapSeq))
        assertEquals(0L, pendingDoubleTaps(second.doubleTapSeq, second.doubleTapSeq))
    }

    @Test
    fun aConflatedFlowSnapshotAccountsForEveryAcceptedTapWithoutRepeatingOldOnes() {
        assertEquals(2L, pendingDoubleTaps(lastHandled = 7L, observed = 9L))
        assertEquals(0L, pendingDoubleTaps(lastHandled = 9L, observed = 9L))
        assertEquals(0L, pendingDoubleTaps(lastHandled = 9L, observed = 8L))
        assertEquals(1L, pendingDoubleTaps(lastHandled = 9L, observed = 10L))
    }
}
