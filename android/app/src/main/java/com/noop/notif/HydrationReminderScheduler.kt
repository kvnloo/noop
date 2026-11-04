package com.noop.notif

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.R
import com.noop.ui.NoopPrefs
import com.noop.ui.appLaunchIntent
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Pure local-time and opt-in gate for the noncritical hydration reminder (#870). */
internal object HydrationReminderPolicy {
    const val FIRST_MINUTE = 8 * 60
    const val END_MINUTE = 22 * 60
    const val DEFAULT_HOURS = 2
    val INTERVAL_HOURS = listOf(2, 3, 4)

    /** Reject old/corrupt prefs rather than scheduling a surprising sub-hour reminder. */
    fun normalizedHours(hours: Int): Int =
        if (hours in INTERVAL_HOURS) hours else DEFAULT_HOURS

    /** No reminders overnight, if either opt-in is off, or for an invalid clock minute. */
    fun shouldNotify(enabled: Boolean, hydrationTracking: Boolean, minuteOfDay: Int): Boolean =
        enabled && hydrationTracking && minuteOfDay in FIRST_MINUTE until END_MINUTE
}

/** Local-only preferences: reminders are off until explicitly enabled in Hydration settings. */
internal class HydrationReminderSettings(private val prefs: SharedPreferences) {
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    var intervalHours: Int
        get() = HydrationReminderPolicy.normalizedHours(
            prefs.getInt("intervalHours", HydrationReminderPolicy.DEFAULT_HOURS),
        )
        set(value) {
            prefs.edit().putInt("intervalHours", HydrationReminderPolicy.normalizedHours(value)).apply()
        }

    companion object {
        fun from(context: Context): HydrationReminderSettings =
            HydrationReminderSettings(
                context.applicationContext.getSharedPreferences(
                    "noop_hydration_reminders", Context.MODE_PRIVATE,
                ),
            )
    }
}

/**
 * Non-exact WorkManager reminder. Unique work is durable across reboot/app kill; KEEP preserves
 * the original interval when the app launches. It never uses strap BLE, user intake data, or a server.
 */
internal object HydrationReminderScheduler {
    private const val WORK_NAME = "noop_hydration_reminder"

    fun reschedule(context: Context) {
        val app = context.applicationContext
        val work = WorkManager.getInstance(app)
        val settings = HydrationReminderSettings.from(app)
        if (!settings.enabled || !NoopPrefs.hydrationTracking(app)) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val hours = settings.intervalHours.toLong()
        val request = PeriodicWorkRequestBuilder<HydrationReminderWorker>(hours, TimeUnit.HOURS)
            .setInitialDelay(hours, TimeUnit.HOURS)
            .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Replace the old period only when the user changes the interval. */
    fun applyIntervalChange(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        reschedule(context)
    }
}

/** Posts a phone-only reminder if the current opt-ins, wall-clock window and permission allow it. */
class HydrationReminderWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val app = applicationContext
        val settings = HydrationReminderSettings.from(app)
        val now = LocalTime.now()
        if (!HydrationReminderPolicy.shouldNotify(
                settings.enabled,
                NoopPrefs.hydrationTracking(app),
                now.hour * 60 + now.minute,
            )
        ) return Result.success()
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return Result.success()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            app.getString(R.string.hydration_reminder_setting_title),
                            NotificationManager.IMPORTANCE_DEFAULT,
                        ).apply {
                            description = app.getString(R.string.hydration_reminder_setting_detail)
                            setShowBadge(false)
                        },
                    )
                }
            }
            val openApp = PendingIntent.getActivity(
                app, NOTIFICATION_ID, appLaunchIntent(app),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(app.getString(R.string.hydration_reminder_title))
                .setContentText(app.getString(R.string.hydration_reminder_body))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
        }
        return Result.success()
    }

    private companion object {
        const val CHANNEL_ID = "noop_hydration_reminder"
        const val NOTIFICATION_ID = 4312
    }
}
