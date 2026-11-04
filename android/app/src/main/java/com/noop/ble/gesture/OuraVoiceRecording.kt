package com.noop.ble.gesture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.noop.NoopApplication
import org.json.JSONObject
import java.io.File

/** Owner-requested private diagnostics, controlled over the already paired gesture channel.
 * Main-thread state; the GATT recorder itself is synchronized. No audio or raw ACM is uploaded.
 */
class OuraVoiceRecording private constructor(private val app: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val coordinator get() = (app as NoopApplication).sourceCoordinator
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var recorder: OuraAcmRecorder? = null
    private var file: File? = null
    private var state: JSONObject? = null
    private var revision = -1
    private var offsetMs = 0.0
    private var startElapsedMs = 0L
    private var deadlineMs = 0L
    private var lastId = ""
    private var report: JSONObject? = null
    private val counts = mutableMapOf("double" to 0, "triple" to 0, "quadruple" to 0)
    private val timeout = Runnable { finish("timeout") }

    val active: Boolean get() = recorder != null && SystemClock.elapsedRealtime() < deadlineMs

    /** Called on main after polling on IO. Offset is broker epoch ms minus local monotonic ms.
     * Use the request midpoint, and save RTT as a clock uncertainty bound, not tap latency.
     */
    fun sync(response: JSONObject, sentMs: Long, receivedMs: Long, available: Boolean): JSONObject? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val command = response.optJSONObject("recording")
        if (recorder != null && (command == null || SystemClock.elapsedRealtime() >= deadlineMs)) finish("expired")
        if (command == null) return report
        val id = command.getString("id")
        if (!id.matches(Regex("[0-9a-f]{32}"))) return report
        if (id != state?.optString("id") && id != lastId) {
            if (recorder != null) finish("replaced")
            lastId = id
            if (!available || command.getString("desired") != "recording") {
                report = failure(id)
                return report
            }
            val generation = coordinator.setActiveOuraAcmRecorder(null)
            if (generation == null) { report = failure(id); return report }
            offsetMs = response.getDouble("broker_ts") * 1000 - (sentMs + receivedMs) / 2.0
            startElapsedMs = SystemClock.elapsedRealtime()
            deadlineMs = minOf(startElapsedMs + 600_000, (command.getDouble("expires") * 1000 - offsetMs).toLong())
            val opened = runCatching { OuraAcmRecorder.open(app.filesDir, "voice", generation, System.currentTimeMillis()) }.getOrNull()
            if (opened == null) { report = failure(id); return report }
            recorder = opened.first
            file = opened.second
            state = JSONObject(command.toString()).put("clock_offset_ms", offsetMs)
                .put("clock_rtt_ms", receivedMs - sentMs).put("phone_start_elapsed_ms", startElapsedMs)
                .put("activity", "unknown").put("schema", "noop-oura-labels-v1")
            revision = -1
            counts.keys.toList().forEach { counts[it] = 0 }
            wakeLock = (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "noop:ring-recording").apply { acquire(600_000L) }
            coordinator.setActiveOuraAcmRecorder(recorder)
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, (deadlineMs - startElapsedMs).coerceAtLeast(0))
        }
        if (recorder != null && command.optString("id") == state?.optString("id")) {
            if (revision != command.getInt("revision")) {
                state?.put("labels", command.getJSONArray("labels"))
                state?.put("desired", command.getString("desired"))
                if (command.has("broker_stop")) state?.put("broker_stop", command.getDouble("broker_stop"))
                revision = command.getInt("revision")
                if (!saveSidecar()) {
                    finish("labels_write_failed")
                    report = failure(id)
                    return report
                }
            }
            if (command.getString("desired") == "stop") finish("owner_stop")
            else report = status("recording")
        }
        return report
    }

    fun decision(atMs: Long, gesture: OuraTapDetector.Gesture) {
        if (!active) return
        val key = when (gesture) {
            OuraTapDetector.Gesture.DOUBLE_TAP -> "double"
            OuraTapDetector.Gesture.TRIPLE_TAP -> "triple"
            OuraTapDetector.Gesture.QUADRUPLE_TAP -> "quadruple"
        }
        counts[key] = counts.getValue(key) + 1
        recorder?.gesture(atMs, gesture.name)
    }

    fun finish(reason: String) {
        if (recorder == null) return
        main.removeCallbacks(timeout)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        state?.put("phone_stop_elapsed_ms", SystemClock.elapsedRealtime())?.put("stop_reason", reason)
        report = status("saved")
        state?.put("counts", JSONObject(counts.toMap()))
        coordinator.setActiveOuraAcmRecorder(null)
        recorder?.close()
        recorder = null
        if (!saveSidecar()) report = failure(lastId)
    }

    private fun status(value: String) = JSONObject().put("id", state!!.getString("id"))
        .put("status", value).put("counts", JSONObject(counts.toMap())).put("frames", recorder?.frames ?: 0)

    private fun failure(id: String) = JSONObject().put("id", id).put("status", "failed")
        .put("counts", JSONObject().put("double", 0).put("triple", 0).put("quadruple", 0)).put("frames", 0)

    private fun saveSidecar(): Boolean {
        val target = file?.let { File(it.parentFile, it.nameWithoutExtension + ".labels.json") } ?: return false
        val pending = File(target.path + ".new")
        return runCatching {
            pending.writeText(state!!.toString(2))
            check(pending.renameTo(target))
        }.onFailure { (app as NoopApplication).ble.externalLog("Oura: voice labels save failed; raw recording retained") }.isSuccess
    }

    companion object {
        @Volatile private var instance: OuraVoiceRecording? = null
        fun get(context: Context): OuraVoiceRecording = instance ?: synchronized(this) {
            instance ?: OuraVoiceRecording(context.applicationContext).also { instance = it }
        }
    }
}
