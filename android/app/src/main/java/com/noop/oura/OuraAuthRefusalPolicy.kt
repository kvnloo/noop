package com.noop.oura

/** App reconnect policy for auth verdicts. Keep one instance across BLE links, not one per driver. */
class OuraAuthRefusalPolicy {
    enum class Action { RECONNECT, NEEDS_PAIRING }

    private var retryUsed = false

    @Synchronized
    fun resetForUserReconnect() {
        retryUsed = false
    }

    /** Observe the resulting phase after an authenticating step. A successful auth replenishes the retry allowance. */
    @Synchronized
    fun observe(phase: OuraDriverPhase): Action? = when (phase) {
        is OuraDriverPhase.AuthFailed, OuraDriverPhase.RetryableAuthFailure -> {
            if (retryUsed) {
                Action.NEEDS_PAIRING
            } else {
                retryUsed = true
                Action.RECONNECT
            }
        }
        OuraDriverPhase.EnablingLiveHR, OuraDriverPhase.Streaming -> {
            retryUsed = false
            null
        }
        else -> null
    }
}
