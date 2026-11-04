package com.noop.breathcam

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// MARK: - Breath ↔ heart-rate coupling and an adaptive resonance pacer (pure, JVM-tested)
//
// The camera tells us WHEN each breath actually began and ended; the ring/strap R-R tells us what the
// heart did. Together they give two things neither gives alone:
//
//  • RSA amplitude per MEASURED breath — the peak-to-trough swing of instantaneous heart rate inside each
//    camera-timed breath cycle. NOOP's ResonanceEngine measures the same swing but has to assume the user
//    breathed exactly with the pacer; here the cycle boundaries are observed.
//  • The breath→heart-rate phase lag. Heart rate rises on the inhale and falls on the exhale; at rest it
//    lags by about half an inhale, and near the resonance pace (~0.1 Hz) it moves in phase with breathing
//    (0°) while blood pressure moves 180° against it (Vaschillo et al. 2002, Appl Psychophysiol Biofeedback
//    27:1–27; reviewed in Lehrer & Gevirtz 2014, Front Psychol 5:756, doi:10.3389/fpsyg.2014.00756).
//    So a lag drifting toward 0° is a second, amplitude-independent sign the pace is near resonance.
//
// ResonanceSeeker then nudges the pacer: it scores each candidate pace by the RSA measured over breaths
// the user ACTUALLY took at that pace (adherence checked by the camera), and walks toward the best one
// inside the 4.5–7 br/min adult resonance range (Lehrer et al. 2000, Appl Psychophysiol Biofeedback
// 25:177–191; Shaffer & Meehan 2020, Front Neurosci 14:570400). Resonance is not fixed between sessions
// (it moved in 66.7% of 21 participants re-tested; Capdevila et al. 2021, Sci Rep 11:8400,
// doi:10.1038/s41598-021-87867-8), which is why
// it is re-estimated live instead of only once.
//
// HONEST LIMITS: R-R from a ring or optical strap is PPG beat timing, and arrives over Bluetooth with a
// delivery delay this code cannot see; the LAG therefore carries that delay and is shown as an estimate.
// Nothing here is stored or fed to recovery/readiness.

data class BreathHeartCoupling(
    /** Mean peak-to-trough heart-rate swing (bpm) over the last scored breath cycles. */
    val rsaBpm: Double?,
    /** Positive: heart rate follows the breath by this much. */
    val lagMs: Long?,
    /** [lagMs] as an angle of the breath period, -180..180°. Near 0° ≈ resonance. */
    val phaseDeg: Double?,
    /** Peak normalised cross-correlation of breath and heart rate (0..1 when they move together). */
    val correlation: Double?,
    val cyclesScored: Int,
)

class BreathHeartCouplingTracker(private val windowMs: Long = 60_000) {
    private val breath = ArrayDeque<Pair<Long, Double>>()
    private val beats = ArrayDeque<Pair<Long, Double>>() // (beat time, instantaneous HR bpm)
    private val onsets = ArrayDeque<Long>()
    private var lastOnsetCount = -1

    fun reset() { breath.clear(); beats.clear(); onsets.clear(); lastOnsetCount = -1 }

    /** One breath sample per camera frame (use [BreathEstimate.smoothed]; null phase = UNKNOWN is skipped). */
    fun onBreath(tMs: Long, estimate: BreathEstimate) {
        if (estimate.phase == BreathPhase.UNKNOWN) return
        // Undo the estimator's own smoothing delay so the lag reflects the body, not the filter.
        breath.addLast(tMs - BreathTuning.SMOOTH_DELAY_MS to estimate.smoothed)
        if (lastOnsetCount >= 0 && estimate.inhaleOnsets > lastOnsetCount && estimate.phase == BreathPhase.INHALE) {
            onsets.addLast(estimate.phaseSinceMs) // already dated to the turnaround, filter delay removed
        }
        lastOnsetCount = estimate.inhaleOnsets
        trim(tMs)
    }

    /**
     * One accepted (artifact-gated) beat ending at [tMs]. Its instantaneous heart rate describes the whole
     * interval, so it is placed at the interval's midpoint.
     */
    fun onBeat(tMs: Long, rrMs: Int) {
        if (rrMs !in 300..2000) return
        val mid = tMs - rrMs / 2
        if (beats.isNotEmpty() && mid <= beats.last().first) return
        beats.addLast(mid to 60_000.0 / rrMs)
        trim(tMs)
    }

    private fun trim(nowMs: Long) {
        while (breath.isNotEmpty() && nowMs - breath.first().first > windowMs) breath.removeFirst()
        while (beats.isNotEmpty() && nowMs - beats.first().first > windowMs) beats.removeFirst()
        while (onsets.isNotEmpty() && nowMs - onsets.first() > windowMs) onsets.removeFirst()
    }

    fun evaluate(nowMs: Long, breathRateBpm: Double?): BreathHeartCoupling {
        // RSA per measured cycle.
        val swings = ArrayList<Double>()
        val on = onsets.toList()
        for (i in 0 until on.size - 1) {
            val a = on[i]; val b = on[i + 1]
            var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY; var n = 0
            for ((t, hr) in beats) if (t in a until b) { lo = min(lo, hr); hi = max(hi, hr); n++ }
            if (n >= 3) swings.add(hi - lo)
        }
        val recent = swings.takeLast(3)
        val rsa = if (recent.isEmpty()) null else recent.average()

        var lagMs: Long? = null
        var phase: Double? = null
        var corr: Double? = null
        if (breathRateBpm != null && breathRateBpm > 0 && breath.size >= 20 && beats.size >= 10) {
            val periodMs = 60_000.0 / breathRateBpm
            val start = max(breath.first().first, beats.first().first)
            val end = min(nowMs, min(breath.last().first, beats.last().first))
            if (end - start >= max(20_000.0, 2 * periodMs)) {
                val step = 250L
                val n = ((end - start) / step).toInt() + 1
                val b = DoubleArray(n) { interp(breath, start + it * step) }
                val h = DoubleArray(n) { interp(beats, start + it * step) }
                detrend(b); detrend(h)
                val maxLag = (min(periodMs / 2.0, 8_000.0) / step).toInt()
                var bestK = 0
                var bestR = Double.NEGATIVE_INFINITY
                for (k in -maxLag..maxLag) {
                    val r = xcorr(b, h, k)
                    if (r > bestR) { bestR = r; bestK = k }
                }
                lagMs = bestK * step
                var deg = 360.0 * lagMs / periodMs
                while (deg > 180) deg -= 360
                while (deg <= -180) deg += 360
                phase = deg
                corr = bestR
            }
        }
        return BreathHeartCoupling(rsa, lagMs, phase, corr, recent.size)
    }

    companion object {
        /** Linear interpolation over time-ordered (t, value) samples, clamped at the ends. */
        internal fun interp(series: ArrayDeque<Pair<Long, Double>>, t: Long): Double {
            if (t <= series.first().first) return series.first().second
            if (t >= series.last().first) return series.last().second
            var lo = 0; var hi = series.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (series[mid].first <= t) lo = mid else hi = mid
            }
            val (t0, v0) = series[lo]; val (t1, v1) = series[hi]
            return if (t1 == t0) v0 else v0 + (v1 - v0) * (t - t0).toDouble() / (t1 - t0)
        }

        internal fun detrend(x: DoubleArray) {
            val n = x.size
            val mt = (n - 1) / 2.0
            val my = x.average()
            var num = 0.0; var den = 0.0
            for (i in 0 until n) { num += (i - mt) * (x[i] - my); den += (i - mt) * (i - mt) }
            val slope = if (den > 0) num / den else 0.0
            for (i in 0 until n) x[i] -= my + slope * (i - mt)
        }

        /** Normalised correlation of b[i] with h[i + k] over their overlap. */
        internal fun xcorr(b: DoubleArray, h: DoubleArray, k: Int): Double {
            var sbh = 0.0; var sbb = 0.0; var shh = 0.0
            for (i in 0 until b.size) {
                val j = i + k
                if (j < 0 || j >= h.size) continue
                sbh += b[i] * h[j]; sbb += b[i] * b[i]; shh += h[j] * h[j]
            }
            val d = sqrt(sbb * shh)
            return if (d > 1e-12) sbh / d else 0.0
        }

        /** Beat end times for one R-R packet that arrived at [arrivalMs]: the last beat at arrival. */
        fun beatTimes(arrivalMs: Long, rrMs: List<Int>): List<Long> {
            val out = LongArray(rrMs.size)
            var t = arrivalMs
            for (i in rrMs.indices.reversed()) { out[i] = t; t -= rrMs[i] }
            return out.toList()
        }
    }
}

/**
 * Walks the pacer toward the pace that gives the largest RSA over breaths the user really took at it.
 * Paces live on a [stepBpm] grid inside [minBpm]..[maxBpm]. A measured cycle counts toward the current
 * pace only if the camera says its rate was within [adherenceBpm] of it.
 */
class ResonanceSeeker(
    startBpm: Double,
    private val minBpm: Double = 4.5,
    private val maxBpm: Double = 7.0,
    private val stepBpm: Double = 0.5,
    private val cyclesPerProbe: Int = 4,
    private val adherenceBpm: Double = 0.6,
    /** A neighbour must beat the incumbent by this fraction to win. */
    private val margin: Double = 0.05,
) {
    enum class State { PROBING, SETTLED }

    private val scores = HashMap<Int, MutableList<Double>>() // grid index -> RSA per adherent cycle
    private val phases = HashMap<Int, MutableList<Double>>()
    private var current: Int = snap(startBpm)
    private val tried = LinkedHashSet<Int>()
    private var adherent = 0
    private var total = 0

    var state: State = State.PROBING
        private set

    val targetBpm: Double get() = paceOf(current)

    /** Share of measured cycles that followed the pacer (null before any). */
    val adherence: Double? get() = if (total == 0) null else adherent.toDouble() / total

    fun bestBpm(): Double? = bestIndex()?.let { paceOf(it) }

    fun meanRsaAt(bpm: Double): Double? = scores[snap(bpm)]?.takeIf { it.isNotEmpty() }?.average()

    /** One completed, measured breath cycle at the current target. */
    fun onCycle(measuredRateBpm: Double, rsaBpm: Double?, phaseDeg: Double? = null) {
        total++
        if (abs(measuredRateBpm - targetBpm) > adherenceBpm || rsaBpm == null) return
        adherent++
        scores.getOrPut(current) { ArrayList() }.add(rsaBpm)
        if (phaseDeg != null) phases.getOrPut(current) { ArrayList() }.add(abs(phaseDeg))
        if (state == State.SETTLED) return
        if ((scores[current]?.size ?: 0) < cyclesPerProbe) return
        tried.add(current)
        // Choose the next pace to probe: an untried neighbour of the best pace so far.
        val best = bestIndex() ?: current
        val next = listOf(best - 1, best + 1).firstOrNull { it in gridRange() && it !in tried }
        if (next != null) {
            current = next
        } else {
            current = best
            state = State.SETTLED
        }
    }

    private fun bestIndex(): Int? {
        val scored = scores.filter { it.value.size >= cyclesPerProbe }
        if (scored.isEmpty()) return null
        val ranked = scored.entries.sortedByDescending { it.value.average() }
        val top = ranked.first()
        // Within the margin, prefer the pace whose breath→HR lag sits nearer 0° (closer to resonance).
        val near = ranked.filter { it.value.average() >= top.value.average() * (1 - margin) }
        if (near.size == 1) return top.key
        return near.minByOrNull { phases[it.key]?.average() ?: 180.0 }?.key ?: top.key
    }

    private fun gridRange(): IntRange = snap(minBpm)..snap(maxBpm)
    private fun snap(bpm: Double): Int = (bpm.coerceIn(minBpm, maxBpm) / stepBpm).roundToInt()
    private fun paceOf(i: Int): Double = i * stepBpm
}
