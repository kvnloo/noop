package com.noop.ble

import android.content.Intent
import android.net.Uri

/**
 * Explicit local handoff into the experimental Lawnchair z0live controls.
 * This factory NEVER launches an Activity and NEVER starts microphone capture.
 * Callers must use an already-visible, user-approved UI; raw BLE callbacks
 * and background services must not call startActivity with this Intent.
 */
object OuraZ0LiveHandoff {
    private const val LAWNCHAIR_DEBUG_PACKAGE = "app.lawnchair.play.debug"
    private const val Z0LIVE_LINK_ACTIVITY =
        "app.lawnchair.zer0.voice.z0live.Z0LiveActivity\$LinkActivity"

    fun visibleControlsIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("z0live://voice"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setClassName(LAWNCHAIR_DEBUG_PACKAGE, Z0LIVE_LINK_ACTIVITY)
}
