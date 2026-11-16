package com.noop.breathcam

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// MARK: - Breath phase from a displacement trace (pure, JVM-tested)
//
// Input: one torso displacement sample per camera frame (up = positive, any unit). Output: the breath
// phase {INHALE, EXHALE, HOLD, UNKNOWN}, a breath-by-breath rate and a confidence. Causal and
// clock-injected so it runs identically live and over a recorded clip.
//
// Phase comes from the SIGN of the smoothed chest velocity, with hysteresis; a hold is velocity staying
// inside a dead band (scaled to this session's own breathing) for at least [BreathTuning.HOLD_MIN_MS].
// Upward torso motion is inhalation for an upright sitter (shoulders and clavicles rise as the rib cage
// lifts); [polarity] flips that when a check against the pacer or the heart says otherwise.
//
// HONEST LIMITS: an ESTIMATE from video of clothing and shoulders, not spirometry. Talking, fidgeting
// and swaying are motion too; those frames go UNKNOWN rather than guessed. Unvalidated on recorded
// clips until docs/experiments/BREATH_CAMERA.md's protocol has been run — hence Experimental.

enum class BreathPhase { INHALE, EXHALE, HOLD, UNKNOWN }

/** Which breath a HOLD followed — breath-work protocols treat the two retentions differently. */
enum class HoldKind { NONE, AFTER_INHALE, AFTER_EXHALE }

data class BreathEstimate(
    val phase: BreathPhase,
    /** When the current phase began (backdated to the motion change, not to the decision). */
    val phaseSinceMs: Long,
    val holdKind: HoldKind,
    /** Breaths per minute from the last few inhale-onset intervals, or null until two full cycles. */
    val rateBpm: Double?,
    /** 0..1: how regular the recent cycles were and how clean the motion is. */
    val confidence: Double,
    /** Duration of the last completed inhale / exhale, ms (null until seen). */
    val lastInhaleMs: Long?,
    val lastExhaleMs: Long?,
    /** Inhale onsets seen so far (a completed cycle count + 1). */
    val inhaleOnsets: Int,
    /** Smoothed torso position scaled to about -1..1 over the recent breaths (for drawing only). */
    val level: Double,
    /** Smoothed, drift-removed displacement in the input unit (for coupling with heart rate). */
    val smoothed: Double,
)

internal object BreathTuning {
    /** Two cascaded EWMAs of this time constant low-pass the displacement (~1 Hz). */
    const val SMOOTH_TAU_S = 0.15

    /** Group delay of that smoothing at breathing frequencies (≈ 2τ). */
    const val SMOOTH_DELAY_MS = 300L

    /** Velocity is the slope over this trailing span. */
    const val VELOCITY_SPAN_MS = 300L

    /** Slow velocity bias (posture creep) removal. */
    const val BIAS_TAU_S = 25.0

    /** Speed scale = this percentile of |velocity| over the last [SCALE_WINDOW_MS]. */
    const val SCALE_PERCENTILE = 0.80
    const val SCALE_WINDOW_MS = 30_000L

    /** Moving faster than ON × scale is a direction; slower than OFF × scale is "still". */
    const val ON_FRACTION = 0.22
    const val OFF_FRACTION = 0.12

    /** A new direction must persist this long before the phase flips. */
    const val FLIP_PERSIST_MS = 150L

    /** Still for this long after breathing = a HOLD (shorter stillness is a turnaround). */
    const val HOLD_MIN_MS = 1_200L

    /** Faster than this × scale is body movement, not breathing. */
    const val ARTIFACT_FRACTION = 7.0
    const val ARTIFACT_QUIET_MS = 1_500L

    /** Warm-up before any phase is reported. */
    const val WARMUP_MS = 3_000L

    /** Breath intervals outside this are not breaths (a gulp, or a long hold-plus-breath). */
    const val MIN_CYCLE_MS = 1_500L
    const val MAX_CYCLE_MS = 40_000L

    /** Gap in the frame stream that resets the estimator. */
    const val GAP_RESET_MS = 2_000L
}

class BreathPhaseEstimator(
    /** Absolute speed floor (input units per second) below which nothing counts as breathing. */
    private val minScalePerSec: Double = 0.08,
) {
    /** +1: upward motion is inhalation; -1: inverted. */
    var polarity: Int = 1
        set(value) { field = if (value < 0) -1 else 1 }

    private var startMs = Long.MIN_VALUE
    private var lastMs = Long.MIN_VALUE
    private var s1 = 0.0
    private var s2 = 0.0
    private val recent = ArrayDeque<Pair<Long, Double>>() // (t, smoothed) for the velocity span
    private var bias = 0.0
    private val speeds = ArrayDeque<Pair<Long, Double>>() // (t, |v|) for the scale
    private val levels = ArrayDeque<Pair<Long, Double>>() // (t, smoothed) for the drawing level
    private var drift = 0.0

    private var phase = BreathPhase.UNKNOWN
    private var phaseSince = 0L
    private var holdKind = HoldKind.NONE
    private var lastMoving = BreathPhase.UNKNOWN
    private var candidate = 0
    private var candidateSince = 0L
    private var stillSince = Long.MIN_VALUE
    private var artifactUntil = Long.MIN_VALUE
    private var lastVcSign = 0
    private var signChangeMs = 0L

    private val onsets = ArrayDeque<Long>()
    private var onsetCount = 0
    private var exhaleStart = Long.MIN_VALUE
    private var inhaleStart = Long.MIN_VALUE
    private var lastInhaleDur: Long? = null
    private var lastExhaleDur: Long? = null
    private var validFraction = 1.0

    /** Sign of the current torso motion in the input's own sense (+1 up, -1 down, 0 still/unknown). */
    val motionSign: Int get() = if (phase == BreathPhase.UNKNOWN) 0 else candidate

    fun reset() {
        startMs = Long.MIN_VALUE; lastMs = Long.MIN_VALUE
        s1 = 0.0; s2 = 0.0; recent.clear(); bias = 0.0; speeds.clear(); levels.clear(); drift = 0.0
        phase = BreathPhase.UNKNOWN; phaseSince = 0L; holdKind = HoldKind.NONE; lastMoving = BreathPhase.UNKNOWN
        candidate = 0; candidateSince = 0L; stillSince = Long.MIN_VALUE; artifactUntil = Long.MIN_VALUE
        lastVcSign = 0; signChangeMs = 0L
        onsets.clear(); onsetCount = 0; exhaleStart = Long.MIN_VALUE; inhaleStart = Long.MIN_VALUE
        lastInhaleDur = null; lastExhaleDur = null; validFraction = 1.0
    }

    /**
     * Feed one sample. [valid] false (the tracker lost the torso) counts toward UNKNOWN. Returns the
     * estimate after this sample.
     */
    fun offer(tMs: Long, displacement: Double, valid: Boolean = true): BreathEstimate {
        if (lastMs != Long.MIN_VALUE && (tMs <= lastMs - 1 || tMs - lastMs > BreathTuning.GAP_RESET_MS)) reset()
        if (startMs == Long.MIN_VALUE) {
            startMs = tMs; lastMs = tMs; s1 = displacement; s2 = displacement
            phaseSince = tMs
            return snapshot(tMs)
        }
        val dt = max(1L, tMs - lastMs) / 1000.0
        lastMs = tMs
        validFraction += ((if (valid) 1.0 else 0.0) - validFraction) * min(1.0, dt / 5.0)
        if (!valid) {
            setPhase(BreathPhase.UNKNOWN, tMs)
            return snapshot(tMs)
        }

        val a = 1.0 - kotlin.math.exp(-dt / BreathTuning.SMOOTH_TAU_S)
        s1 += a * (displacement - s1)
        s2 += a * (s1 - s2)
        recent.addLast(tMs to s2)
        while (recent.size > 2 && tMs - recent.first().first > BreathTuning.VELOCITY_SPAN_MS) recent.removeFirst()
        val (t0, y0) = recent.first()
        val v = if (tMs > t0) (s2 - y0) / ((tMs - t0) / 1000.0) else 0.0
        val warm = tMs - startMs >= BreathTuning.WARMUP_MS
        val scale = scale()
        val usable = warm && scale >= minScalePerSec

        // Artifact: far faster than this session's breathing.
        if (usable && abs(v - bias) > BreathTuning.ARTIFACT_FRACTION * scale) {
            artifactUntil = tMs + BreathTuning.ARTIFACT_QUIET_MS
        }
        val inArtifact = tMs < artifactUntil
        if (!inArtifact) {
            bias += (v - bias) * min(1.0, dt / BreathTuning.BIAS_TAU_S)
            speeds.addLast(tMs to abs(v - bias))
        }
        while (speeds.isNotEmpty() && tMs - speeds.first().first > BreathTuning.SCALE_WINDOW_MS) speeds.removeFirst()
        drift += bias * dt
        levels.addLast(tMs to (s2 - drift))
        while (levels.isNotEmpty() && tMs - levels.first().first > 20_000L) levels.removeFirst()

        if (inArtifact || !usable) {
            setPhase(BreathPhase.UNKNOWN, tMs)
            candidate = 0
            stillSince = Long.MIN_VALUE
            return snapshot(tMs)
        }

        val vc = v - bias
        // The turnaround: where the velocity last changed sign. A new phase is dated from here (shifted
        // back by the filter's delay), not from the later moment it cleared the threshold, so inhale and
        // exhale durations stay unbiased when one is faster than the other.
        val vcSign = if (vc > 0) 1 else if (vc < 0) -1 else 0
        if (vcSign != 0 && vcSign != lastVcSign) { signChangeMs = tMs; lastVcSign = vcSign }
        val dir = when {
            abs(vc) >= BreathTuning.ON_FRACTION * scale -> if (vc > 0) 1 else -1
            abs(vc) <= BreathTuning.OFF_FRACTION * scale -> 0
            else -> candidate // inside the hysteresis band: keep what we had
        }
        if (dir != candidate) { candidate = dir; candidateSince = tMs }
        if (dir == 0) {
            if (stillSince == Long.MIN_VALUE) stillSince = tMs
        } else {
            stillSince = Long.MIN_VALUE
        }

        if (dir != 0 && tMs - candidateSince >= BreathTuning.FLIP_PERSIST_MS) {
            val moving = if (dir * polarity > 0) BreathPhase.INHALE else BreathPhase.EXHALE
            if (phase != moving) setPhase(moving, min(candidateSince, signChangeMs) - BreathTuning.SMOOTH_DELAY_MS)
        } else if (dir == 0 && stillSince != Long.MIN_VALUE &&
            tMs - stillSince >= BreathTuning.HOLD_MIN_MS && phase != BreathPhase.HOLD &&
            (lastMoving == BreathPhase.INHALE || lastMoving == BreathPhase.EXHALE)
        ) {
            holdKind = if (lastMoving == BreathPhase.INHALE) HoldKind.AFTER_INHALE else HoldKind.AFTER_EXHALE
            setPhase(BreathPhase.HOLD, stillSince - BreathTuning.SMOOTH_DELAY_MS)
        }
        return snapshot(tMs)
    }

    private fun setPhase(next: BreathPhase, atMs: Long) {
        if (next == phase) return
        val prev = phase
        if (next == BreathPhase.INHALE) {
            if (prev == BreathPhase.EXHALE || (prev == BreathPhase.HOLD && lastMoving == BreathPhase.EXHALE)) {
                if (exhaleStart != Long.MIN_VALUE) lastExhaleDur = atMs - exhaleStart
                noteOnset(atMs)
            } else if (prev == BreathPhase.UNKNOWN) {
                onsets.clear() // a fresh start: do not bridge an interval across an unknown stretch
            }
            inhaleStart = atMs
        }
        if (next == BreathPhase.EXHALE) {
            if (inhaleStart != Long.MIN_VALUE &&
                (prev == BreathPhase.INHALE || (prev == BreathPhase.HOLD && lastMoving == BreathPhase.INHALE))
            ) lastInhaleDur = atMs - inhaleStart
            exhaleStart = atMs
        }
        if (next == BreathPhase.UNKNOWN) { lastMoving = BreathPhase.UNKNOWN; onsets.clear() }
        if (next == BreathPhase.INHALE || next == BreathPhase.EXHALE) { lastMoving = next; holdKind = HoldKind.NONE }
        phase = next
        phaseSince = atMs
    }

    private fun noteOnset(atMs: Long) {
        val prev = onsets.lastOrNull()
        if (prev != null) {
            val gap = atMs - prev
            if (gap < BreathTuning.MIN_CYCLE_MS) return // a flutter, not a new breath
            if (gap > BreathTuning.MAX_CYCLE_MS) onsets.clear()
        }
        onsets.addLast(atMs)
        onsetCount++
        while (onsets.size > 5) onsets.removeFirst()
    }

    private fun scale(): Double {
        if (speeds.size < 10) return 0.0
        val sorted = speeds.map { it.second }.sorted()
        return sorted[((sorted.size - 1) * BreathTuning.SCALE_PERCENTILE).toInt()]
    }

    private fun snapshot(tMs: Long): BreathEstimate {
        val intervals = onsets.zipWithNext { a, b -> (b - a).toDouble() }
        var rate: Double? = null
        var regularity = 0.0
        if (intervals.size >= 1) {
            val med = intervals.sorted()[intervals.size / 2]
            // A cycle still in progress that already outlasts the median means the rate is slower now.
            val open = onsets.lastOrNull()?.let { (tMs - it).toDouble() } ?: 0.0
            val effective = if (open > med * 1.25 && phase != BreathPhase.UNKNOWN) open else med
            rate = 60_000.0 / effective
            if (intervals.size >= 2) {
                val m = intervals.average()
                val sd = sqrt(intervals.sumOf { (it - m) * (it - m) } / intervals.size)
                regularity = (1.0 - sd / m).coerceIn(0.0, 1.0)
            } else {
                regularity = 0.5
            }
        }
        val known = phase != BreathPhase.UNKNOWN
        val confidence = if (!known) 0.0 else (0.35 + 0.65 * regularity) * validFraction.coerceIn(0.0, 1.0)
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for ((_, y) in levels) { lo = min(lo, y); hi = max(hi, y) }
        val cur = levels.lastOrNull()?.second ?: 0.0
        val span = hi - lo
        val level = if (span > 1e-9) ((cur - lo) / span * 2.0 - 1.0) * polarity else 0.0
        return BreathEstimate(
            phase = phase,
            phaseSinceMs = phaseSince,
            holdKind = if (phase == BreathPhase.HOLD) holdKind else HoldKind.NONE,
            rateBpm = if (known) rate else null,
            confidence = confidence,
            lastInhaleMs = lastInhaleDur,
            lastExhaleMs = lastExhaleDur,
            inhaleOnsets = onsetCount,
            level = level,
            smoothed = (cur) * polarity,
        )
    }
}

/**
 * Checks the motion polarity against the pacer: while the user follows the guide, chest velocity should
 * agree in sign with the guide's inhale(+1)/exhale(-1). After [minSamples] confident samples a strong
 * disagreement says the camera sees the torso inverted (a reclined phone, a mirror) and votes -1.
 */
class PacerPolarityCheck(private val minSamples: Int = 150) {
    private var agree = 0.0
    private var n = 0

    fun reset() { agree = 0.0; n = 0 }

    /** [pacerSign] +1 while the guide says inhale, -1 for exhale, 0 for hold/none. */
    fun offer(pacerSign: Int, velocitySign: Int) {
        if (pacerSign == 0 || velocitySign == 0) return
        agree += if (pacerSign == velocitySign) 1.0 else -1.0
        n++
    }

    /** +1, -1, or null while undecided. */
    fun verdict(): Int? {
        if (n < minSamples) return null
        val r = agree / n
        return when {
            r <= -0.3 -> -1
            r >= 0.3 -> 1
            else -> null
        }
    }
}
