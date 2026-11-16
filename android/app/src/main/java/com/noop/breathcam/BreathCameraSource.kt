package com.noop.breathcam

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import com.noop.ui.NoopPrefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The one reader/writer of the Experimental breath-camera switch. Default OFF. */
object BreathCameraPrefs {
    private const val KEY_ENABLED = "experimental.breathCamera"
    private const val KEY_ADAPT = "experimental.breathCameraAdaptPace"

    fun enabled(context: Context): Boolean = NoopPrefs.of(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, on: Boolean) = NoopPrefs.of(context).edit().putBoolean(KEY_ENABLED, on).apply()

    /** Let the measured breath + heart coupling move the pacer (inside 4.5–7 br/min). Default OFF. */
    fun adaptPace(context: Context): Boolean = NoopPrefs.of(context).getBoolean(KEY_ADAPT, false)
    fun setAdaptPace(context: Context, on: Boolean) = NoopPrefs.of(context).edit().putBoolean(KEY_ADAPT, on).apply()
}

/**
 * Front camera → [BreathCameraPipeline], on-device only. Binds ONE CameraX ImageAnalysis use case at a
 * small resolution (no preview, no capture, no recording). Each frame's luminance plane is copied into one
 * reused scratch array, reduced to row profiles, and the ImageProxy is closed; no frame is kept, written or
 * sent anywhere. Unbinding (stop or lifecycle end) turns the camera — and the system camera indicator — off.
 */
class BreathCameraSource( // one instance per session: stop() is final
    private val onEstimate: (tMs: Long, estimate: BreathEstimate) -> Unit,
) {
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private val pipeline = BreathCameraPipeline()
    private var scratch: ByteArray? = null
    private var lastFrameMs = 0L
    private var stopped = false
    private var costNs = 0L
    private var costFrames = 0
    private var costSinceMs = 0L

    /** Polarity is settled by the pacer check; the analyzer thread reads it. */
    @Volatile var polarity: Int = 1

    /** Binds the analysis use case. Must run on the main thread; throws if the camera cannot start. */
    suspend fun start(context: Context, owner: LifecycleOwner) {
        if (analysis != null || stopped) return
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "noop-breath-camera") }
        executor = exec
        try {
            val p = ProcessCameraProvider.awaitInstance(context)
            if (stopped) return
            val use = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(TARGET_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            use.setAnalyzer(exec, ::analyze)
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, use)
            provider = p
            analysis = use
        } catch (t: Throwable) {
            stop()
            throw t
        }
    }

    fun stop() {
        stopped = true
        analysis?.clearAnalyzer()
        analysis?.let { provider?.unbind(it) }
        analysis = null
        provider = null
        executor?.shutdown() // a frame already on the analyzer thread finishes and closes its image
        executor = null
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMs < FRAME_INTERVAL_MS) return
            lastFrameMs = now
            val plane = image.planes[0]
            val buf = plane.buffer
            buf.rewind()
            val n = buf.remaining()
            val bytes = scratch?.takeIf { it.size == n } ?: ByteArray(n).also { scratch = it }
            buf.get(bytes)
            val frame = LumaFrame(bytes, image.width, image.height, plane.rowStride, plane.pixelStride)
            pipeline.estimator.polarity = polarity
            val t0 = System.nanoTime()
            val estimate = pipeline.onFrame(frame, image.imageInfo.rotationDegrees, now)
            costNs += System.nanoTime() - t0
            costFrames++
            if (now - costSinceMs >= COST_LOG_EVERY_MS) {
                // Rare, content-free evidence for the on-device latency claim in BREATH_CAMERA.md.
                Log.i(TAG, "pipeline ${String.format(java.util.Locale.US, "%.2f", costNs / 1e6 / costFrames)} ms/frame over $costFrames frames " +
                    "(${image.width}x${image.height}, rot ${image.imageInfo.rotationDegrees})")
                costNs = 0; costFrames = 0; costSinceMs = now
            }
            onEstimate(now, estimate)
        } finally {
            image.close()
        }
    }

    companion object {
        /** Small on purpose: breathing is large, slow motion. 320×240 sensor ≈ 240×320 upright. */
        val TARGET_SIZE = Size(320, 240)

        /** ~15 fps is enough for ≤ 0.7 Hz motion and keeps the CPU near idle. */
        const val FRAME_INTERVAL_MS = 62L

        private const val TAG = "BreathCam"
        private const val COST_LOG_EVERY_MS = 30_000L
    }
}
