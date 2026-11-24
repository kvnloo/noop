package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin twin of StrandTests/OuraHistoryAdmissionTests: guards the shared-link #2523 replay. */
class OuraHistoryAdmissionTest {
    private val replay = listOf("night 0x60 +1s", "night 0x60 +2s", "night 0x60 +3s")

    @Test
    fun testContinuousRecordsCannotExtendDrainPastDeadline() {
        var now = 0L
        var timer = 0L
        var closedAt: Long? = null
        val admission = OuraHistoryAdmission(nowMs = { now }, onDeadline = { closedAt = now }) {
            timer = now + it
        }
        fun advanceTo(time: Long) {
            now = time
            if (timer <= now && admission.takeIdleTimeout(whileFetching = true)) closedAt = now
        }

        admission.startRequest()
        for (arrival in 0L..299_000L step 1_000L) {
            advanceTo(arrival)
            assertEquals(replay, admission.admit(replay, whileFetching = true))
        }
        advanceTo(300_000)
        assertEquals(300_000L, closedAt)
        advanceTo(301_000)
        assertEquals(emptyList<String>(), admission.admit(replay, whileFetching = true))
    }

    @Test
    fun testRecordsAtDeadlineAreRejectedEvenIfTimerHasNotRun() {
        var now = 0L
        var closed = false
        val admission = OuraHistoryAdmission(nowMs = { now }, onDeadline = { closed = true })
        admission.startRequest()
        now = 300_000
        assertEquals(emptyList<String>(), admission.admit(replay, whileFetching = true))
        assertTrue(closed)
    }

    @Test
    fun testContinuationDoesNotResetDeadline() {
        var now = 1_000_000L
        var delay = 0L
        val admission = OuraHistoryAdmission(nowMs = { now }) { delay = it }
        admission.startRequest()
        now += 298_000
        assertTrue(admission.handleSummary(moreData = true, whileFetching = true))
        assertTrue(admission.continueRequest())
        assertEquals(2_000L, delay)
        now += 2_000
        assertFalse(admission.continueRequest())
    }

    @Test
    fun testUnattributedFinalAfterRecordsFallsBackToInactivity() {
        var now = 0L
        var timer = 0L
        val admission = OuraHistoryAdmission(nowMs = { now }) { timer = now + it }
        admission.startRequest()
        now = 1_000
        assertEquals(replay, admission.admit(replay, whileFetching = true))
        now = 2_000
        assertFalse(admission.handleSummary(moreData = false, whileFetching = true))
        assertEquals("the final cannot shorten or extend inactivity", 11_000L, timer)
    }

    @Test
    fun testForeignFinalDoesNotCutOffDelayedOwnedTail() {
        var now = 0L
        var timer: Long? = null
        var timedOut = false
        val admission = OuraHistoryAdmission(nowMs = { now }) { delay -> timer = now + delay }
        fun advanceTo(time: Long) {
            now = time
            if (timer?.let { it <= now } == true) {
                timer = null
                timedOut = admission.takeIdleTimeout(whileFetching = true)
            }
        }

        admission.startRequest()
        assertEquals(replay, admission.admit(replay, whileFetching = true))
        assertFalse(admission.handleSummary(moreData = false, whileFetching = true)) // foreign final
        advanceTo(1_501)
        val tail = listOf("tail")
        assertEquals(tail, admission.admit(tail, whileFetching = true))
        advanceTo(10_000) // the original timeout was extended by the admitted tail
        assertFalse(timedOut)
        assertEquals(emptyList<String>(), admission.admit(emptyList<String>(), whileFetching = true))
        advanceTo(11_501)
        assertTrue(timedOut)
        assertEquals(emptyList<String>(), admission.admit(tail, whileFetching = true))
    }

    @Test
    fun testUnsolicitedReplayAfterOurDrainIsIgnored() {
        val admission = OuraHistoryAdmission()
        admission.startRequest()
        admission.endRequest()
        assertEquals(emptyList<String>(), admission.admit(replay, whileFetching = false))
        assertEquals(emptyList<String>(), admission.admit(replay, whileFetching = true))
    }

    @Test
    fun testOwnedDrainAcceptsAllRecordsInOrder() {
        val admission = OuraHistoryAdmission()
        admission.startRequest()
        assertEquals(replay, admission.admit(replay, whileFetching = true))
        assertEquals(emptyList<String>(), admission.admit(replay, whileFetching = false))
    }

    @Test
    fun testFinalBeforeAnyRecordsDoesNotRescheduleRequestTimeout() {
        val delays = mutableListOf<Long>()
        val admission = OuraHistoryAdmission { delays.add(it) }
        admission.startRequest()
        assertFalse(admission.handleSummary(moreData = false, whileFetching = true))
        assertEquals(listOf(10_000L), delays)
    }

    @Test
    fun testOnlyMoreDataSummaryDuringOwnedFetchCanContinue() {
        val admission = OuraHistoryAdmission()
        assertFalse(admission.handleSummary(moreData = true, whileFetching = true))
        admission.startRequest()
        assertFalse(admission.handleSummary(moreData = true, whileFetching = false))
        assertTrue(admission.handleSummary(moreData = true, whileFetching = true))
        assertFalse(admission.handleSummary(moreData = false, whileFetching = true))
    }
}
