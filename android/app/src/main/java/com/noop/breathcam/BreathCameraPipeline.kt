package com.noop.breathcam

/**
 * Frames in, breath estimate out: the whole camera path minus CameraX, so the live analyzer and the
 * offline clip evaluator run byte-for-byte the same code. Each frame is reduced to one row profile per
 * candidate region and released; only the scalar displacement survives.
 */
class BreathCameraPipeline(
    private val regions: List<UprightRect> = BreathRegions.strips,
) {
    private val trackers = List(regions.size) { ProfileShiftTracker() }
    private val selector = StripSelector(regions.size)
    val estimator = BreathPhaseEstimator()
    private var mapping: UprightMapping? = null
    private var selected = 0
    private var offset = 0.0
    private var lastFed = 0.0
    private var lastFrameMs = Long.MIN_VALUE

    /** Which candidate region is driving the estimate (for a framing hint). */
    val selectedRegion: Int get() = selected

    fun reset() {
        trackers.forEach { it.reset() }
        selector.reset()
        estimator.reset()
        mapping = null; selected = 0; offset = 0.0; lastFed = 0.0; lastFrameMs = Long.MIN_VALUE
    }

    fun onFrame(frame: LumaFrame, rotationDegrees: Int, tMs: Long): BreathEstimate {
        if (lastFrameMs != Long.MIN_VALUE && tMs - lastFrameMs > BreathTuning.GAP_RESET_MS) reset()
        lastFrameMs = tMs
        val m = mapping?.takeIf {
            it.rotation == rotationDegrees && it.bufferWidth == frame.width && it.bufferHeight == frame.height
        } ?: UprightMapping(rotationDegrees, frame.width, frame.height).also {
            trackers.forEach { t -> t.reset() }
            mapping = it
        }
        val disp = DoubleArray(regions.size)
        val valid = BooleanArray(regions.size)
        for (i in regions.indices) {
            val s = trackers[i].offer(m.verticalProfile(frame, regions[i]))
            disp[i] = s.displacementUpPx
            valid[i] = s.valid
        }
        val pick = selector.offer(tMs, disp)
        if (pick != selected) {
            // Keep the fed trace continuous across a region switch, so the switch is not read as motion.
            offset = lastFed - disp[pick]
            selected = pick
        }
        val fed = disp[selected] + offset
        lastFed = fed
        return estimator.offer(tMs, fed, valid[selected])
    }
}
