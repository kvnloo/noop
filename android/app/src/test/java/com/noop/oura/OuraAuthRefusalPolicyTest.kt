package com.noop.oura

import com.noop.oura.OuraAuthRefusalPolicy.Action.NEEDS_PAIRING
import com.noop.oura.OuraAuthRefusalPolicy.Action.RECONNECT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OuraAuthRefusalPolicyTest {
    private var policy = OuraAuthRefusalPolicy()

    private fun authAttempt(
        status: OuraAuthStatus = OuraAuthStatus.AUTH_ERROR,
        ambiguous: Boolean = true,
        liveHRWanted: Boolean = true,
    ): OuraAuthRefusalPolicy.Action? {
        // A new driver per link, but the same policy in the transport.
        val driver = OuraDriver(ringGen = OuraRingGen.GEN3, authKey = IntArray(16) { it })
        driver.liveHRWanted = liveHRWanted
        driver.nextStep(OuraTransition.Ready)
        assertNull(policy.observe(driver.phase))
        val proof = driver.nextStep(OuraTransition.NonceReceived(IntArray(OuraAuth.nonceLength) { it + 16 }))
        assertEquals(listOf("submit_proof"), proof.map { it.label })
        if (ambiguous) {
            assertTrue(driver.nextStep(OuraTransition.NonceReceived(IntArray(OuraAuth.nonceLength) { it + 32 })).isEmpty())
        }
        driver.nextStep(OuraTransition.AuthCompleted(status))
        return policy.observe(driver.phase)
    }

    @Test
    fun secondAmbiguousRefusalStopsAutoReconnect() {
        assertEquals(RECONNECT, authAttempt(OuraAuthStatus.IN_FACTORY_RESET))
        assertEquals(NEEDS_PAIRING, authAttempt(OuraAuthStatus.IN_FACTORY_RESET))
    }

    @Test
    fun ambiguousAndPlainRefusalsShareTheBudget() {
        assertEquals(RECONNECT, authAttempt())
        assertEquals(NEEDS_PAIRING, authAttempt(ambiguous = false))
        policy = OuraAuthRefusalPolicy()
        assertEquals(RECONNECT, authAttempt(ambiguous = false))
        assertEquals(NEEDS_PAIRING, authAttempt())
    }

    @Test
    fun authSuccessReplenishesRetryWithOrWithoutLiveHR() {
        for (liveHRWanted in listOf(true, false)) {
            policy = OuraAuthRefusalPolicy()
            assertEquals(RECONNECT, authAttempt(ambiguous = false))
            assertNull(authAttempt(OuraAuthStatus.SUCCESS, liveHRWanted = liveHRWanted))
            assertEquals(RECONNECT, authAttempt())
            assertEquals(NEEDS_PAIRING, authAttempt())
        }
    }

    @Test
    fun userReconnectReplenishesRetry() {
        assertEquals(RECONNECT, authAttempt())
        assertEquals(NEEDS_PAIRING, authAttempt())
        policy.resetForUserReconnect()
        assertEquals(RECONNECT, authAttempt())
        assertEquals(NEEDS_PAIRING, authAttempt())
    }
}
