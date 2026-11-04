package com.noop.agent

import android.app.Activity
import androidx.compose.ui.graphics.toArgb
import com.noop.R
import com.noop.ui.Palette
import com.noop.ui.Metrics
import com.noop.ui.NoopType
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
    private var pairingInFlight = false
    private var pairingFailed = false
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
            setPadding(dp(Metrics.screenPadding.value.toInt()), dp(Metrics.space24.value.toInt()), dp(Metrics.screenPadding.value.toInt()), dp(Metrics.space24.value.toInt()))
            setBackgroundColor(Palette.surfaceBase.toArgb())
        }
        setContentView(ScrollView(this).apply { addView(column) })
        fun label(value: String, size: Float, color: Int = Palette.textPrimary.toArgb()) = TextView(this).also {
            it.text = value; it.textSize = size; it.setTextColor(color); it.setPadding(0, dp(Metrics.space6.value.toInt()), 0, dp(Metrics.space10.value.toInt())); column.addView(it)
        }
        fun button(value: String, click: () -> Unit) = Button(this).also {
            it.text = value; it.setOnClickListener { click() }
            column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        label(getString(R.string.agent_title), NoopType.title2.fontSize.value)
        label(getString(R.string.agent_subtitle), NoopType.subhead.fontSize.value, Palette.statusWarning.toArgb())
        label(getString(R.string.agent_intro), NoopType.body.fontSize.value, Palette.textSecondary.toArgb())

        label(getString(R.string.agent_pair_section), NoopType.headline.fontSize.value)
        pairingStatus = label("", NoopType.body.fontSize.value)
        val linkField = EditText(this).apply {
            hint = getString(R.string.agent_link_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Palette.textPrimary.toArgb()); setHintTextColor(Palette.textTertiary.toArgb())
        }
        column.addView(linkField)
        button(getString(R.string.agent_pair_button)) { val link = linkField.text.toString(); linkField.text.clear(); pair(link) }
        confirm = button(getString(R.string.agent_confirm_pair)) { pendingLink?.let { pendingLink = null; pair(it) } }
        button(getString(R.string.agent_forget_pair)) {
            AgentAccessService.stop(this)
            AgentAccess.pairingStore(this).forget()
            AgentAccess.audit(this).record("pairing", null, "forgotten")
            render()
        }

        label(getString(R.string.agent_grant_section), NoopType.headline.fontSize.value)
        val minutes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        AgentPolicy.GRANT_MINUTES.forEachIndexed { i, m ->
            minutes.addView(RadioButton(this).apply {
                id = 1000 + i; text = if (m < 60) getString(R.string.agent_minutes, m) else getString(R.string.agent_hours, m / 60); setTextColor(Palette.textPrimary.toArgb()); isChecked = m == 60
            })
        }
        column.addView(minutes)
        val detail = CheckBox(this).apply { text = getString(R.string.agent_detail); setTextColor(Palette.textPrimary.toArgb()) }
        val actions = CheckBox(this).apply { text = getString(R.string.agent_actions); setTextColor(Palette.textPrimary.toArgb()) }
        column.addView(detail); column.addView(actions)
        button(getString(R.string.agent_allow)) {
            val m = AgentPolicy.GRANT_MINUTES[(minutes.checkedRadioButtonId - 1000).coerceIn(0, AgentPolicy.GRANT_MINUTES.lastIndex)]
            AgentAccess.lastError = null
            AgentAccessService.start(this, m, detail.isChecked, actions.isChecked)
            main.postDelayed({ render() }, 600)
        }
        button(getString(R.string.agent_stop)) { AgentAccessService.stop(this); main.postDelayed({ render() }, 300) }
        grantStatus = label("", NoopType.body.fontSize.value)

        label(getString(R.string.agent_audit_section), NoopType.headline.fontSize.value)
        button(getString(R.string.agent_clear_audit)) { AgentAccess.audit(this).clear(); render() }
        auditView = label("", NoopType.caption.fontSize.value, Palette.textTertiary.toArgb())
        render()
    }

    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun pair(link: String) {
        pairingInFlight = true
        pairingFailed = false
        pairingStatus.text = getString(R.string.agent_checking)
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
                pairingInFlight = false
                pairingFailed = result.isFailure
                render()
            }
        }
    }

    private fun render() {
        val p = AgentAccess.pairingStore(this).load()
        pairingStatus.text = when {
            pairingInFlight -> getString(R.string.agent_checking)
            pairingFailed -> getString(R.string.agent_pair_failed)
            p != null -> getString(R.string.agent_paired, p.brokerHost, p.deviceShort)
            else -> getString(R.string.agent_not_paired)
        }
        confirm.visibility = if (pendingLink != null) android.view.View.VISIBLE else android.view.View.GONE
        val g = AgentAccess.grant
        grantStatus.text = when {
            g != null && g.active(System.currentTimeMillis()) ->
                getString(R.string.agent_grant_on,
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(g.expiresAtMs)),
                    g.scopes.joinToString { it.wire }, AgentAccess.listening ?: "…")
            AgentAccess.lastError != null -> getString(R.string.agent_grant_off_error, AgentAccess.lastError)
            else -> getString(R.string.agent_grant_off)
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        auditView.text = AgentAccess.audit(this).recent(40).joinToString("\n") { e ->
            val extra = listOfNotNull(
                e.optString("screen").takeIf { it.isNotEmpty() },
                e.optString("granularity").takeIf { it.isNotEmpty() },
                e.optString("peer").takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            "${fmt.format(Date(e.optLong("ts")))}  ${e.optString("kind")}  ${e.optString("tool", "")}  ${e.optString("outcome")}  $extra"
        }.ifEmpty { getString(R.string.agent_no_activity) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
