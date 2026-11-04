package com.noop.ingest

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.ui.NoopPrefs
import com.noop.ui.ProfileStore
import java.util.concurrent.TimeUnit

/**
 * Background Health Connect auto-sync (fork). The existing auto-sync only catches up when NOOP is
 * opened; a phone owner who rarely opens NOOP never sees Oura/Samsung sleep land here. When auto-sync
 * is ON and the user has granted Health Connect's "read in background" permission, a periodic
 * WorkManager job runs the same [HealthConnectImporter.import] at the chosen interval.
 *
 * Without the background permission the job is a no-op, so behaviour falls back to the on-open sync
 * exactly as before. Nothing leaves the device.
 */
object HealthConnectBackgroundSync {
    const val BACKGROUND_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    private const val WORK = "noop_hc_background_sync"

    /** Self-healing schedule: cancel when auto-sync is off, else one unique periodic job (KEEP). */
    fun reschedule(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!NoopPrefs.hcAutoSync(context)) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val hours = NoopPrefs.hcSyncHours(context).coerceIn(1, 24).toLong()
        val req = PeriodicWorkRequestBuilder<HealthConnectBackgroundSyncWorker>(hours, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** The interval changed: re-anchor so the new period applies now instead of after the old one. */
    fun applyIntervalChange(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
        reschedule(context)
    }

    /** True when Health Connect reports the background-read permission as granted. */
    suspend fun backgroundGranted(context: Context): Boolean = runCatching {
        HealthConnectImporter.sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE &&
            BACKGROUND_PERMISSION in HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
    }.getOrDefault(false)

    /**
     * One background import. Returns false (and does nothing) when auto-sync is off, Health Connect is
     * missing, the background permission or every selected read type is not granted.
     */
    suspend fun runOnce(context: Context): Boolean {
        if (!NoopPrefs.hcAutoSync(context)) return false
        if (HealthConnectImporter.sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return false
        val granted = runCatching {
            HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        if (BACKGROUND_PERMISSION !in granted) return false
        val selected = HealthConnectImporter.permissionsFor(HealthConnectImporter.selectedCategories(context))
        if (granted.none { it in selected }) return false
        val repo = WhoopRepository(WhoopDatabase.get(context))
        val ok = runCatching {
            HealthConnectImporter.import(context, repo, ProfileStore.from(context).heightCm)
        }.isSuccess
        if (ok) NoopPrefs.setHcLastSync(context, System.currentTimeMillis())
        return ok
    }
}

class HealthConnectBackgroundSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        HealthConnectBackgroundSync.runOnce(applicationContext)
        // A skipped or failed run waits for the next period; retrying would only repeat the same denial.
        return Result.success()
    }
}
