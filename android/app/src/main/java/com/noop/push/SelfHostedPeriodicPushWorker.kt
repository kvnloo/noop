package com.noop.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noop.data.WhoopDatabase
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** Interrupt the read-only probe at the first would-be POST; preserve genuine cancellation. */
private class PendingLocalPush : CancellationException()

/**
 * The same cursor/day-hash decisions as real pushes, with a local-only transport.
 * Read errors fail open to the original worker, never suppress a possible export.
 */
internal suspend fun hasLocalUnsentPush(
    source: PushSnapshotSource,
    progress: PushProgressStore,
    sourceId: String,
    capabilities: PushCapabilities,
    today: () -> LocalDate,
    zoneId: ZoneId,
): Boolean {
    val localOnly = object : PushTransport {
        override suspend fun post(batch: PushBatch): PushTransportResponse = throw PendingLocalPush()
    }
    // The coordinator normally remembers discovered devices. A preflight must never mutate
    // cursors, windows, or the remembered-device set before the receiver acknowledges data.
    val readOnlyProgress = object : PushProgressStore {
        override suspend fun knownDeviceIds(): Set<String> = progress.knownDeviceIds()
        override suspend fun rememberDeviceId(deviceId: String) = Unit
        override suspend fun cursor(table: PushAppendTable, deviceId: String): PushCursor? =
            progress.cursor(table, deviceId)
        override suspend fun saveCursor(table: PushAppendTable, deviceId: String, cursor: PushCursor) =
            error("Periodic probe cannot acknowledge append data")
        override suspend fun window(table: PushMutableTable, deviceId: String): PushWindowProgress? =
            progress.window(table, deviceId)
        override suspend fun saveWindow(table: PushMutableTable, deviceId: String, value: PushWindowProgress) =
            error("Periodic probe cannot acknowledge mutable data")
    }
    return try {
        val check = PushCoordinator(source, localOnly, readOnlyProgress, sourceId, today, zoneId)
            .pushKnownDevices(maxDevices = Int.MAX_VALUE, capabilities = capabilities)
        check.rejectedBatches > 0 || check.acceptedBatches > 0 ||
            check.hasMoreAppendRows || check.hasMoreDevices
    } catch (_: PendingLocalPush) {
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        true
    }
}

/** The periodic request queues the existing acked, retryable one-time worker; never sends itself. */
class SelfHostedPeriodicPushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SelfHostedPushSettings.from(applicationContext)
        if (!settings.periodicEnabled() || settings.enabledEndpoint() == null) return Result.success()
        val snapshot = settings.snapshot()
        if (!snapshot.ready) return Result.success()

        val caughtUp = try {
            val endpoint = snapshot.endpoint
            val cached = endpoint?.let(settings::cachedCapabilities)
            if (endpoint != null && cached != null && snapshot.lastSuccessAt != null &&
                snapshot.runState == SelfHostedPushSettings.RunState.COMPLETE
            ) {
                val zone = ZoneId.systemDefault()
                val sourceId = settings.sourceId()
                val namespace = settings.progressNamespace(
                    sourceId, endpoint, cached.protocolVersion, cached.receiverStateId,
                )
                !hasLocalUnsentPush(
                    source = WhoopDatabase.get(applicationContext).pushDao(),
                    progress = EndpointScopedProgressStore(
                        SharedPrefsPushProgressStore.from(applicationContext), namespace,
                    ),
                    sourceId = sourceId,
                    capabilities = cached,
                    today = { LocalDate.now(zone) },
                    zoneId = zone,
                )
            } else false // No successful acknowledgement or receiver identity: normal catch-up.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false // A probe error never hides a pending export.
        }
        if (!caughtUp) SelfHostedPushScheduler.enqueuePeriodicCatchUp(applicationContext)
        return Result.success()
    }
}
