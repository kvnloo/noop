package com.noop.ble.gesture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.noop.NoopApplication
import com.noop.ble.OuraLiveSource
import java.util.concurrent.Executors

/**
 * "Always listen": a persistent Oura gesture capture owned by the process, not by the setup screen.
 *
 * Off by default; the owner turns it on in the gesture setup screen and the choice is persisted. While
 * on, it keeps one continuous capture armed on the live Oura source, re-arms it after a link loss,
 * re-authentication or stall, holds the broker listener lease, and posts double/triple taps to the
 * owner's paired broker exactly as the setup screen does. The broker owns the mapping (double with no
 * call starts one, double in a call toggles mute, triple ends the call). Gesture names only: no raw
 * motion leaves the phone.
 *
 * The setup screen can take the capture over for a debug or guided recording ([holdForScreen]); the
 * standby then stands aside and re-arms when the screen lets go.
 */
class OuraGestureStandby private constructor(private val app: Context) {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val broker by lazy { OuraGestureBroker(app) }
    private val recording by lazy { OuraVoiceRecording.get(app) }
    private var pollInFlight = false
    private var started = false
    private var screenHold = false
    private var ownsCapture = false
    private var nextArmAtMs = 0L
    private var armFailures = 0
    private var lastDeliveryMs = 0L
    /** Written and read on [io] only. */
    private var leaseAtMs = 0L
    @Volatile var lastResult: String = ""
        private set

    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    /** The 3-trial calibration in the setup screen is done once, then remembered. */
    var calibrated: Boolean
        get() = prefs.getBoolean(KEY_CALIBRATED, false)
        set(value) { prefs.edit().putBoolean(KEY_CALIBRATED, value).apply() }

    /** Whether the standby currently holds an armed capture. Main thread. */
    val listening: Boolean get() = ownsCapture && coordinator.activeOuraGestureCaptureArmed()

    private val coordinator get() = (app as NoopApplication).sourceCoordinator

    private val tick = object : Runnable {
        override fun run() {
            step()
            if (enabled) main.postDelayed(this, TICK_MS)
        }
    }

    /** Process start (Application, boot, update): resume listening when the owner left it on. */
    fun startIfEnabled() {
        if (!enabled || started) return
        started = true
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(app,
                android.content.Intent(app, OuraGestureRecordingService::class.java))
        }.onFailure { log("Oura: standby foreground admission refused; open setup to enable") }
        log("Oura: always-listen on - starting the ring source and gesture standby")
        coordinator.start()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) {
            started = false
            armFailures = 0
            nextArmAtMs = 0
            startIfEnabled()
        } else {
            started = false
            recording.finish("standby_off")
            app.stopService(android.content.Intent(app, OuraGestureRecordingService::class.java))
            main.removeCallbacks(tick)
            if (ownsCapture) coordinator.stopActiveOuraGestureCapture()
            ownsCapture = false
            io.execute { runCatching { broker.release() }; leaseAtMs = 0 }
            log("Oura: always-listen off")
        }
    }

    /** The setup screen arms its own capture (debug/guided recording): stand aside until it lets go. */
    fun holdForScreen(hold: Boolean) {
        if (screenHold == hold) return
        if (hold) recording.finish("screen_takeover")
        screenHold = hold
        if (hold && ownsCapture) {
            coordinator.stopActiveOuraGestureCapture()
            ownsCapture = false
        }
        if (!hold) { nextArmAtMs = 0; main.post { step() } }
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        val armed = coordinator.activeOuraGestureCaptureArmed()
        if (ownsCapture && !armed) {
            // Link loss, re-auth, stall or source switch dropped the capture underneath us.
            ownsCapture = false
            log("Oura: always-listen capture dropped - re-arming when the ring is ready")
        }
        val ringReady = coordinator.ouraLinkPhase.value == OuraLiveSource.LinkPhase.AUTHENTICATED
        when (OuraGestureStandbyPolicy.next(enabled, screenHold, ownsCapture, armed, ringReady, now >= nextArmAtMs)) {
            OuraGestureStandbyPolicy.Action.ARM -> {
                ownsCapture = coordinator.startActiveOuraGestureCapture(
                    OuraLiveSource.STANDBY_RING_SECONDS, continuous = true, onGesture = ::onGesture)
                if (ownsCapture) {
                    armFailures = 0
                    log("Oura: always-listen armed")
                } else {
                    armFailures++
                    nextArmAtMs = now + OuraGestureStandbyPolicy.backoffMs(armFailures)
                }
            }
            OuraGestureStandbyPolicy.Action.DISARM -> {
                coordinator.stopActiveOuraGestureCapture()
                ownsCapture = false
            }
            OuraGestureStandbyPolicy.Action.IDLE -> Unit
        }
        if (enabled && !screenHold && broker.configured && !pollInFlight) {
            pollInFlight = true
            io.execute {
                if (SystemClock.elapsedRealtime() - leaseAtMs >= LEASE_RENEW_MS || leaseAtMs == 0L) {
                    leaseAtMs = if (runCatching { broker.claim(LEASE_SECONDS) }.isSuccess) SystemClock.elapsedRealtime() else 0L
                }
                val sent = SystemClock.elapsedRealtime()
                val response = runCatching { broker.recordingCommands() }.getOrNull()
                val received = SystemClock.elapsedRealtime()
                main.post {
                    pollInFlight = false
                    if (response != null) {
                        val report = recording.sync(response, sent, received, enabled && !screenHold && listening && OuraGestureRecordingService.running)
                        if (report != null) io.execute { runCatching { broker.recordingReport(report) } }
                    }
                }
            }
        }
    }

    private fun onGesture(gesture: OuraTapDetector.Gesture) {
        if (recording.active) return
        val name = when (gesture) {
            OuraTapDetector.Gesture.DOUBLE_TAP -> "double_tap"
            OuraTapDetector.Gesture.TRIPLE_TAP -> "triple_tap"
            else -> return   // quadruple and longer are recorded for the future, never sent
        }
        val now = SystemClock.elapsedRealtime()
        if (!OuraGestureStandbyPolicy.deliverable(now, lastDeliveryMs)) {
            log("Oura: always-listen $name ignored - within ${OuraGestureStandbyPolicy.REFRACTORY_MS} ms of the last one")
            return
        }
        lastDeliveryMs = now
        if (!broker.configured) { lastResult = "no broker paired"; return }
        io.execute {
            if (leaseAtMs == 0L) {
                leaseAtMs = if (runCatching { broker.claim(LEASE_SECONDS) }.isSuccess) SystemClock.elapsedRealtime() else 0L
            }
            val ok = runCatching { broker.send(name) }.getOrDefault(false)
            if (!ok) leaseAtMs = 0L   // re-claim before the next gesture
            lastResult = "$name ${if (ok) "accepted" else "refused"}"
            log("Oura: always-listen sent $name - ${if (ok) "accepted" else "refused or unreachable"}")
        }
    }

    private fun log(line: String) = runCatching { (app as NoopApplication).ble.externalLog(line) }

    companion object {
        private const val PREFS = "oura_gesture_standby"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CALIBRATED = "calibrated"
        private const val TICK_MS = 1_000L
        /** The broker caps a lease at 300 s; renew at well under half of that. */
        private const val LEASE_SECONDS = 300
        private const val LEASE_RENEW_MS = 120_000L

        @Volatile private var instance: OuraGestureStandby? = null
        fun get(context: Context): OuraGestureStandby =
            instance ?: synchronized(this) {
                instance ?: OuraGestureStandby(context.applicationContext).also { instance = it }
            }
    }
}

/** The standby's decisions, pure so they are JVM-tested. */
object OuraGestureStandbyPolicy {
    enum class Action { ARM, DISARM, IDLE }

    /** One deliverable gesture per this window: a ringing double must not read as two commands. */
    const val REFRACTORY_MS = 1_500L

    fun next(
        enabled: Boolean,
        screenHold: Boolean,
        ownsCapture: Boolean,
        captureArmed: Boolean,
        ringReady: Boolean,
        backoffElapsed: Boolean,
    ): Action = when {
        !enabled || screenHold -> if (ownsCapture && captureArmed) Action.DISARM else Action.IDLE
        captureArmed -> Action.IDLE          // ours, or the setup screen's: never steal a capture
        !ringReady || !backoffElapsed -> Action.IDLE
        else -> Action.ARM
    }

    /** Retry delay after [failures] consecutive refused arms: 10 s doubling to a 2-minute cap. */
    fun backoffMs(failures: Int): Long = (10_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(120_000L)

    fun deliverable(nowMs: Long, lastMs: Long): Boolean = lastMs == 0L || nowMs - lastMs >= REFRACTORY_MS
}
