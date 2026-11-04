package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin twin of StrandTests/OuraHistoryAdmissionTests: guards the shared-link #2523 replay. */
class OuraHistoryAdmissionTest {
    private val replay = listOf("night 0x60 +1s", "night 0x60 +2s", "night 0x60 +3s")

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
    fun testEarlyFinalSummaryKeepsBatchTailUntilQuiet() {
        val admission = OuraHistoryAdmission()
        admission.startRequest()
        admission.noteFinalSummary()
        assertTrue(admission.finalSummaryPending)
        assertEquals(replay, admission.admit(replay, whileFetching = true))
        admission.endRequest()
        assertFalse(admission.finalSummaryPending)
        assertEquals(emptyList<String>(), admission.admit(replay, whileFetching = false))
    }

    @Test
    fun testNewRequestClearsStaleFinalSummary() {
        val admission = OuraHistoryAdmission()
        admission.startRequest()
        admission.noteFinalSummary()
        admission.startRequest()
        assertFalse(admission.finalSummaryPending)
        assertEquals(replay, admission.admit(replay, whileFetching = true))
        admission.noteFinalSummary()
        admission.noteContinuationSummary()
        assertFalse(admission.finalSummaryPending)
    }

    @Test
    fun testForeignFinalSummaryCannotOpenAdmission() {
        val admission = OuraHistoryAdmission()
        admission.noteFinalSummary()
        assertFalse(admission.fetchOutstanding)
        assertFalse(admission.finalSummaryPending)
        assertEquals(emptyList<String>(), admission.admit(replay, whileFetching = true))
    }
}
