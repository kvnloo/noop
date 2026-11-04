package com.noop.breathcam

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// MARK: - Camera breath motion (pure, JVM-tested)
//
// EXPERIMENTAL, default off. Turns the front camera's luminance plane into ONE number per frame: how far
// the upper torso has moved vertically (pixels, up = positive) since tracking started. Nothing here sees
// a colour image, keeps a frame, or writes anything: a frame is reduced to a 1-D row profile and dropped.
//
// Method (re-implemented from the papers, no code copied):
//  • Profile-1D motion (Bartula, Tigges & Muehlsteff 2013, "Camera-based system for contactless
//    monitoring of respiration", IEEE EMBC, doi:10.1109/EMBC.2013.6610090): average each image row of a
//    torso region into a vertical intensity profile, then find the vertical shift between profiles. In
//    the Boccignone et al. 2025 benchmark (ACM Computing Surveys 58(5), doi:10.1145/3771763; code
//    phuselab/resPyre, GPL-3.0 — not used here) Profile-1D reached COHFACE respiration-rate MAE 0.84–1.29
//    breaths/min at ~0.1 ms per frame, ahead of the face-video deep models (MTTS-CAN 2.31, BigSmall 3.92).
//  • Vertical motion only, several candidate regions, keep the one with the best breathing-band signal —
//    the shape of Google's validated front-camera method (Bae et al. 2022, Commun Med 2:40,
//    doi:10.1038/s43856-022-00102-x: vertical optical flow of the upper torso, 5 regions, MAE 0.78
//    breaths/min in 50 seated adults, 1–4 ms/frame on Pixel 3a/4).

/** A read-only view of an 8-bit luminance plane: a CameraX Y plane, or one decoded grey video frame. */
class LumaFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int = width,
    val pixelStride: Int = 1,
) {
    init {
        require(width > 0 && height > 0) { "empty frame" }
        require(rowStride >= width * pixelStride || pixelStride > 1) { "row stride too small" }
        require(data.size >= (height - 1) * rowStride + (width - 1) * pixelStride + 1) { "buffer too small" }
    }
}

/** A rectangle in UPRIGHT display coordinates as fractions of the upright frame (0..1, y grows down). */
data class UprightRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left in 0.0..1.0 && right in 0.0..1.0 && top in 0.0..1.0 && bottom in 0.0..1.0)
        require(right > left && bottom > top)
    }
}

/**
 * Maps upright (display-oriented) pixel coordinates onto the sensor buffer. [rotation] is CameraX's
 * `rotationDegrees`: the clockwise rotation that makes the buffer upright. A front-camera horizontal
 * mirror does not matter here — row averages and vertical shifts are unchanged by a left-right flip.
 */
class UprightMapping(val rotation: Int, val bufferWidth: Int, val bufferHeight: Int) {
    val uprightWidth: Int = if (rotation % 180 == 0) bufferWidth else bufferHeight
    val uprightHeight: Int = if (rotation % 180 == 0) bufferHeight else bufferWidth

    // buffer x = x0 + xu*u + xv*v ; buffer y = y0 + yu*u + yv*v
    private val x0: Int
    private val xu: Int
    private val xv: Int
    private val y0: Int
    private val yu: Int
    private val yv: Int

    init {
        when (rotation) {
            0 -> { x0 = 0; xu = 1; xv = 0; y0 = 0; yu = 0; yv = 1 }
            90 -> { x0 = 0; xu = 0; xv = 1; y0 = bufferHeight - 1; yu = -1; yv = 0 }
            180 -> { x0 = bufferWidth - 1; xu = -1; xv = 0; y0 = bufferHeight - 1; yu = 0; yv = -1 }
            270 -> { x0 = bufferWidth - 1; xu = 0; xv = -1; y0 = 0; yu = 1; yv = 0 }
            else -> throw IllegalArgumentException("rotation must be 0/90/180/270, was $rotation")
        }
    }

    fun bufferX(u: Int, v: Int): Int = x0 + xu * u + xv * v
    fun bufferY(u: Int, v: Int): Int = y0 + yu * u + yv * v

    /**
     * The vertical (upright) intensity profile of [roi]: one value per upright row, the mean luminance
     * across that row (every [columnStep]-th column), lightly smoothed with a [1,2,1] kernel.
     */
    fun verticalProfile(frame: LumaFrame, roi: UprightRect, columnStep: Int = 2): DoubleArray {
        require(frame.width == bufferWidth && frame.height == bufferHeight) { "frame/mapping size mismatch" }
        val u0 = (roi.left * uprightWidth).toInt().coerceIn(0, uprightWidth - 1)
        val u1 = (roi.right * uprightWidth).toInt().coerceIn(u0 + 1, uprightWidth)
        val v0 = (roi.top * uprightHeight).toInt().coerceIn(0, uprightHeight - 1)
        val v1 = (roi.bottom * uprightHeight).toInt().coerceIn(v0 + 1, uprightHeight)
        val step = max(1, columnStep)
        val raw = DoubleArray(v1 - v0)
        val data = frame.data
        for (v in v0 until v1) {
            var sum = 0L
            var n = 0
            var u = u0
            while (u < u1) {
                val idx = bufferY(u, v) * frame.rowStride + bufferX(u, v) * frame.pixelStride
                sum += (data[idx].toInt() and 0xFF)
                n++
                u += step
            }
            raw[v - v0] = sum.toDouble() / n
        }
        if (raw.size < 3) return raw
        val out = DoubleArray(raw.size)
        out[0] = raw[0]
        out[raw.size - 1] = raw[raw.size - 1]
        for (i in 1 until raw.size - 1) out[i] = (raw[i - 1] + 2 * raw[i] + raw[i + 1]) / 4.0
        return out
    }
}

/** One tracked frame: cumulative upward displacement, and how well the profile matched its key. */
data class ShiftSample(val displacementUpPx: Double, val correlation: Double, val valid: Boolean)

/**
 * Tracks the vertical shift of a row profile against a key profile, with sub-pixel parabolic refinement,
 * and accumulates it into a displacement. The key is replaced (its offset carried) once the shift passes
 * half the search range, so slow drift never runs off the search window.
 */
class ProfileShiftTracker(private val maxShiftPx: Int = MAX_SHIFT_PX) {
    private var key: DoubleArray? = null
    private var keyOffsetDownPx = 0.0

    fun reset() {
        key = null
        keyOffsetDownPx = 0.0
    }

    fun offer(profile: DoubleArray): ShiftSample {
        val current = zNormalize(profile) ?: return ShiftSample(-keyOffsetDownPx, 0.0, valid = false)
        val k = key
        if (k == null || k.size != current.size || current.size <= 2 * maxShiftPx + MIN_OVERLAP) {
            key = current
            return ShiftSample(-keyOffsetDownPx, 1.0, valid = k != null && k.size == current.size)
        }
        val costs = DoubleArray(2 * maxShiftPx + 1) { j -> cost(current, k, j - maxShiftPx) }
        var best = 0
        for (j in costs.indices) if (costs[j] < costs[best]) best = j
        val shiftInt = best - maxShiftPx
        var frac = 0.0
        if (best in 1 until costs.size - 1) {
            val a = costs[best - 1]
            val b = costs[best]
            val c = costs[best + 1]
            val denom = a - 2 * b + c
            if (denom > 1e-12) frac = ((a - c) / (2 * denom)).coerceIn(-0.5, 0.5)
        }
        val shift = shiftInt + frac
        // For z-normalised profiles the mean squared difference is 2(1 - r).
        val correlation = (1.0 - costs[best] / 2.0).coerceIn(-1.0, 1.0)
        val atEdge = abs(shiftInt) == maxShiftPx
        val total = keyOffsetDownPx + shift
        if (atEdge || abs(shift) > maxShiftPx / 2.0 || correlation < REKEY_CORRELATION) {
            keyOffsetDownPx = total
            key = current
        }
        val valid = !atEdge && correlation >= VALID_CORRELATION
        return ShiftSample(-total, correlation, valid)
    }

    /** Mean squared difference when the current profile is the key moved DOWN by [shift] rows. */
    private fun cost(cur: DoubleArray, key: DoubleArray, shift: Int): Double {
        var s = 0.0
        val from = maxShiftPx
        val to = cur.size - maxShiftPx
        for (i in from until to) {
            val d = cur[i] - key[i - shift]
            s += d * d
        }
        return s / (to - from)
    }

    companion object {
        const val MAX_SHIFT_PX = 6
        private const val MIN_OVERLAP = 12
        const val VALID_CORRELATION = 0.80
        private const val REKEY_CORRELATION = 0.90

        internal fun zNormalize(p: DoubleArray): DoubleArray? {
            if (p.isEmpty()) return null
            val mean = p.average()
            var v = 0.0
            for (x in p) v += (x - mean) * (x - mean)
            val sd = sqrt(v / p.size)
            if (sd < 0.5) return null // a flat region (lens covered, dark room): nothing to track
            return DoubleArray(p.size) { (p[it] - mean) / sd }
        }
    }
}

/**
 * Picks the candidate region whose displacement carries the most breathing-band signal over the last
 * [windowMs], with hysteresis so the choice does not flicker. Breathing-band score = variance of the
 * 0.6 s moving average of the detrended trace over the variance of what that average removes.
 */
class StripSelector(
    private val strips: Int,
    private val windowMs: Long = 20_000,
    private val evalEveryMs: Long = 1_000,
) {
    private val t = ArrayDeque<Long>()
    private val x = Array(strips) { ArrayDeque<Double>() }
    private var lastEval = Long.MIN_VALUE
    private var challenger = -1
    private var challengerWins = 0

    var selected: Int = 0
        private set
    var scores: DoubleArray = DoubleArray(strips)
        private set

    fun reset() {
        t.clear(); x.forEach { it.clear() }
        lastEval = Long.MIN_VALUE; challenger = -1; challengerWins = 0; selected = 0
        scores = DoubleArray(strips)
    }

    fun offer(tMs: Long, displacements: DoubleArray): Int {
        require(displacements.size == strips)
        t.addLast(tMs)
        for (i in 0 until strips) x[i].addLast(displacements[i])
        while (t.isNotEmpty() && tMs - t.first() > windowMs) {
            t.removeFirst(); x.forEach { it.removeFirst() }
        }
        if (lastEval != Long.MIN_VALUE && tMs - lastEval < evalEveryMs) return selected
        lastEval = tMs
        if (t.size < 30 || tMs - t.first() < 6_000) return selected
        val fps = (t.size - 1) * 1000.0 / max(1L, tMs - t.first())
        val half = max(1, (fps * 0.3).toInt())
        scores = DoubleArray(strips) { bandScore(x[it], half) }
        var best = 0
        for (i in 1 until strips) if (scores[i] > scores[best]) best = i
        if (best != selected && scores[best] > SWITCH_RATIO * scores[selected]) {
            if (best == challenger) challengerWins++ else { challenger = best; challengerWins = 1 }
            if (challengerWins >= SWITCH_PERSIST) { selected = best; challenger = -1; challengerWins = 0 }
        } else {
            challenger = -1; challengerWins = 0
        }
        return selected
    }

    private fun bandScore(series: ArrayDeque<Double>, half: Int): Double {
        val n = series.size
        val y = DoubleArray(n) { series[it] }
        // Remove a straight-line trend (posture creep).
        val mt = (n - 1) / 2.0
        val my = y.average()
        var num = 0.0; var den = 0.0
        for (i in 0 until n) { num += (i - mt) * (y[i] - my); den += (i - mt) * (i - mt) }
        val slope = if (den > 0) num / den else 0.0
        for (i in 0 until n) y[i] -= my + slope * (i - mt)
        var low = 0.0; var high = 0.0
        for (i in half until n - half) {
            var s = 0.0
            for (j in i - half..i + half) s += y[j]
            val m = s / (2 * half + 1)
            low += m * m
            high += (y[i] - m) * (y[i] - m)
        }
        return low / max(high, 1e-9)
    }

    companion object {
        private const val SWITCH_RATIO = 1.5
        private const val SWITCH_PERSIST = 3
    }
}

/** The candidate torso regions, in upright fractions: three columns across the shoulder/chest band. */
object BreathRegions {
    const val BAND_TOP = 0.42
    const val BAND_BOTTOM = 0.95

    val strips: List<UprightRect> = listOf(
        UprightRect(0.04, BAND_TOP, 0.36, BAND_BOTTOM),
        UprightRect(0.34, BAND_TOP, 0.66, BAND_BOTTOM),
        UprightRect(0.64, BAND_TOP, 0.96, BAND_BOTTOM),
        UprightRect(0.10, BAND_TOP, 0.90, BAND_BOTTOM),
    )
}
