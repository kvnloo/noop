package com.noop.ble

import com.noop.ble.OuraWriteQueue.Attempt
import com.noop.ble.OuraWriteQueue.SendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Oura write queue against a fake GATT stack that behaves like Android's: one outstanding write,
 * BUSY until the completion callback. The burst below is the one the strap log showed going out after
 * auth (`sync_time`, `flush_buffer`, `get_events`, ...), where only the first write was answered.
 */
class OuraWriteQueueTest {

    /** Android-like stack: refuses with BUSY while a write is outstanding. */
    private class FakeStack {
        var busy = false
        val wire = ArrayList<String>()
        fun send(item: String): Attempt {
            if (busy) return Attempt(SendResult.BUSY, "rc=201")
            busy = true
            wire.add(item)
            return Attempt(SendResult.ACCEPTED, "rc=0")
        }
        fun complete() { busy = false }
    }

    private val burst = listOf("sync_time", "flush_buffer", "get_events", "get_battery")

    @Test
    fun burstReachesTheWireInOrderOneCompletionAtATime() {
        val stack = FakeStack()
        val q = OuraWriteQueue<String>()
        var now = 0L
        for (cmd in burst) {
            q.enqueue(cmd)
            q.pump(now, stack::send)
        }
        // Only the first write is out; the rest wait instead of being refused by the stack.
        assertEquals(listOf("sync_time"), stack.wire)
        assertEquals(3, q.size)
        repeat(3) {
            now += 10
            stack.complete()
            assertEquals(if (it == 0) "sync_time" else stack.wire.last(), q.onWriteCompleted())
            q.pump(now, stack::send)
        }
        assertEquals(burst, stack.wire)
        assertEquals(0, q.size)
    }

    @Test
    fun theOldFireAndForgetBurstLosesEverythingAfterTheFirstWrite() {
        // What the transport did before the queue: every write straight at a one-in-flight stack.
        val stack = FakeStack()
        val results = burst.map { stack.send(it).result }
        assertEquals(listOf(SendResult.ACCEPTED, SendResult.BUSY, SendResult.BUSY, SendResult.BUSY), results)
        assertEquals(listOf("sync_time"), stack.wire)
    }

    @Test
    fun busyKeepsTheHeadInPlaceAndAsksForARetry() {
        val stack = FakeStack().apply { busy = true }   // a foreign write still outstanding
        val q = OuraWriteQueue<String>()
        q.enqueue("notify_all"); q.enqueue("get_nonce")
        val r = q.pump(0, stack::send)
        assertEquals(OuraWriteQueue.BUSY_RETRY_DELAY_MS, r.nextPumpDelayMs)
        assertTrue(r.dropped.isEmpty())
        stack.complete()
        q.pump(20, stack::send)
        assertEquals(listOf("notify_all"), stack.wire)   // order preserved: notify before nonce
    }

    @Test
    fun busyRetriesAreBoundedThenTheWriteIsDroppedAndNamed() {
        val q = OuraWriteQueue<String>(maxBusyRetries = 2)
        q.enqueue("get_events"); q.enqueue("get_battery")
        var sends = 0
        val alwaysBusy: (String) -> Attempt = { sends++; Attempt(SendResult.BUSY, "rc=201") }
        assertTrue(q.pump(0, alwaysBusy).dropped.isEmpty())
        assertTrue(q.pump(20, alwaysBusy).dropped.isEmpty())
        val r = q.pump(40, alwaysBusy)
        assertEquals(listOf("get_events"), r.dropped.map { it.item })
        assertTrue(r.dropped.single().detail.contains("still busy after 2 retries"))
        // The next command then gets its own retry budget instead of being dropped with it.
        assertEquals(1, q.size)
        assertEquals(OuraWriteQueue.BUSY_RETRY_DELAY_MS, r.nextPumpDelayMs)
        assertEquals(4, sends)
    }

    @Test
    fun aHardRefusalDropsOnlyThatWrite() {
        val stack = FakeStack()
        val q = OuraWriteQueue<String>()
        q.enqueue("bad"); q.enqueue("good")
        val r = q.pump(0) { if (it == "bad") Attempt(SendResult.FAILED, "rc=6") else stack.send(it) }
        assertEquals(listOf("bad"), r.dropped.map { it.item })
        assertEquals(listOf("good"), r.sent)
    }

    @Test
    fun aMissingCompletionDoesNotStallTheQueueForever() {
        val stack = FakeStack()
        val q = OuraWriteQueue<String>()
        q.enqueue("a"); q.enqueue("b")
        val first = q.pump(0, stack::send)
        assertEquals(OuraWriteQueue.COMPLETION_TIMEOUT_MS, first.nextPumpDelayMs)
        // Before the deadline the queue waits and says how long.
        val early = q.pump(400, stack::send)
        assertTrue(early.sent.isEmpty())
        assertEquals(600L, early.nextPumpDelayMs)
        // The stack does finish (callback lost); at the deadline the queue moves on and names the write.
        stack.complete()
        val late = q.pump(OuraWriteQueue.COMPLETION_TIMEOUT_MS, stack::send)
        assertEquals("a", late.completionTimedOut)
        assertEquals(listOf("a", "b"), stack.wire)
    }

    @Test
    fun clearForgetsQueuedAndInFlightWrites() {
        val stack = FakeStack()
        val q = OuraWriteQueue<String>()
        q.enqueue("a"); q.enqueue("b")
        q.pump(0, stack::send)
        assertTrue(q.isWriteInFlight)
        q.clear()
        assertFalse(q.isWriteInFlight)
        assertEquals(0, q.size)
        assertNull(q.onWriteCompleted())
    }
}
