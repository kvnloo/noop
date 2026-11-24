package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutRealtimeOwnerTest {
    @Test
    fun publishingRecordingArmsRealtimeThroughProductionOwner() {
        val edges = mutableListOf<String>()
        val owner = WorkoutRealtimeOwner<Int>({ edges += "start" }, { edges += "stop" })

        owner.updateRecording(1)
        owner.updateRecording(2) // sample or pause update doesn't acquire again

        assertEquals(2, owner.recording.value)
        assertEquals(listOf("start"), edges)
    }

    @Test
    fun clearingViewModelReleasesRecordingExactlyOnce() {
        val edges = mutableListOf<String>()
        val owner = WorkoutRealtimeOwner<Int>({ edges += "start" }, { edges += "stop" })

        owner.updateRecording(1)
        owner.onCleared()
        owner.onCleared()
        assertEquals(listOf("start", "stop"), edges)

        // A fresh zero-to-one edge proves the recording didn't leave a counted owner behind.
        owner.request()
        assertEquals(listOf("start", "stop", "start"), edges)
    }

    @Test
    fun finishingBeforeClearDoesNotReleaseAnotherOwner() {
        val edges = mutableListOf<String>()
        val owner = WorkoutRealtimeOwner<Int>({ edges += "start" }, { edges += "stop" })

        owner.request() // visible surface
        owner.updateRecording(1)
        owner.updateRecording(null) // End or Discard
        owner.onCleared()
        assertEquals(listOf("start"), edges)

        owner.release()
        assertEquals(listOf("start", "stop"), edges)
    }
}
