package com.noop.ui

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
import android.view.WindowManager
import com.noop.ble.gesture.OuraGestureBroker
import java.util.concurrent.Executors
import com.noop.ble.gesture.OuraTapDetector
import com.noop.ble.gesture.OuraAcmRecorder
import android.view.KeyEvent
import java.io.File
import com.noop.NoopApplication
import com.noop.R
import com.noop.ble.OuraZ0LiveHandoff
import com.noop.ble.WhoopConnectionService
import com.noop.ble.gesture.OuraGestureStandby
import com.noop.ble.gesture.OuraGuidedPlan
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech

/**
 * Opt-in bounded gesture onboarding on NOOP's already-connected Oura Gen 3 BLE source.
 * Launched by z0live, but no microphone/audio/network permission belongs to NOOP.
 * The app remains user-disarmable, and the ring stream always has a hard timeout.
 */
class OuraGestureSetupActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var arm: Button
    private lateinit var stop: Button
    private val broker by lazy { OuraGestureBroker(this) }
    private val export = Executors.newSingleThreadExecutor()
    private var claiming = false
    private var pairingInProgress = false
    // Read by the single-thread export worker; Stop invalidates queued events immediately.
    @Volatile private var claimGeneration = 0
    private var armed = false
    private var visible = false
    private var doubles = 0
    private var sequence = 0L
    private var capability: PendingIntent? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var countdown: TextView
    private lateinit var recordToggle: Button
    private lateinit var labelButton: Button
    private lateinit var markButton: Button
    private lateinit var rearmToggle: Button
    private var armedUntilMs = 0L
    /** Re-arm the next bounded capture when one expires, ONLY while this screen is in front. */
    private var autoRearm = true
    /** Debug raw-motion recording (default OFF, never persisted): no broker lease, no gesture delivery. */
    private var recordMode = false
    private var recordLabel = 0
    private var recorder: OuraAcmRecorder? = null
    private var recordFile: File? = null
    private val standby by lazy { OuraGestureStandby.get(this) }
    private lateinit var standbyToggle: Button
    private lateinit var standbyStatus: TextView
    private lateinit var guidedButton: Button
    private lateinit var guidedPause: Button
    private lateinit var guidedRestart: Button
    private lateinit var guidedSkip: Button
    private lateinit var guidedText: TextView
    /** Guided recording: the block running (or next to run) and whether a session is open. */
    private var guidedOpen = false
    private var guidedRunning = false
    private var guidedIndex = 0
    private val guidedToken = Any()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private val guidedPrefs by lazy { getSharedPreferences("oura_guided_recording", MODE_PRIVATE) }
    private val standbyRefresh = object : Runnable {
        override fun run() {
            refreshStandby()
            if (visible) handler.postDelayed(this, 2_000)
        }
    }
    private val endNotice = Runnable {
        val rearm = autoRearm && visible && !recordMode && armed
        val recording = recorder != null
        disarm()
        if (rearm) begin(rearm = true) else if (!recording) status.text = getString(R.string.oura_broker_expired)
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!armed) { countdown.text = "Ring motion: OFF"; return }
            val left = ((armedUntilMs - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
            val rec = recorder
            countdown.text = "Ring motion armed: ${left}s left" +
                (if (autoRearm && !recordMode) " (re-arms while this screen is open)" else "") +
                (if (rec != null) "\nRecording '${RECORD_LABELS[recordLabel]}': ${rec.frames} frames, ${rec.marks} marks" else "")
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        capability = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_CAPABILITY, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra(EXTRA_CAPABILITY) as? PendingIntent)
        }?.takeIf { it.creatorPackage == LAWNCHAIR_PACKAGE }
        // PendingIntent is process-local onboarding authority; never save or log it.
        intent.replaceExtras(Bundle())

        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(14, 19, 22))
        }
        scroll.addView(column)
        fun label(value: String, size: Float): TextView =
            TextView(this).also {
                it.text = value
                it.textSize = size
                it.setTextColor(Color.WHITE)
                it.setPadding(0, dp(6), 0, dp(10))
                column.addView(it)
            }
        fun button(value: String, click: () -> Unit): Button =
            Button(this).also {
                it.text = value
                it.setOnClickListener { click() }
                column.addView(it, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        label("Oura ring → z0live", 24f)
        label(getString(R.string.oura_gesture_map), 17f)
        standbyToggle = button(getString(R.string.oura_standby_toggle_off)) { toggleStandby() }
        standbyStatus = label("", 15f)
        label(getString(R.string.oura_standby_explain), 14f)
        label(
            "1. In NOOP → Devices, connect your existing Oura Ring Gen 3. Do NOT reset or rekey it.\n" +
            "2. In Lawnchair z0live, pair and start voice yourself, then mute it.\n" +
            "3. Arm 2 minutes of experimental ring motion. Double tap the ring 3 times to verify recognition.\n" +
            "4. Another double tap toggles mute in that already-running voice session.\n" +
            "Stop anytime. Gesture settings are OFF by default and this never records microphone audio.",
            15f
        )
        status = label(routeLine(), 16f)
        progress = label("Recognized double taps: 0 / 3", 18f)
        countdown = label("Ring motion: OFF", 16f)
        arm = button("Arm ring for 2 minutes") { begin() }
        stop = button("Stop and turn gestures off") { disarm() }.apply { isEnabled = false }
        rearmToggle = button("Auto re-arm while this screen is open: ON") {
            autoRearm = !autoRearm
            rearmToggle.text = "Auto re-arm while this screen is open: ${if (autoRearm) "ON" else "OFF"}"
        }
        label("Debug: raw motion recording (for tuning tap detection). Off by default; files stay on " +
            "this phone. While recording, taps are NOT sent to z0live.", 14f)
        recordToggle = button("Debug recording: OFF") {
            if (armed || claiming) return@button
            recordMode = !recordMode
            recordToggle.text = "Debug recording: ${if (recordMode) "ON" else "OFF"}"
            labelButton.isEnabled = recordMode
        }
        labelButton = button("Recording label: ${RECORD_LABELS[0]}") {
            if (armed) return@button
            recordLabel = (recordLabel + 1) % RECORD_LABELS.size
            labelButton.text = "Recording label: ${RECORD_LABELS[recordLabel]}"
        }.apply { isEnabled = false }
        markButton = button("Mark tap (or press volume down)") { markTap() }.apply { isEnabled = false }
        label(getString(R.string.oura_guided_title), 18f)
        label(getString(R.string.oura_guided_explain), 14f)
        guidedText = label("", 16f)
        guidedButton = button(getString(R.string.oura_guided_start)) { startGuided() }
        guidedPause = button(getString(R.string.oura_guided_pause)) { pauseGuided(close = true) }.apply { isEnabled = false }
        guidedSkip = button(getString(R.string.oura_guided_skip)) { skipGuided() }
        guidedRestart = button(getString(R.string.oura_guided_restart)) {
            if (guidedOpen) return@button
            guidedPrefs.edit().putInt(KEY_GUIDED_NEXT, 0).apply()
            refreshGuided()
        }
        button("Open NOOP devices / connect ring") {
            disarm()
            startActivity(Intent(this, MainActivity::class.java))
        }
        val pairing = EditText(this).apply {
            hint = getString(R.string.oura_broker_pair_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        column.addView(pairing)
        button(getString(R.string.oura_broker_pair)) {
            disarm()
            pairingInProgress = true
            arm.isEnabled = false
            val link = pairing.text.toString()
            pairing.text.clear()
            val generation = claimGeneration
            // Disarm queues the old remote lease release. Pair on the SAME
            // worker after it, so rotating credentials cannot strand that lease
            // or race with an earlier gesture send. Keystore work also stays off UI.
            export.execute {
                if (generation != claimGeneration) return@execute
                val paired = runCatching {
                    broker.release()
                    broker.pair(link)
                }.isSuccess
                handler.post {
                    if (generation == claimGeneration && !isDestroyed) {
                        pairingInProgress = false
                        arm.isEnabled = true
                        status.text = getString(
                            if (paired) R.string.oura_broker_paired else R.string.oura_broker_failed
                        )
                    }
                }
            }
        }
        button(getString(R.string.oura_broker_forget)) { disarm(); export.execute { runCatching { broker.release() }; broker.forget() } }
        button("Open z0live voice controls") { openControls() }
        button("Close setup") { disarm(); finish() }
        setContentView(scroll)
        refreshStandby()
        refreshGuided()
    }

    /**
     * Where ring taps go from this phone. The old "not armed" line only described the session capability
     * a z0live screen can hand over; with the broker paired, taps travel through it instead (Always listen,
     * or this screen's arm), and the broker starts a call itself when no z0live surface is awake.
     */
    private fun routeLine(): String = when {
        capability != null -> "Session gesture capability received; starts only after you arm the ring."
        broker.configured -> getString(R.string.oura_route_broker)
        else -> getString(R.string.oura_route_unpaired)
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()
    private val coordinator get() = (application as NoopApplication).sourceCoordinator

    private fun markTap() {
        val rec = recorder ?: return
        rec.mark(SystemClock.elapsedRealtime(), RECORD_LABELS[recordLabel])
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && recorder != null) {
            if (event?.repeatCount == 0) markTap()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun begin(leaseReady: Boolean = false, rearm: Boolean = false) {
        // Never start a local-only gesture capture while the optional broker
        // credentials are still being replaced on the export worker.
        if (armed || claiming || pairingInProgress || guidedOpen) return
        if (standby.enabled && !recordMode) {
            status.text = getString(R.string.oura_standby_screen_busy)
            return
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)) {
            status.text = "Allow Bluetooth permissions for NOOP first, then arm the ring."
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN), BLUETOOTH_REQUEST)
            return
        }
        if (broker.configured && !leaseReady && !recordMode) {
            claiming = true
            stop.isEnabled = true
            val generation = ++claimGeneration
            export.execute {
                val ok = runCatching { broker.claim(120) }.isSuccess
                handler.post {
                    claiming = false
                    if (generation != claimGeneration || isDestroyed) {
                        if (!export.isShutdown) export.execute { runCatching { broker.release() } }
                    } else if (ok) begin(true, rearm)
                    else status.text = getString(R.string.oura_broker_failed)
                }
            }
            return
        }
        if (!rearm) {
            // An automatic re-arm keeps the calibration: three trials once per screen visit, not per window.
            // Calibration is once per install, not once per arm: a calibrated owner goes straight to live taps.
            doubles = if (standby.calibrated) 3 else 0
            sequence = 0
            progress.text = "Recognized double taps: ${minOf(doubles, 3)} / 3"
        }
        if (recordMode) {
            standby.holdForScreen(true)
            val generation = coordinator.setActiveOuraAcmRecorder(null)
            val opened = if (generation == null) null else runCatching {
                OuraAcmRecorder.open(filesDir, RECORD_LABELS[recordLabel], generation, System.currentTimeMillis())
            }.getOrNull()
            if (opened == null || coordinator.setActiveOuraAcmRecorder(opened.first) == null) {
                opened?.first?.close()
                opened?.second?.delete()
                status.text = "Could not start a recording: no live Oura ring."
                standby.holdForScreen(false)
                return
            }
            recorder = opened.first
            recordFile = opened.second
        }
        val ok = coordinator.startActiveOuraGestureCapture(120) { gesture ->
            val rec = recorder
            if (rec != null) {
                // Recording only: count what the current detector says, deliver nothing.
                rec.gesture(SystemClock.elapsedRealtime(), gesture.name)
                if (gesture == OuraTapDetector.Gesture.DOUBLE_TAP) doubles++
                progress.text = "Detector double taps this recording: $doubles"
                return@startActiveOuraGestureCapture
            }
            if (gesture == OuraTapDetector.Gesture.DOUBLE_TAP) {
                doubles++
                progress.text = "Recognized double taps: ${minOf(doubles, 3)} / 3"
                if (doubles == 3) standby.calibrated = true
                if (doubles <= 3) {
                    status.text = if (doubles < 3) "Tap the ring twice again (${3 - doubles} trial(s) remain)."
                        else "Three trials detected. Next double tap will invoke z0live."
                } else {
                    deliverGesture("double_tap")
                }
            } else if (gesture == OuraTapDetector.Gesture.TRIPLE_TAP && doubles >= 3) {
                deliverGesture("triple_tap")
            }
        }
        if (!ok) {
            closeRecording(keep = false)
            standby.holdForScreen(false)
            export.execute { runCatching { broker.release() } }
            status.text = "NOOP needs an authenticated, actively connected Gen 3 ring. " +
                "Open NOOP → Devices, select Oura Ring 3 and reconnect, then retry."
            return
        }
        armed = true
        arm.isEnabled = false
        stop.isEnabled = true
        markButton.isEnabled = recorder != null
        status.text = when {
            recorder != null -> "Recording '${RECORD_LABELS[recordLabel]}' for 120 seconds. After each double tap, press volume down."
            doubles >= 3 -> "Oura motion re-armed for 120 seconds. Double tap toggles z0live."
            else -> "Oura motion enabled for 120 seconds. Double tap the ring 3 times."
        }
        armedUntilMs = SystemClock.elapsedRealtime() + 120_000
        handler.removeCallbacks(tick)
        handler.post(tick)
        handler.removeCallbacks(endNotice)
        handler.postDelayed(endNotice, 120_000)
    }

    /** Detach and close the debug recorder; report where it went (or delete it when nothing was armed). */
    private fun closeRecording(keep: Boolean) {
        val rec = recorder ?: return
        recorder = null
        coordinator.setActiveOuraAcmRecorder(null)
        rec.close()
        val file = recordFile
        recordFile = null
        if (::markButton.isInitialized) markButton.isEnabled = false
        if (!keep) { file?.delete(); return }
        if (::status.isInitialized) {
            status.text = "Saved ${rec.frames} frames (${rec.undecoded} undecoded), ${rec.marks} marks to ${file?.name}."
        }
    }

    private fun deliverGesture(gesture: String) {
        if (broker.configured) {
            val generation = claimGeneration
            export.execute {
                // Stop/re-pair increments generation on the UI thread. Queued
                // gestures from an expired or disarmed capture must not toggle
                // the remote voice session after the owner pressed Stop.
                if (generation != claimGeneration) return@execute
                val ok = runCatching { broker.send(gesture) }.getOrDefault(false)
                handler.post {
                    if (generation == claimGeneration && !isDestroyed) {
                        status.text = getString(if (ok) R.string.oura_broker_sent else R.string.oura_broker_failed)
                    }
                }
            }
            return // Never fire capability AND broker; an ambiguous request must not toggle twice.
        }
        if (gesture != "double_tap") return
        val token = capability
        if (token != null) {
            sequence++
            val data = Intent()
                .putExtra(EXTRA_GESTURE, "DOUBLE_TAP")
                .putExtra(EXTRA_SEQUENCE, sequence)
                .putExtra(EXTRA_TIMESTAMP, SystemClock.elapsedRealtime())
            try {
                token.send(this, 0, data)
                status.text = "Double tap delivered to z0live. Check its Mute status."
                return
            } catch (_: PendingIntent.CanceledException) {
                capability = null
            }
        }
        if (visible) {
            openControls() // The exported link opens controls only; never microphone.
        } else {
            notifyToOpenControls()
        }
    }

    private fun openControls() {
        runCatching { startActivity(OuraZ0LiveHandoff.visibleControlsIntent()) }
            .onFailure { status.text = "Install the matching experimental Lawnchair z0live APK first." }
    }

    private fun notifyToOpenControls() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = "oura_z0live_setup"
        mgr.createNotificationChannel(NotificationChannel(channel, getString(R.string.oura_z0live_notification_channel), NotificationManager.IMPORTANCE_HIGH))
        val pending = PendingIntent.getActivity(this, 317,
            OuraZ0LiveHandoff.visibleControlsIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        mgr.notify(317, Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.oura_z0live_notification_title))
            .setContentText(getString(R.string.oura_z0live_notification_body))
            .setContentIntent(pending).setAutoCancel(true).build())
    }

    private fun disarm() {
        claimGeneration++
        claiming = false
        pairingInProgress = false
        // The broker lease is per paired device: releasing it would also cut off Always listen.
        if (!standby.enabled) export.execute { runCatching { broker.release() } }
        if (armed) coordinator.stopActiveOuraGestureCapture()
        armed = false
        handler.removeCallbacks(endNotice)
        if (::arm.isInitialized) arm.isEnabled = true
        if (::stop.isInitialized) stop.isEnabled = false
        if (::status.isInitialized) status.text = "Gesture capture OFF."
        closeRecording(keep = true)   // after the status line, so the saved-file line is what stays
        if (!guidedOpen) standby.holdForScreen(false)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        handler.removeCallbacks(standbyRefresh)
        handler.post(standbyRefresh)
    }
    override fun onPause() { visible = false; super.onPause() }
    override fun onDestroy() {
        if (guidedOpen) pauseGuided(close = true)
        disarm()
        tts?.shutdown()
        tone?.release()
        export.execute { if (!standby.enabled) runCatching { broker.release() }; export.shutdown() }
        super.onDestroy()
    }


    private fun toggleStandby() {
        if (!standby.enabled) {
            if (Build.VERSION.SDK_INT >= 31 &&
                (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN), BLUETOOTH_REQUEST)
                return
            }
            if (armed) disarm()
            // Visible here, so the connection service may be (re)promoted now; it keeps the ring linked
            // with the screen off. NOOP's own Background connection setting still decides.
            if (NoopPrefs.backgroundConnection(this)) WhoopConnectionService.start(this)
            standby.setEnabled(true)
        } else {
            standby.setEnabled(false)
        }
        refreshStandby()
    }

    private fun refreshStandby() {
        if (!::standbyToggle.isInitialized) return
        val on = standby.enabled
        standbyToggle.text = getString(if (on) R.string.oura_standby_toggle_on else R.string.oura_standby_toggle_off)
        val line = when {
            !on -> getString(R.string.oura_standby_status_off)
            standby.listening -> getString(R.string.oura_standby_status_listening)
            else -> getString(R.string.oura_standby_status_waiting)
        }
        val background = if (on && !NoopPrefs.backgroundConnection(this)) "\n" + getString(R.string.oura_standby_needs_background) else ""
        val last = standby.lastResult.takeIf { on && it.isNotEmpty() }?.let { "\nLast: $it" } ?: ""
        standbyStatus.text = line + background + last
    }

    // MARK: - Guided recording

    /**
     * Where the guided plan resumes. A position saved by the old 29-block plan is mapped once onto the
     * short plan (its first two blocks are the same), so finished double/triple-normal blocks are not
     * repeated.
     */
    private fun guidedNext(): Int {
        if (!guidedPrefs.contains(KEY_GUIDED_NEXT) && guidedPrefs.contains(KEY_GUIDED_NEXT_LEGACY)) {
            val mapped = OuraGuidedPlan.resumeFromLegacy(guidedPrefs.getInt(KEY_GUIDED_NEXT_LEGACY, 0))
            guidedPrefs.edit().putInt(KEY_GUIDED_NEXT, mapped).remove(KEY_GUIDED_NEXT_LEGACY).apply()
        }
        return guidedPrefs.getInt(KEY_GUIDED_NEXT, 0).coerceIn(0, OuraGuidedPlan.BLOCKS.size)
    }

    /** Skip: while running, drop the current block (`skip` row) and go on; otherwise move the start on. */
    private fun skipGuided() {
        if (guidedOpen) {
            handler.removeCallbacksAndMessages(guidedToken)
            tts?.stop()
            val index = guidedIndex
            if (guidedRunning) {
                recorder?.block(SystemClock.elapsedRealtime(), "skip", index, OuraGuidedPlan.BLOCKS[index])
            }
            guidedRunning = false
            guidedPrefs.edit().putInt(KEY_GUIDED_NEXT, index + 1).apply()
            beep(80)
            handler.postAtTime({ runBlock(index + 1) }, guidedToken, SystemClock.uptimeMillis() + 600)
        } else {
            val next = guidedNext()
            guidedPrefs.edit().putInt(KEY_GUIDED_NEXT,
                if (next + 1 >= OuraGuidedPlan.BLOCKS.size) 0 else next + 1).apply()
            refreshGuided()
        }
    }

    private fun refreshGuided() {
        if (!::guidedButton.isInitialized) return
        val next = guidedNext()
        if (!guidedOpen) guidedIndex = next
        guidedButton.text = when {
            next == 0 || next >= OuraGuidedPlan.BLOCKS.size -> getString(R.string.oura_guided_start)
            else -> getString(R.string.oura_guided_resume, next + 1, OuraGuidedPlan.BLOCKS.size)
        }
        guidedButton.isEnabled = !guidedOpen
        guidedPause.isEnabled = guidedOpen
        guidedRestart.isEnabled = !guidedOpen && next > 0
        guidedSkip.isEnabled = true
        if (!guidedOpen) {
            val from = if (next >= OuraGuidedPlan.BLOCKS.size) 0 else next
            val left = OuraGuidedPlan.totalMs(from)
            guidedText.text = "Next: block ${from + 1} of ${OuraGuidedPlan.BLOCKS.size} " +
                "(${OuraGuidedPlan.BLOCKS[from].spoken}), about ${left / 60_000} min ${left / 1000 % 60} s to the end."
        }
    }

    private fun startGuided() {
        if (guidedOpen || claiming || pairingInProgress) return
        if (armed) disarm()
        var start = guidedNext()
        if (start >= OuraGuidedPlan.BLOCKS.size) start = 0
        standby.holdForScreen(true)
        val generation = coordinator.setActiveOuraAcmRecorder(null)
        val opened = if (generation == null) null else runCatching {
            OuraAcmRecorder.open(filesDir, "guided", generation, System.currentTimeMillis())
        }.getOrNull()
        val ok = opened != null && coordinator.setActiveOuraAcmRecorder(opened.first) != null &&
            coordinator.startActiveOuraGestureCapture(OuraLiveSourceSeconds, continuous = true) { g ->
                recorder?.gesture(SystemClock.elapsedRealtime(), g.name)
            }
        if (!ok) {
            coordinator.setActiveOuraAcmRecorder(null)
            opened?.first?.close()
            opened?.second?.delete()
            standby.holdForScreen(false)
            guidedText.text = getString(R.string.oura_guided_no_ring)
            return
        }
        recorder = opened!!.first
        recordFile = opened.second
        guidedOpen = true
        guidedIndex = start
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (tts == null) tts = TextToSpeech(this) { st -> ttsReady = st == TextToSpeech.SUCCESS }
        if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
        refreshGuided()
        // Give the speech engine a moment to bind before the first announcement.
        handler.postAtTime({ runBlock(guidedIndex) }, guidedToken, SystemClock.uptimeMillis() + 1_200)
    }

    private fun runBlock(index: Int) {
        if (!guidedOpen) return
        if (index >= OuraGuidedPlan.BLOCKS.size) { finishGuided(); return }
        val block = OuraGuidedPlan.BLOCKS[index]
        guidedIndex = index
        guidedRunning = true
        val cue = OuraGuidedPlan.cue(index)
        guidedText.text = cue
        speak(cue)
        buzz(80)
        val t0 = SystemClock.uptimeMillis() + OuraGuidedPlan.LEAD_IN_MS
        handler.postAtTime({
            recorder?.block(SystemClock.elapsedRealtime(), "start", index, block)
            if (block.kind != OuraGuidedPlan.Kind.TAPS) beep(250)
        }, guidedToken, t0)
        OuraGuidedPlan.repOffsets(block).forEachIndexed { rep, offset ->
            handler.postAtTime({
                recorder?.cue(SystemClock.elapsedRealtime(), index, rep + 1)
                beep(120)
                guidedText.text = "$cue\nRep ${rep + 1} of ${block.reps}"
            }, guidedToken, t0 + offset)
        }
        handler.postAtTime({
            recorder?.block(SystemClock.elapsedRealtime(), "end", index, block)
            guidedRunning = false
            buzz(60)
            beep(400)
            guidedPrefs.edit().putInt(KEY_GUIDED_NEXT, index + 1).apply()
            guidedText.text = "Block ${index + 1} done."
        }, guidedToken, t0 + block.durationMs)
        handler.postAtTime({ runBlock(index + 1) }, guidedToken, t0 + block.durationMs + OuraGuidedPlan.GAP_MS)
    }

    /** Pause: drop the block in progress (marked `abort`), save the file; resume restarts that block. */
    private fun pauseGuided(close: Boolean) {
        if (!guidedOpen) return
        handler.removeCallbacksAndMessages(guidedToken)
        if (guidedRunning) {
            recorder?.block(SystemClock.elapsedRealtime(), "abort", guidedIndex, OuraGuidedPlan.BLOCKS[guidedIndex])
            guidedPrefs.edit().putInt(KEY_GUIDED_NEXT, guidedIndex).apply()
        }
        guidedRunning = false
        tts?.stop()
        if (close) {
            guidedOpen = false
            coordinator.stopActiveOuraGestureCapture()
            closeRecording(keep = true)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            standby.holdForScreen(false)
        }
        refreshGuided()
    }

    private fun finishGuided() {
        speak(getString(R.string.oura_guided_done))
        guidedPrefs.edit().putInt(KEY_GUIDED_NEXT, OuraGuidedPlan.BLOCKS.size).apply()
        pauseGuided(close = true)
        guidedText.text = getString(R.string.oura_guided_done) + " " + status.text
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "oura-guided")
    }

    private fun beep(ms: Int) { runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, ms) } }

    private fun buzz(ms: Long) {
        runCatching {
            @Suppress("DEPRECATION")
            val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    companion object {
        /** Ring-side box for the guided recording's continuous capture (renewed by the source). */
        private const val OuraLiveSourceSeconds = 300
        const val EXTRA_CAPABILITY = "app.lawnchair.zer0.voice.z0live.CAPABILITY"
        const val EXTRA_GESTURE = "app.lawnchair.zer0.voice.z0live.OURA_GESTURE"
        const val EXTRA_SEQUENCE = "app.lawnchair.zer0.voice.z0live.OURA_SEQUENCE"
        const val EXTRA_TIMESTAMP = "app.lawnchair.zer0.voice.z0live.OURA_TIMESTAMP"
        const val LAWNCHAIR_PACKAGE = "app.lawnchair.play.debug"
        const val BLUETOOTH_REQUEST = 714
        /** Saved position in the short plan; [KEY_GUIDED_NEXT_LEGACY] held the old 29-block plan's. */
        private const val KEY_GUIDED_NEXT = "next_block_short"
        private const val KEY_GUIDED_NEXT_LEGACY = "next_block"
        /** Ground-truth labels for one debug recording (one capture = one label). */
        val RECORD_LABELS = listOf("normal", "light", "negative", "use")
    }
}
