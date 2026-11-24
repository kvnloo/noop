package com.noop.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** ViewModel-local workout state and its shared realtime-HR ownership. */
internal class WorkoutRealtimeOwner<Recording : Any>(
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val mutableRecording = MutableStateFlow<Recording?>(null)
    val recording = mutableRecording.asStateFlow()

    private var owners = 0
    private val workoutRealtimeLease = WorkoutRealtimeLease(onBegin = ::request, onEnd = ::release)

    // Publishing a start or restored recording always acquires HR. Sample and pause updates
    // keep the same lease, while publishing null (End/Discard) releases it.
    fun updateRecording(next: Recording?) {
        mutableRecording.value = next
        if (next == null) workoutRealtimeLease.end() else workoutRealtimeLease.begin()
    }

    fun request() {
        if (owners++ == 0) onStart()
    }

    fun release() {
        owners = (owners - 1).coerceAtLeast(0)
        if (owners == 0) onStop()
    }

    fun onCleared() {
        workoutRealtimeLease.end()
    }
}
