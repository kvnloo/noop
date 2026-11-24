package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ref-count behavior of a workout's own live-HR request, independent of screen visibility. */
class WorkoutRealtimeLeaseTest {
    @Test fun workoutKeepsStreamArmedAfterScreensDisappear() {
        var owners = 0
        val commands = mutableListOf<String>()
        fun request() {
            if (owners == 0) commands.add("arm")
            owners++
        }
        fun release() {
            owners = (owners - 1).coerceAtLeast(0)
            if (owners == 0) commands.add("disarm")
        }

        request() // Live tab
        val workout = WorkoutRealtimeLease(::request, ::release)
        workout.begin() // recording
        request() // workout sheet
        assertEquals(3, owners)

        release() // close workout sheet; recording remains
        release() // navigate away from Live
        assertEquals(1, owners)
        assertEquals(listOf("arm"), commands)

        workout.end() // End/Delete releases the final owner
        assertEquals(0, owners)
        assertEquals(listOf("arm", "disarm"), commands)
    }

    @Test fun startRestoreAndFinishEachChangeOwnershipOnlyOnce() {
        var starts = 0
        var stops = 0
        val workout = WorkoutRealtimeLease(onBegin = { starts++ }, onEnd = { stops++ })
        workout.begin()
        workout.begin() // repeated rehydration cannot acquire twice
        assertEquals(1, starts)
        workout.end()
        workout.end() // End plus an out-of-order cleanup cannot release twice
        assertEquals(1, stops)

        workout.begin() // a new workout needs a fresh lease
        assertEquals(2, starts)
        workout.end()
        assertEquals(2, stops)
    }
}
