package com.noop.ui

/**
 * The recording owns one realtime-HR request independently of its visible screens.
 * This lease is idempotent: resume/rehydration cannot double-arm, and End/Delete
 * cannot consume a screen's separate request. The ViewModel supplies the existing
 * ref-counted requestRealtimeHr/releaseRealtimeHr functions.
 *
 * Swift twin: Strand/App/WorkoutRealtimeLease.swift.
 */
internal class WorkoutRealtimeLease(
    private val onBegin: () -> Unit,
    private val onEnd: () -> Unit,
) {
    private var held = false

    fun begin() {
        if (held) return
        held = true
        onBegin()
    }

    fun end() {
        if (!held) return
        held = false
        onEnd()
    }
}
