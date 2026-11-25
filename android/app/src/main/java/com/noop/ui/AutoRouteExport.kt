package com.noop.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.noop.analytics.RouteMath
import com.noop.data.WorkoutRow
import com.noop.ingest.RouteExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * #2682: wearer-consented automated GPX output. No exported component or third-party query API.
 * Only a chosen SAF folder can receive routes, after NOOP persists the workout itself.
 */
object AutoRouteExport {
    private const val PREFS = "auto_route_export"
    private const val FOLDER = "folder_uri"
    private const val LAST_FAILED = "last_failed"
    private const val GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun destination(context: Context): Uri? =
        prefs(context).getString(FOLDER, null)?.let(Uri::parse)

    fun enabled(context: Context): Boolean = destination(context) != null

    fun lastFailed(context: Context): Boolean =
        prefs(context).getBoolean(LAST_FAILED, false) ||
            (destination(context)?.let { !hasWriteGrant(context, it) } ?: false)

    private fun hasWriteGrant(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    /** Selecting a folder is the opt-in; a failed persisted write grant does not change consent. */
    fun chooseFolder(context: Context, uri: Uri): Boolean {
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, GRANTS)
            hasWriteGrant(context, uri)
        }.getOrDefault(false)
        if (!granted) return false

        val previous = destination(context)
        prefs(context).edit().putString(FOLDER, uri.toString()).putBoolean(LAST_FAILED, false).apply()
        if (previous != null && previous != uri) releaseIfUnused(context, previous)
        return true
    }

    /** Stop future writes; preserve shared SAF grants if Backup & Sync uses the same directory. */
    fun disable(context: Context) {
        val previous = destination(context)
        prefs(context).edit().remove(FOLDER).putBoolean(LAST_FAILED, false).apply()
        if (previous != null) releaseIfUnused(context, previous)
    }

    private fun releaseIfUnused(context: Context, uri: Uri) {
        if (BackupSyncPrefs.treeUri(context) == uri) return
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, GRANTS) }
    }

    /**
     * Called only after the WorkoutRow upsert succeeds. Render and write off Main. Skip missing or
     * invalid GPS data, never overwrite an existing document, and never record GPS or URI in logs.
     */
    suspend fun afterSaved(context: Context, row: WorkoutRow) {
        val app = context.applicationContext
        val folderUri = destination(app) ?: return
        val writeGranted = hasWriteGrant(app, folderUri)
        val plan = AutoRouteExportPolicy.plan(row, destinationSelected = true, writeGranted = writeGranted)
        if (plan == null) {
            if (!writeGranted) prefs(app).edit().putBoolean(LAST_FAILED, true).apply()
            return
        }

        val completed: Boolean? = try {
            withContext(Dispatchers.IO) {
                val bytes = RouteExport.render(
                    RouteExport.Format.GPX, plan.points, row.startTs, row.endTs, row.sport,
                    distanceM = row.distanceM, energyKcal = row.energyKcal,
                    avgHr = row.avgHr, maxHr = row.maxHr,
                )
                // Disabling/changing the destination during encoding must not open the old folder.
                if (destination(app) != folderUri) return@withContext null
                if (!hasWriteGrant(app, folderUri)) throw IOException("SAF write permission revoked")
                val folder = DocumentFile.fromTreeUri(app, folderUri)
                    ?: throw IOException("SAF folder unavailable")
                if (!folder.isDirectory || !folder.canWrite()) throw IOException("SAF folder not writable")
                val existing = folder.findFile(plan.fileName)
                if (existing != null) {
                    if (!existing.isFile) throw IOException("Export name is a directory")
                    return@withContext true
                }
                val document = folder.createFile("application/gpx+xml", plan.fileName)
                    ?: throw IOException("SAF document creation failed")
                try {
                    app.contentResolver.openOutputStream(document.uri, "wt")?.use { it.write(bytes) }
                        ?: throw IOException("SAF output stream unavailable")
                } catch (error: Exception) {
                    document.delete() // best-effort partial-file cleanup
                    throw error
                }
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (completed != null && destination(app) == folderUri) {
            prefs(app).edit().putBoolean(LAST_FAILED, !completed).apply()
        }
    }
}

/** Pure JVM-testable preflight: consent, permission, duration and actual route samples. */
internal data class AutoRouteExportPlan(val fileName: String, val points: List<RouteExport.Point>)

internal object AutoRouteExportPolicy {
    fun plan(row: WorkoutRow, destinationSelected: Boolean, writeGranted: Boolean): AutoRouteExportPlan? {
        if (!destinationSelected || !writeGranted || row.startTs <= 0 || row.endTs <= row.startTs) return null
        val polyline = row.routePolyline?.takeIf { it.isNotBlank() } ?: return null
        val route = runCatching { RouteMath.decode(polyline) }.getOrNull() ?: return null
        if (route.size < 2 || route.any {
                !it.lat.isFinite() || !it.lon.isFinite() ||
                    it.lat !in -90.0..90.0 || it.lon !in -180.0..180.0
            }) return null
        return AutoRouteExportPlan(
            fileName = "noop-route-${row.startTs}.gpx",
            points = route.map { RouteExport.Point(it.lat, it.lon) },
        )
    }
}
