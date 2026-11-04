package com.noop.ble

/**
 * One-at-a-time writer for the Oura ring's write characteristic.
 *
 * Android's GATT client keeps ONE characteristic write outstanding per connection, and that includes
 * `WRITE_TYPE_NO_RESPONSE`: until `onCharacteristicWrite` reports the previous write, the next
 * `writeCharacteristic` returns `ERROR_GATT_WRITE_REQUEST_BUSY` (API 33+) or `false` (older) and nothing
 * goes out. `OuraLiveSource` used to issue its command bursts back to back (`notify_all` + `get_nonce`;
 * `sync_time` + `flush_buffer` + `get_events` + the status reads), so every write after the first in a
 * burst could be refused while the log still printed it. A refused `get_events` leaves the driver in
 * `FetchingHistory` with no `0x11` summary ever coming, which parks the live-HR re-engage, the periodic
 * history fetch and gesture arming behind it for the rest of the session.
 *
 * Same discipline as `WhoopBleClient`'s write queue. CoreBluetooth queues writes itself, so the Swift
 * transport has no twin of this class.
 *
 * Pure and single-threaded: the caller serialises access (it synchronises on the instance), supplies the
 * send function and the clock, and schedules the next [pump] after [PumpResult.nextPumpDelayMs].
 */
internal class OuraWriteQueue<T>(
    private val maxBusyRetries: Int = MAX_BUSY_RETRIES,
    private val busyRetryDelayMs: Long = BUSY_RETRY_DELAY_MS,
    private val completionTimeoutMs: Long = COMPLETION_TIMEOUT_MS,
) {
    enum class SendResult { ACCEPTED, BUSY, FAILED }

    /** One send attempt. [detail] names what the stack returned, for a dropped write's log line. */
    data class Attempt(val result: SendResult, val detail: String)

    data class Dropped<T>(val item: T, val detail: String)

    data class PumpResult<T>(
        /** Items the stack accepted during this pump (at most one: the next waits for its completion). */
        val sent: List<T>,
        /** Items given up on: a non-busy refusal, or still busy after [maxBusyRetries] retries. */
        val dropped: List<Dropped<T>>,
        /** The write whose completion never arrived within [completionTimeoutMs]; the queue moved on. */
        val completionTimedOut: T?,
        /** When to pump again: a busy retry, or the completion deadline of the write in flight. */
        val nextPumpDelayMs: Long?,
    )

    private val pending = ArrayDeque<T>()
    private var inFlight: T? = null
    private var inFlightSinceMs = 0L
    private var busyRetries = 0

    val size: Int get() = pending.size
    val isWriteInFlight: Boolean get() = inFlight != null

    fun enqueue(item: T) {
        pending.addLast(item)
    }

    /** The stack reported the in-flight write complete. Returns that write, or null for a stray callback. */
    fun onWriteCompleted(): T? {
        val done = inFlight
        inFlight = null
        return done
    }

    /** Teardown: forget everything queued and in flight (the link they were meant for is gone). */
    fun clear() {
        pending.clear()
        inFlight = null
        busyRetries = 0
    }

    fun pump(nowMs: Long, send: (T) -> Attempt): PumpResult<T> {
        val sent = ArrayList<T>(1)
        val dropped = ArrayList<Dropped<T>>()
        var timedOut: T? = null
        val current = inFlight
        if (current != null) {
            if (nowMs - inFlightSinceMs < completionTimeoutMs) {
                val wait = if (pending.isEmpty()) null else completionTimeoutMs - (nowMs - inFlightSinceMs)
                return PumpResult(sent, dropped, null, wait)
            }
            // No completion arrived. Move on: if the stack really is still busy, the next send says so
            // and takes the bounded busy-retry path instead of stalling the queue forever.
            timedOut = current
            inFlight = null
        }
        while (pending.isNotEmpty()) {
            val head = pending.first()
            val attempt = send(head)
            when (attempt.result) {
                SendResult.ACCEPTED -> {
                    pending.removeFirst()
                    busyRetries = 0
                    inFlight = head
                    inFlightSinceMs = nowMs
                    sent.add(head)
                    val wait = if (pending.isEmpty()) null else completionTimeoutMs
                    return PumpResult(sent, dropped, timedOut, wait)
                }
                SendResult.BUSY -> {
                    if (busyRetries < maxBusyRetries) {
                        busyRetries += 1
                        // Keep the head in place: command order is part of the protocol (notify before nonce).
                        return PumpResult(sent, dropped, timedOut, busyRetryDelayMs)
                    }
                    pending.removeFirst()
                    busyRetries = 0
                    dropped.add(Dropped(head, "${attempt.detail}, still busy after $maxBusyRetries retries"))
                }
                SendResult.FAILED -> {
                    pending.removeFirst()
                    busyRetries = 0
                    dropped.add(Dropped(head, attempt.detail))
                }
            }
        }
        return PumpResult(sent, dropped, timedOut, null)
    }

    companion object {
        /** 25 x 20 ms: a write completion normally lands within one connection interval. */
        const val MAX_BUSY_RETRIES = 25
        const val BUSY_RETRY_DELAY_MS = 20L

        /** Longest wait for `onCharacteristicWrite` before the queue stops trusting the in-flight write. */
        const val COMPLETION_TIMEOUT_MS = 1_000L
    }
}
