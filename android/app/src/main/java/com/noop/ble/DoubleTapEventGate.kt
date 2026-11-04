package com.noop.ble

/**
 * Recognises one physical strap double-tap across its live EVENT and banked EVENT replay.
 *
 * The event name is identical for every tap. Keep recent strap timestamps instead, just as
 * the Apple FrameRouter does. The existing 45-second offload freshness gate still runs first;
 * this gate prevents a replay *inside* that window from triggering another action.
 */
internal class DoubleTapEventGate(
    private val windowSeconds: Long,
    private val capacity: Int,
) {
    init {
        require(windowSeconds > 0L)
        require(capacity > 0)
    }

    private val recentTimestamps = mutableListOf<Long>()

    @Synchronized
    fun accepts(eventTimestamp: Long?): Boolean {
        // A live EVENT without a usable timestamp must not silently lose the gesture.
        // The offload path already rejects missing/invalid timestamps before this call.
        if (eventTimestamp == null || eventTimestamp <= 0L) return true
        if (recentTimestamps.contains(eventTimestamp)) return false
        recentTimestamps.removeAll { kotlin.math.abs(eventTimestamp - it) > windowSeconds }
        recentTimestamps.add(eventTimestamp)
        if (recentTimestamps.size > capacity) recentTimestamps.removeAt(0)
        return true
    }

    @Synchronized
    fun reset() {
        recentTimestamps.clear()
    }
}

/**
 * Accepted physical taps since a consumer's last snapshot. StateFlow can conflate rapid
 * updates, so a receiver must consume the delta instead of just comparing EVENT strings.
 */
internal fun pendingDoubleTaps(lastHandled: Long, observed: Long): Long =
    if (observed > lastHandled) observed - lastHandled else 0L
