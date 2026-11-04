package com.noop.notif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the pure reminder gate without a phone, WorkManager, BLE or user intake history. */
class HydrationReminderPolicyTest {
    @Test fun defaultAndSupportedIntervals() {
        assertEquals(2, HydrationReminderPolicy.DEFAULT_HOURS)
        for (hours in listOf(2, 3, 4)) {
            assertEquals(hours, HydrationReminderPolicy.normalizedHours(hours))
        }
    }

    @Test fun corruptOrUnsupportedIntervalFallsBackToTwoHours() {
        for (hours in listOf(-1, 0, 1, 5, 24, Int.MAX_VALUE)) {
            assertEquals(2, HydrationReminderPolicy.normalizedHours(hours))
        }
    }

    @Test fun bothUserOptInsAreRequired() {
        assertFalse(HydrationReminderPolicy.shouldNotify(false, true, 12 * 60))
        assertFalse(HydrationReminderPolicy.shouldNotify(true, false, 12 * 60))
        assertFalse(HydrationReminderPolicy.shouldNotify(false, false, 12 * 60))
        assertTrue(HydrationReminderPolicy.shouldNotify(true, true, 12 * 60))
    }

    @Test fun remindersOnlyFireWithinDaytimeWindow() {
        val policy = HydrationReminderPolicy
        assertEquals(480, policy.FIRST_MINUTE)
        assertEquals(1320, policy.END_MINUTE)
        for (minute in listOf(-1, 0, 479, 1320, 1439, 1440)) {
            assertFalse(policy.shouldNotify(true, true, minute))
        }
        for (minute in listOf(480, 481, 720, 1319)) {
            assertTrue(policy.shouldNotify(true, true, minute))
        }
    }
}
