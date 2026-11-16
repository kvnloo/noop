package com.noop.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * The runtime permissions a BLE scan needs on this OS version. Android 12+ (API 31) uses the
 * granular Bluetooth permissions; API <= 30 falls back to fine location, which the platform
 * requires before it will hand back BLE scan results.
 */
fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** Decision for a user-initiated scan, in the order Android requires. */
internal enum class BleScanAction {
    REQUEST_PERMISSION, REQUEST_ENABLE, START_SCAN, UNAVAILABLE,
}

internal fun bleScanAction(
    permissionsGranted: Boolean,
    hasAdapter: Boolean,
    bluetoothEnabled: Boolean,
): BleScanAction = when {
    !permissionsGranted -> BleScanAction.REQUEST_PERMISSION
    !hasAdapter -> BleScanAction.UNAVAILABLE
    !bluetoothEnabled -> BleScanAction.REQUEST_ENABLE
    else -> BleScanAction.START_SCAN
}

/** The Bluetooth enable result is consent, not proof of a ready radio on its own. */
internal fun shouldResumeBleScan(accepted: Boolean, bluetoothEnabled: Boolean): Boolean =
    accepted && bluetoothEnabled

/**
 * Requests runtime BLE permissions, then the Android system Bluetooth-enable prompt if necessary,
 * before invoking [onGranted]. A denial/cancel never starts a scan; a later tap can retry.
 *
 * Keep this at the Compose boundary so every caller (including the Add Device wizard) can use the
 * ActivityResult launchers. The BLE client and ViewModel must not switch the radio on themselves.
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberRequestScan(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val perms = remember { blePermissions() }
    val onReady = rememberUpdatedState(onGranted)
    val adapter = remember(context) {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    fun radioEnabled(): Boolean = try {
        adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    val enableBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (shouldResumeBleScan(result.resultCode == Activity.RESULT_OK, radioEnabled())) {
            onReady.value()
        }
    }

    fun proceedAfterPermissions() {
        when (bleScanAction(permissionsGranted = true, hasAdapter = adapter != null,
                bluetoothEnabled = radioEnabled())) {
            BleScanAction.START_SCAN -> onReady.value()
            BleScanAction.REQUEST_ENABLE ->
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            BleScanAction.UNAVAILABLE, BleScanAction.REQUEST_PERMISSION -> Unit
        }
    }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (perms.all { results[it] == true }) proceedAfterPermissions()
    }

    return {
        if (perms.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }) {
            proceedAfterPermissions()
        } else {
            requestPermissions.launch(perms)
        }
    }
}

/**
 * The runtime permissions needed to BROADCAST as a BLE peripheral on this OS version. Android 12+
 * (API 31) needs BLUETOOTH_ADVERTISE (to advertise) plus BLUETOOTH_CONNECT (to run the GATT server);
 * API <= 30 used the install-time BLUETOOTH/BLUETOOTH_ADMIN perms, so there's nothing to request.
 */
fun bleAdvertisePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    else
        emptyArray()

/**
 * Returns a callback that enables HR broadcasting, first requesting BLUETOOTH_ADVERTISE (+ CONNECT) on
 * Android 12+ if they aren't already granted. Mirrors [rememberRequestScan]: the launcher must live in
 * the Compose layer so it can raise the system dialog, and the "Broadcast heart rate" toggle calls this
 * before turning the peripheral on. On grant (or on pre-12, where the perms are install-time) it calls
 * [onGranted]; on denial the toggle stays off and the broadcaster surfaces a status note rather than
 * failing silently.
 */
@Composable
fun rememberRequestAdvertise(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val perms = remember { bleAdvertisePermissions() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Enable only if every requested permission was granted; otherwise leave the toggle off.
        if (results.values.all { it }) onGranted()
    }
    return {
        val granted = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) onGranted() else launcher.launch(perms)
    }
}

/**
 * Requests ACCESS_FINE_LOCATION (needed for GPS-tracked workouts) and reports the outcome. Unlike
 * the BLE permissions, fine location is NOT implicitly granted on Android 12+ — BLE uses the granular
 * Bluetooth permissions there — so a GPS workout must request it explicitly before starting, or
 * `requestLocationUpdates` throws SecurityException and crashes the app. Mirrors [rememberRequestScan];
 * the launcher must live in the Compose layer so it can raise the system dialog. (#101)
 */
@Composable
fun rememberRequestLocation(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onResult(granted) }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            onResult(true)
        } else {
            launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
