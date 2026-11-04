package com.noop.agent

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Experimental (fork-only) owner controls for Hermes agent access: pair with the owner's own z0live
 * broker, grant a time-boxed session with explicit scopes, stop it, forget the pairing and read the
 * on-phone audit log. Default OFF; nothing listens until the owner taps Allow.
 */
class AgentAccessActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var pairingStatus: TextView
    private lateinit var grantStatus: TextView
    private lateinit var auditView: TextView
    private lateinit var confirm: Button
    private var pendingLink: String? = null
    private val refresh = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 2000) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // A noop-agent://pair link is staged in memory only and needs an explicit tap below.
        pendingLink = intent?.dataString?.takeIf { it.startsWith("noop-agent://pair?") }
        intent?.data = null

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(14, 19, 22))
        }
        setContentView(ScrollView(this).apply { addView(column) })
        fun label(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).also {
            it.text = value; it.textSize = size; it.setTextColor(color); it.setPadding(0, dp(6), 0, dp(10)); column.addView(it)
        }
        fun button(value: String, click: () -> Unit) = Button(this).also {
            it.text = value; it.setOnClickListener { click() }
            column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        label("Hermes agent access", 24f)
        label("Experimental · fork-only · off by default", 13f, Color.rgb(255, 196, 0))
        label(
            "Lets your own Hermes, on your own Tailscale network, read bounded summaries of your NOOP data " +
                "and, if you allow it, open a NOOP screen or post a coach note. Nothing is sent anywhere on its own: " +
                "NOOP only answers while a grant below is running, only on its Tailscale address, and logs every call here. " +
                "Daily values are off unless you tick them. Hermes' notes are wearable-based reflections, not medical advice.",
            14f, Color.rgb(200, 210, 215),
        )

        label("1 · Pair with your broker", 18f)
        pairingStatus = label("", 14f)
        val linkField = EditText(this).apply {
            hint = "Paste z0live://pair… link from your Hermes host"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        column.addView(linkField)
        button("Pair") { val link = linkField.text.toString(); linkField.text.clear(); pair(link) }
        confirm = button("Confirm pairing from link") { pendingLink?.let { pendingLink = null; pair(it) } }
        button("Forget pairing (also stops access)") {
            AgentAccessService.stop(this)
            AgentAccess.pairingStore(this).forget()
            AgentAccess.audit(this).record("pairing", null, "forgotten")
            render()
        }

        label("2 · Grant a session", 18f)
        val minutes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        AgentPolicy.GRANT_MINUTES.forEachIndexed { i, m ->
            minutes.addView(RadioButton(this).apply {
                id = 1000 + i; text = if (m < 60) "$m min" else "${m / 60} h"; setTextColor(Color.WHITE); isChecked = m == 60
            })
        }
        column.addView(minutes)
        val detail = CheckBox(this).apply { text = "Also allow daily values (otherwise summaries only)"; setTextColor(Color.WHITE) }
        val actions = CheckBox(this).apply { text = "Allow safe actions: open a NOOP screen, post a coach note"; setTextColor(Color.WHITE) }
        column.addView(detail); column.addView(actions)
        button("Allow Hermes access") {
            val m = AgentPolicy.GRANT_MINUTES[(minutes.checkedRadioButtonId - 1000).coerceIn(0, AgentPolicy.GRANT_MINUTES.lastIndex)]
            AgentAccess.lastError = null
            AgentAccessService.start(this, m, detail.isChecked, actions.isChecked)
            main.postDelayed({ render() }, 600)
        }
        button("Stop access now") { AgentAccessService.stop(this); main.postDelayed({ render() }, 300) }
        grantStatus = label("", 15f)

        label("3 · Audit log (this phone only)", 18f)
        button("Clear audit log") { AgentAccess.audit(this).clear(); render() }
        auditView = label("", 12f, Color.rgb(190, 200, 205))
        render()
    }

    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun pair(link: String) {
        pairingStatus.text = "Checking with your broker…"
        io.execute {
            val result = runCatching {
                val (url, token) = AgentPairing.parseLink(link)
                val device = AgentAccess.brokerDeviceId(url, token) ?: error("broker did not accept this link")
                AgentAccessService.stop(this)
                AgentAccess.pairingStore(this).save(AgentPairing(url, AgentPolicy.sha256Hex(token), device, System.currentTimeMillis()))
                AgentAccess.audit(this).record("pairing", null, "paired", mapOf("broker" to java.net.URI(url).host, "device" to device.takeLast(6)))
            }
            main.post {
                if (isDestroyed) return@post
                if (result.isFailure) pairingStatus.text = "Pairing failed: link invalid, expired, or broker unreachable over Tailscale."
                render()
            }
        }
    }

    private fun render() {
        val p = AgentAccess.pairingStore(this).load()
        if (!pairingStatus.text.startsWith("Pairing failed") && !pairingStatus.text.startsWith("Checking")) {
            pairingStatus.text = p?.let { "Paired with ${it.brokerHost} · device …${it.deviceShort}" } ?: "Not paired."
        } else if (p != null && pairingStatus.text.startsWith("Checking")) {
            pairingStatus.text = "Paired with ${p.brokerHost} · device …${p.deviceShort}"
        }
        confirm.visibility = if (pendingLink != null) android.view.View.VISIBLE else android.view.View.GONE
        val g = AgentAccess.grant
        grantStatus.text = when {
            g != null && g.active(System.currentTimeMillis()) ->
                "ON until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(g.expiresAtMs))} · " +
                    "${g.scopes.joinToString { it.wire }} · listening on ${AgentAccess.listening ?: "…"}"
            AgentAccess.lastError != null -> "OFF · ${AgentAccess.lastError}"
            else -> "OFF"
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        auditView.text = AgentAccess.audit(this).recent(40).joinToString("\n") { e ->
            val extra = listOfNotNull(
                e.optString("screen").takeIf { it.isNotEmpty() },
                e.optString("granularity").takeIf { it.isNotEmpty() },
                e.optString("peer").takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            "${fmt.format(Date(e.optLong("ts")))}  ${e.optString("kind")}  ${e.optString("tool", "")}  ${e.optString("outcome")}  $extra"
        }.ifEmpty { "No agent activity yet." }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
