package com.noop.agent

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.noop.R
import com.noop.data.WhoopDatabase
import java.text.DateFormat
import java.util.Date

/**
 * Foreground service that exists only while the owner's time-boxed grant is live. It binds the MCP
 * endpoint to the phone's Tailscale address, shows an ongoing "Hermes has access" notification with a
 * Stop action, and tears everything down at expiry, on Stop, or when the process dies (the grant is
 * memory-only, so a restart is always back to OFF).
 */
class AgentAccessService : Service() {
    private var server: AgentHttpServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { stopAccess("expired") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAccess("stopped_by_owner"); return START_NOT_STICKY }
        val minutes = intent?.getIntExtra(EXTRA_MINUTES, 0) ?: 0
        if (intent?.action != ACTION_START || minutes !in AgentPolicy.GRANT_MINUTES) { stopSelf(); return START_NOT_STICKY }
        val scopes = buildSet {
            add(AgentScope.READ_SUMMARY)
            if (intent.getBooleanExtra(EXTRA_DETAIL, false)) add(AgentScope.READ_DETAIL)
            if (intent.getBooleanExtra(EXTRA_ACTIONS, false)) add(AgentScope.ACTIONS)
        }
        val now = System.currentTimeMillis()
        val grant = AgentGrant(now, now + minutes * 60_000L, scopes)
        AgentAccess.ensureChannel(this)
        if (!startForegroundCompat(grant, null)) { AgentAccess.lastError = "Android refused the foreground service"; stopSelf(); return START_NOT_STICKY }

        val audit = AgentAccess.audit(this)
        val store = AgentAccess.pairingStore(this)
        val address = AgentAccess.tailnetAddress()
        if (store.load() == null || address == null) {
            AgentAccess.lastError = if (address == null) "Tailscale is not connected on this phone" else "Pair with your broker first"
            audit.record("grant", null, "refused_${if (address == null) "no_tailnet" else "not_paired"}")
            stopAccess(null); return START_NOT_STICKY
        }
        server?.stop()
        val authorizer = AgentAuthorizer({ store.load() }, AgentAccess::brokerDeviceId)
        val reader = AgentHealthReader(RoomAgentSql(WhoopDatabase.get(this)))
        val mcp = AgentMcp(reader, AndroidAgentActions(applicationContext), audit, { AgentAccess.grant })
        val http = AgentHttpServer(address, AgentPolicy.PORT, authorizer, mcp, audit)
        try {
            http.start()
        } catch (e: Exception) {
            AgentAccess.lastError = "Could not listen on the tailnet address (${e.javaClass.simpleName})"
            audit.record("grant", null, "refused_bind")
            stopAccess(null); return START_NOT_STICKY
        }
        server = http
        AgentAccess.grant = grant
        AgentAccess.listening = "${address.hostAddress}:${AgentPolicy.PORT}"
        AgentAccess.lastError = null
        audit.record("grant", null, "granted", mapOf("minutes" to minutes, "scopes" to scopes.joinToString(",") { it.wire }))
        startForegroundCompat(grant, AgentAccess.listening)
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, grant.expiresAtMs - now)
        return START_NOT_STICKY
    }

    private fun stopAccess(reason: String?) {
        handler.removeCallbacks(expire)
        val wasLive = AgentAccess.grant != null
        AgentAccess.grant = null
        AgentAccess.listening = null
        server?.stop(); server = null
        if (reason != null && wasLive) AgentAccess.audit(this).record("grant", null, reason)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        if (AgentAccess.grant != null) AgentAccess.audit(this).record("grant", null, "service_destroyed")
        AgentAccess.grant = null; AgentAccess.listening = null
        server?.stop(); server = null
        super.onDestroy()
    }

    private fun startForegroundCompat(grant: AgentGrant, listening: String?): Boolean = runCatching {
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentAccessService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, AgentAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(grant.expiresAtMs))
        val what = buildList {
            add("summaries")
            if (grant.allows(AgentScope.READ_DETAIL)) add("daily detail")
            if (grant.allows(AgentScope.ACTIONS)) add("safe actions")
        }.joinToString(" · ")
        val n = NotificationCompat.Builder(this, AgentAccess.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle("Hermes has NOOP access until $until")
            .setContentText(if (listening == null) "Starting…" else "$what · tailnet only · every call logged")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop now", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }.isSuccess

    companion object {
        const val ACTION_START = "com.noop.agent.START"
        const val ACTION_STOP = "com.noop.agent.STOP"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_ACTIONS = "actions"
        const val NOTIF_ID = 47_100

        fun start(context: Context, minutes: Int, detail: Boolean, actions: Boolean) {
            ContextCompat.startForegroundService(context, Intent(context, AgentAccessService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_MINUTES, minutes).putExtra(EXTRA_DETAIL, detail).putExtra(EXTRA_ACTIONS, actions))
        }

        fun stop(context: Context) {
            if (AgentAccess.grant == null) return
            context.startService(Intent(context, AgentAccessService::class.java).setAction(ACTION_STOP))
        }
    }
}
