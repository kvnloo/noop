package com.noop.ble.gesture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noop.ble.WhoopConnectionService
import com.noop.ui.NoopPrefs

/**
 * Brings "Always listen" back after a reboot or an app update, both of which kill the process that
 * held the ring capture. Does nothing unless the owner turned Always listen on. These two broadcasts
 * are among Android's documented exemptions for starting a foreground service from the background, so
 * the connection service can be re-promoted here (only when NOOP's own Background connection setting
 * allows it, as on a normal launch).
 */
class OuraGestureStandbyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val standby = OuraGestureStandby.get(context)
                if (!standby.enabled) return
                if (NoopPrefs.backgroundConnection(context)) WhoopConnectionService.start(context)
                standby.startIfEnabled()
            }
        }
    }
}
