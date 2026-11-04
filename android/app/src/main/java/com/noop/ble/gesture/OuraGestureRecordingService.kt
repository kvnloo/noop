package com.noop.ble.gesture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.noop.R

/** Foreground lifetime for the explicitly enabled ring standby, including screen-off diagnostics. */
class OuraGestureRecordingService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.oura_standby_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(4217, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(getString(R.string.oura_standby_channel))
            .setContentText(getString(R.string.oura_standby_notification))
            .setOngoing(true).build())
        running = true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!OuraGestureStandby.get(this).enabled) { stopSelf(); return START_NOT_STICKY }
        OuraGestureStandby.get(this).startIfEnabled()
        return START_STICKY
    }
    override fun onDestroy() {
        running = false
        OuraVoiceRecording.get(this).finish("service_stopped")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        @Volatile var running = false
            private set
        private const val CHANNEL = "noop_oura_gesture_recording"
    }
}
