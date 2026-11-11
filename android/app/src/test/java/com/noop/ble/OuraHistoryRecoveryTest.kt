package com.noop.ble

import android.os.Looper
import androidx.room.Room
import com.noop.data.HrRow
import com.noop.data.StreamBatch
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.oura.GetEventsSummary
import com.noop.oura.OuraDriver
import com.noop.oura.OuraDriverPhase
import com.noop.oura.OuraHistoryDrain
import com.noop.oura.OuraEvent
import com.noop.oura.OuraTemp
import com.noop.oura.OuraIBI
import com.noop.oura.OuraRingGen
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Exercises the transport timers, not just OuraHistoryDrain's cursor arithmetic. No Bluetooth I/O. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class OuraHistoryRecoveryTest {
    private fun source(persist: (StreamBatch, String) -> Unit = { _, _ -> }): Pair<OuraLiveSource, OuraDriver> {
        val source = OuraLiveSource(RuntimeEnvironment.getApplication(), "oura-test", OuraRingGen.GEN3,
            liveSink = { _, _ -> }, authKey = { null }, persist = persist)
        val driver = OuraDriver(OuraRingGen.GEN3, authKey = null)
        set(driver, "phase", OuraDriverPhase.Streaming)
        set(source, "driver", driver)
        set(source, "reachedStreaming", true)
        set(source, "historyCursor", 100L)
        call(source, "fetchHistoryIfIdle")
        return source to driver
    }

    @Test fun silentFirstResponseReleasesFetchWithoutInventingCursorProgress() {
        val (source, driver) = source()
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            assertEquals(OuraDriverPhase.Streaming, driver.phase)
            assertEquals(100L, field(source, "historyCursor"))
        } finally { source.stop() }
    }

    @Test fun missingSummaryContinuesPastSeenBurstAndCommitsOnlyStoredProgress() {
        val (source, driver) = source()
        try {
            val drain = field(source, "drain") as OuraHistoryDrain
            drain.noteSeenRingTime(200L)
            // Emulate a batch whose anchored samples were stored. A quiet burst with no summary must
            // issue the next request instead of leaving the driver FetchingHistory forever.
            drain.noteStoredRingTime(200L, 100L)
            set(driver, "anchorRingTime", 200L)
            set(driver, "anchorUtcMs", System.currentTimeMillis())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            assertEquals(201L, field(source, "lastRequestCursor"))
            assertEquals(OuraDriverPhase.FetchingHistory, driver.phase)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            assertEquals(OuraDriverPhase.Streaming, driver.phase)
            assertEquals(200L, field(source, "historyCursor"))
        } finally { source.stop() }
    }

    @Test fun noSummaryDeadlineStillStopsAContinuouslyProgressingDrain() {
        val (source, driver) = source()
        try {
            val drain = field(source, "drain") as OuraHistoryDrain
            // Keep each request progressing, but never send a summary. The deadline must not depend
            // on receiving a 0x11 response from the very device that has stopped sending them.
            repeat(31) {
                drain.noteSeenRingTime(200L + it)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            }
            assertEquals(OuraDriverPhase.Streaming, driver.phase)
            assertEquals(100L, field(source, "historyCursor")) // seen is not stored
        } finally { source.stop() }
    }

    @Test fun lateAnchorAdvancesHistoryOnlySamplesButNotParkedLiveIbi() {
        val (source, driver) = source()
        try {
            OuraLiveSource::class.java.getDeclaredMethod("emit", List::class.java)
                .apply { isAccessible = true }.invoke(source, listOf(
                    OuraEvent.Temp(OuraTemp(200L, 36.5)), OuraEvent.Ibi(OuraIBI(300L, 1000))))
            assertEquals(0L, (field(source, "drain") as OuraHistoryDrain).maxStoredRingTime)
            set(driver, "anchorRingTime", 200L)
            set(driver, "anchorUtcMs", System.currentTimeMillis() - 10_000L)
            call(source, "drainPendingAnchorEvents")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            assertEquals(200L, field(source, "historyCursor")) // never 300 from a possibly-live IBI
        } finally { source.stop() }
    }

    @Test fun wallClockFallbackNeverAdvancesHistoryCursor() {
        val (source, _) = source()
        try {
            OuraLiveSource::class.java.getDeclaredMethod("emit", List::class.java)
                .apply { isAccessible = true }.invoke(source, listOf(OuraEvent.Temp(OuraTemp(200L, 36.5))))
            call(source, "drainPendingAnchorEvents")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            assertEquals(100L, field(source, "historyCursor"))
        } finally { source.stop() }
    }

    @Test fun finalSummaryTailIsParsedStoredAndCommittedUnderRingId() = kotlinx.coroutines.runBlocking {
        bankedBurstPersists(finalSummary = true)
    }

    @Test fun missingSummaryBurstIsParsedStoredAndCommittedUnderRingId() = kotlinx.coroutines.runBlocking {
        bankedBurstPersists(finalSummary = false)
    }

    private suspend fun bankedBurstPersists(finalSummary: Boolean) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = WhoopRepository(db)
        val (source, driver) = source { batch, id -> kotlinx.coroutines.runBlocking { repo.insert(batch, id) }; Unit }
        try {
            set(driver, "anchorRingTime", 65_538L)
            set(driver, "anchorUtcMs", System.currentTimeMillis() - 10_000L)
            if (finalSummary) {
                OuraLiveSource::class.java.getDeclaredMethod("handleHistorySummary", GetEventsSummary::class.java)
                    .apply { isAccessible = true }.invoke(source, GetEventsSummary(1, 0, false))
            }
            assertEquals("The final summary can precede its last TLVs", OuraDriverPhase.FetchingHistory, driver.phase)
            // Existing 0x60 fixture from OuraDriverTest plus a synthetic 0x46 temperature record:
            // ring-time 65538 LE, 3650 centidegrees LE. History-only temp, not possibly-live IBI,
            // advances the durable cursor. Banked IBI still materializes the ring's hrSample.
            val bytes = "6012020001007d10000000000000000000000007460602000100420e"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            OuraLiveSource::class.java.getDeclaredMethod("handleNotification", ByteArray::class.java)
                .apply { isAccessible = true }.invoke(source, bytes)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            if (!finalSummary) {
                assertEquals(65_539L, field(source, "lastRequestCursor"))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            }
            call(source, "flush")
            repo.insert(StreamBatch(hr = listOf(HrRow(123L, 65))), "my-whoop")
            assertEquals(OuraDriverPhase.Streaming, driver.phase)
            assertEquals(65_538L, field(source, "historyCursor"))
            assertEquals(65_538L, OuraHistoryCursorStore.read(RuntimeEnvironment.getApplication(), "oura-test"))
            val ringRows = db.whoopDao().hrSamples("oura-test", 0, Long.MAX_VALUE, 100)
            assertTrue("Banked IBI must materialize hrSample in Room", ringRows.isNotEmpty())
            assertTrue(ringRows.all { it.deviceId == "oura-test" })
            assertEquals(listOf(65), db.whoopDao().hrSamples("my-whoop", 0, Long.MAX_VALUE, 100).map { it.bpm })
        } finally { source.stop(); db.close() }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
    private fun set(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)
    private fun call(target: Any, name: String) = target.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(target)
}
