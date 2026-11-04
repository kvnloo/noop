package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM checks for the experimental home; no BLE or Compose runtime needed. */
class ContextualTodayScreenTest {
    @Test fun activeSessionWinsEvenWhenDisconnected() {
        assertEquals(
            ContextualPrompt.ACTIVE_SESSION,
            contextualPrompt(activeWorkout = true, connected = false, scanning = false, hasCharge = false),
        )
    }

    @Test fun scanningDoesNotOfferASecondConnection() {
        assertEquals(
            ContextualPrompt.SCANNING,
            contextualPrompt(activeWorkout = false, connected = false, scanning = true, hasCharge = false),
        )
    }

    @Test fun connectedWinsOverStaleScanningFlag() {
        // A scan can finish before its flag clears; never show a scanning prompt
        // while the connected-state badge and live data already say connected.
        assertEquals(
            ContextualPrompt.REVIEW_DAY,
            contextualPrompt(activeWorkout = false, connected = true, scanning = true, hasCharge = true),
        )
        assertEquals(
            ContextualPrompt.WAITING_FOR_SCORE,
            contextualPrompt(activeWorkout = false, connected = true, scanning = true, hasCharge = false),
        )
    }

    @Test fun scanningIsOnlyAValidPromptWhenDisconnected() {
        assertEquals(
            ContextualPrompt.SCANNING,
            contextualPrompt(activeWorkout = false, connected = false, scanning = true, hasCharge = true),
        )
    }

    @Test fun offlineIsDistinctFromMissingRecovery() {
        assertEquals(
            ContextualPrompt.CONNECT_STRAP,
            contextualPrompt(activeWorkout = false, connected = false, scanning = false, hasCharge = true),
        )
        assertEquals(
            ContextualPrompt.WAITING_FOR_SCORE,
            contextualPrompt(activeWorkout = false, connected = true, scanning = false, hasCharge = false),
        )
    }

    @Test fun scoredAndConnectedOpensTrends() {
        assertEquals(
            ContextualPrompt.REVIEW_DAY,
            contextualPrompt(activeWorkout = false, connected = true, scanning = false, hasCharge = true),
        )
    }

    @Test fun invalidScoresNeverAppearAsNumbers() {
        assertNull(visibleChargePercent(null))
        assertNull(visibleChargePercent(Double.NaN))
        assertNull(visibleChargePercent(Double.POSITIVE_INFINITY))
        assertNull(visibleChargePercent(-1.0))
        assertNull(visibleChargePercent(101.0))
        assertEquals(62, visibleChargePercent(61.6))
    }
}
