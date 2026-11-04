package com.noop.push

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfHostedPeriodicPushWorkerTest {
    private val today = { LocalDate.of(2026, 8, 18) }
    private val zone = ZoneId.of("UTC")
    private val sourceId = "00000000-0000-4000-8000-000000000001"

    @Test fun acknowledgedAppendAndMutableDataShortCircuitWithoutTransport() = runBlocking {
        val source = FakePushSource(
            append = mutableMapOf(key(PushAppendTable.HR_SAMPLE, "a") to mutableListOf(hr(1, 100))),
            mutable = mutableMapOf(
                key(PushMutableTable.JOURNAL, "a") to mutableListOf(journal("2026-08-18", "sleep")),
            ),
        )
        val capabilities = PushCapabilities(
            setOf(PushAppendTable.HR_SAMPLE), setOf(PushMutableTable.JOURNAL),
        )
        val progress = MemoryProgress()
        val transport = AckingTransport()
        val initial = PushCoordinator(source, transport, progress, sourceId, today, zone)
            .pushKnownDevices(capabilities = capabilities)
        assertEquals(2, initial.acceptedBatches)
        assertEquals(2, initial.acceptedRecords)

        assertFalse(hasLocalUnsentPush(source, progress, sourceId, capabilities, today, zone))
        assertEquals(2, transport.batches.size)
        assertEquals(1L, progress.cursor(PushAppendTable.HR_SAMPLE, "a")?.rowId)
    }

    @Test fun newAppendRowMustPushWithoutMovingTheAckedCursor() = runBlocking {
        val rows = mutableListOf(hr(1, 100))
        val source = FakePushSource(
            append = mutableMapOf(key(PushAppendTable.HR_SAMPLE, "a") to rows),
        )
        val capabilities = PushCapabilities(setOf(PushAppendTable.HR_SAMPLE), emptySet())
        val progress = MemoryProgress()
        PushCoordinator(source, AckingTransport(), progress, sourceId, today, zone)
            .pushKnownDevices(capabilities = capabilities)
        rows += hr(2, 101)

        assertTrue(hasLocalUnsentPush(source, progress, sourceId, capabilities, today, zone))
        assertEquals(1L, progress.cursor(PushAppendTable.HR_SAMPLE, "a")?.rowId)
    }

    @Test fun editOrDeleteOfMutableDayCannotBeSkipped() = runBlocking {
        val rows = mutableListOf(journal("2026-08-18", "sleep", "old"))
        val source = FakePushSource(
            mutable = mutableMapOf(key(PushMutableTable.JOURNAL, "a") to rows),
        )
        val capabilities = PushCapabilities(emptySet(), setOf(PushMutableTable.JOURNAL))
        val progress = MemoryProgress()
        PushCoordinator(source, AckingTransport(), progress, sourceId, today, zone)
            .pushKnownDevices(capabilities = capabilities)

        rows[0] = journal("2026-08-18", "sleep", "edited")
        assertTrue(hasLocalUnsentPush(source, progress, sourceId, capabilities, today, zone))
        rows.clear()
        assertTrue(hasLocalUnsentPush(source, progress, sourceId, capabilities, today, zone))
    }

    @Test fun missingAckOrLocalReadFailureAlwaysFallsBackToNormalWorker() = runBlocking {
        val source = FakePushSource(
            append = mutableMapOf(key(PushAppendTable.HR_SAMPLE, "a") to mutableListOf(hr(1, 100))),
        )
        val capabilities = PushCapabilities(setOf(PushAppendTable.HR_SAMPLE), emptySet())
        assertTrue(hasLocalUnsentPush(source, MemoryProgress(), sourceId, capabilities, today, zone))
        source.knownDeviceIdsFailure = IllegalStateException("test read error")
        assertTrue(hasLocalUnsentPush(source, MemoryProgress(), sourceId, capabilities, today, zone))
    }

    @Test fun emptyCapabilitiesAvoidDeviceDiscovery() = runBlocking {
        val source = FakePushSource()
        assertFalse(hasLocalUnsentPush(
            source, MemoryProgress(), sourceId,
            PushCapabilities(emptySet(), emptySet()), today, zone,
        ))
        assertEquals(null, source.deviceDiscoveryCapabilities)
    }

    @Test fun cancellationStillEscapesTheReadOnlyProbe() = runBlocking {
        val source = FakePushSource()
        source.knownDeviceIdsFailure = CancellationException("cancelled")
        try {
            hasLocalUnsentPush(source, MemoryProgress(), sourceId, PushCapabilities.ALL, today, zone)
            throw AssertionError("Cancellation must escape")
        } catch (_: CancellationException) {
            // Only the private first-pending-row sentinel is consumed.
        }
    }
}
