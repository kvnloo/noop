package com.noop.agent

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.sqlite.db.SimpleSQLiteQuery
import com.noop.R
import com.noop.data.WhoopDatabase
import com.noop.ui.MainActivity
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection

/** Process-wide state for the Experimental Hermes agent access. Nothing here runs until a grant. */
object AgentAccess {
    @Volatile var grant: AgentGrant? = null
    @Volatile var listening: String? = null
    @Volatile var lastError: String? = null

    fun pairingStore(context: Context) = AgentPairingStore(File(context.noBackupFilesDir, "agent-pairing.json"))
    fun audit(context: Context) = AgentAudit(File(context.noBackupFilesDir, "agent-audit.jsonl"))

    /** One-shot nonce so only NOOP's own notification can deep-link a screen. */
    private val nonce: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    const val EXTRA_NONCE = "com.noop.agent.NONCE"

    fun consumeNavIntent(intent: Intent?) {
        val screen = intent?.getStringExtra(AgentNav.EXTRA_SCREEN) ?: return
        val ok = intent.getStringExtra(EXTRA_NONCE) == nonce && screen in AgentPolicy.SCREENS
        intent.removeExtra(AgentNav.EXTRA_SCREEN); intent.removeExtra(EXTRA_NONCE)
        if (ok) AgentNav.request(screen)
    }

    internal fun navIntent(context: Context, screen: String): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AgentNav.EXTRA_SCREEN, screen)
            .putExtra(EXTRA_NONCE, nonce)

    /** First IPv4 address on a Tailscale interface, or null when Tailscale is not connected. */
    fun tailnetAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && AgentPolicy.isTailnet(it) }
    }.getOrNull()

    /** `GET {broker}/v1/devices/self` with the presented token; returns the broker's device id. */
    fun brokerDeviceId(brokerUrl: String, token: String): String? {
        val conn = URL("$brokerUrl/v1/devices/self").openConnection() as HttpsURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 3000; conn.readTimeout = 3000
        conn.setRequestProperty("Authorization", "Bearer $token")
        return try {
            if (conn.responseCode != 200) return null
            val raw = conn.inputStream.use { it.readNBytesCompat(4096) }
            JSONObject(String(raw, Charsets.UTF_8)).optString("device_id").takeIf { it.matches(Regex("[0-9a-f]{32}")) }
        } finally { conn.disconnect() }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (true) { val n = read(buf); if (n < 0) break; require(out.size() + n <= max); out.write(buf, 0, n) }
        return out.toByteArray()
    }

    const val CHANNEL = "noop_agent"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Hermes agent access", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Experimental: shown while Hermes has access, and for Hermes coach notes."
        })
    }
}

/** Room-backed [AgentSql]. Every value is copied out of the cursor before it closes. */
class RoomAgentSql(private val db: WhoopDatabase) : AgentSql {
    override fun rows(sql: String, args: List<Any>): List<Map<String, Any?>> =
        db.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).associate { i ->
                    c.getColumnName(i) to when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> c.getString(i)
                        else -> null
                    }
                })
            }
        }
}

class AndroidAgentActions(private val context: Context) : AgentActions {
    private fun foreground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    override fun openScreen(screen: String): JSONObject {
        if (foreground()) {
            // NOOP already has a visible window (the shell or one of its own screens), so it may bring the
            // shell forward itself; MainActivity consumes the nonce-checked extra and AppRoot navigates.
            val before = AgentNav.currentId()
            context.startActivity(AgentAccess.navIntent(context, screen))
            val landed = AgentNav.awaitLanded(screen, before, 3000)
            return JSONObject().put("screen", screen).put("mode", "in_app").put("landed", landed)
        }
        // Android blocks background activity starts; the owner taps to open instead (a natural confirmation).
        val pi = PendingIntent.getActivity(context, screen.hashCode(), AgentAccess.navIntent(context, screen),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val posted = notify(NAV_ID, "Hermes suggests: open ${screen.replaceFirstChar { it.uppercase() }}", "Tap to open NOOP there.", pi)
        return JSONObject().put("screen", screen).put("mode", "notification").put("posted", posted).put("landed", false)
    }

    override fun showCoachNote(title: String, body: String): JSONObject {
        val pi = PendingIntent.getActivity(context, 7, AgentAccess.navIntent(context, "coach"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val posted = notify(NOTE_ID, title, body, pi)
        return JSONObject().put("posted", posted)
    }

    private fun notify(id: Int, title: String, body: String, pi: PendingIntent): Boolean {
        AgentAccess.ensureChannel(context)
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        val n = NotificationCompat.Builder(context, AgentAccess.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n\nFrom Hermes via NOOP agent access. Not medical advice."))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        return runCatching { nm.notify(id, n); true }.getOrDefault(false)
    }

    companion object {
        const val NAV_ID = 47_101
        const val NOTE_ID = 47_102
    }
}
