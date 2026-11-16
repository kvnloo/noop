package com.noop.breathcam

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import java.util.Random

/** Synthetic inputs shared by the breath-camera tests: breath waveforms with truth, and torso frames. */
internal object BreathSynth {

    data class Segment(val phase: BreathPhase, val durMs: Long)

    /** One breath pattern repeated: e.g. inhale 3 s, exhale 3 s. */
    fun pattern(vararg seg: Pair<BreathPhase, Long>): List<Segment> = seg.map { Segment(it.first, it.second) }

    fun paced(bpm: Double, inhaleFraction: Double = 0.5): List<Segment> {
        val cycle = (60_000.0 / bpm).toLong()
        val inh = (cycle * inhaleFraction).toLong()
        return pattern(BreathPhase.INHALE to inh, BreathPhase.EXHALE to cycle - inh)
    }

    data class Trace(val tMs: LongArray, val position: DoubleArray, val truth: Array<BreathPhase>)

    /**
     * Torso position (up = +, peak-to-peak [amplitude]) following the pattern for [durationMs] at
     * [fps]. Inhale/exhale are raised-cosine half-waves; holds keep the position flat.
     */
    fun trace(
        pattern: List<Segment>,
        durationMs: Long,
        fps: Double = 15.0,
        amplitude: Double = 1.0,
        noiseSd: Double = 0.0,
        driftPerMin: Double = 0.0,
        seed: Long = 7,
    ): Trace {
        val rnd = Random(seed)
        val n = (durationMs / 1000.0 * fps).toInt()
        val t = LongArray(n)
        val y = DoubleArray(n)
        val truth = Array(n) { BreathPhase.UNKNOWN }
        val cycle = pattern.sumOf { it.durMs }
        for (i in 0 until n) {
            val ms = (i * 1000.0 / fps).toLong()
            t[i] = ms
            var r = ms % cycle
            var level = 0.0 // 0 = empty, 1 = full
            var ph = BreathPhase.UNKNOWN
            // Position at the start of each segment follows from the previous segments.
            var start = 0.0
            for (s in pattern) {
                val end = when (s.phase) { BreathPhase.INHALE -> 1.0; BreathPhase.EXHALE -> 0.0; else -> start }
                if (r < s.durMs) {
                    val f = r.toDouble() / s.durMs
                    level = start + (end - start) * (1 - cos(PI * f)) / 2
                    ph = s.phase
                    break
                }
                r -= s.durMs
                start = end
            }
            truth[i] = ph
            y[i] = amplitude * level + driftPerMin * ms / 60_000.0 + noiseSd * rnd.nextGaussian()
        }
        return Trace(t, y, truth)
    }

    /** Fraction of samples whose estimate matches truth, ignoring warm-up and [graceMs] after each change. */
    fun phaseAccuracy(trace: Trace, est: List<BreathPhase>, warmupMs: Long = 12_000, graceMs: Long = 700): Double {
        var ok = 0
        var n = 0
        var lastChange = 0L
        for (i in trace.tMs.indices) {
            if (i > 0 && trace.truth[i] != trace.truth[i - 1]) lastChange = trace.tMs[i]
            if (trace.tMs[i] < warmupMs || trace.tMs[i] - lastChange < graceMs) continue
            n++
            if (est[i] == trace.truth[i]) ok++
        }
        return ok.toDouble() / n
    }

    fun run(trace: Trace, polarity: Int = 1): List<BreathEstimate> {
        val e = BreathPhaseEstimator().also { it.polarity = polarity }
        return trace.tMs.indices.map { e.offer(trace.tMs[it], trace.position[it]) }
    }

    // --- Frames ------------------------------------------------------------------------------------

    /** Row texture of a shirt/shoulder scene, analytic so a sub-pixel shift is exact. */
    fun rowTexture(v: Double): Double =
        118 + 30 * sin(2 * PI * v / 23.0 + 0.7) + 18 * sin(2 * PI * v / 9.7 + 1.9) +
            10 * sin(2 * PI * v / 57.0) + 45 * tanh((v - 150.0) / 3.0)

    fun columnTexture(u: Int): Double = 8 * sin(2 * PI * u / 31.0)

    /**
     * An upright [w]×[h] scene whose content has moved UP by [upPx], written into a sensor buffer that
     * needs [rotation]° clockwise to be upright. Buffers are built with explicit rotation formulas, not
     * with [UprightMapping], so the tests check the mapping rather than reuse it.
     */
    fun frame(w: Int, h: Int, upPx: Double, rotation: Int, noiseSd: Double = 0.0, rnd: Random = Random(1)): LumaFrame {
        val bw = if (rotation % 180 == 0) w else h
        val bh = if (rotation % 180 == 0) h else w
        val rows = DoubleArray(h) { rowTexture(it + upPx) }
        val buf = ByteArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) {
            val (u, v) = when (rotation) {
                0 -> x to y
                90 -> (w - 1 - y) to x
                180 -> (w - 1 - x) to (h - 1 - y)
                270 -> y to (h - 1 - x)
                else -> error("rotation")
            }
            val value = rows[v] + columnTexture(u) + if (noiseSd > 0) noiseSd * rnd.nextGaussian() else 0.0
            buf[y * bw + x] = value.toInt().coerceIn(0, 255).toByte()
        }
        return LumaFrame(buf, bw, bh)
    }

    /** Beats whose instantaneous HR follows [hr] (bpm as a function of ms): (end time, R-R ms). */
    fun beats(durationMs: Long, hr: (Double) -> Double): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        var t = 0.0
        while (t < durationMs) {
            // The interval's own heart rate is the rate at its midpoint (a short fixed-point solve).
            var rr = 60_000.0 / hr(t)
            repeat(4) { rr = 60_000.0 / hr(t + rr / 2) }
            t += rr
            out.add(t.toLong() to rr.toInt())
        }
        return out
    }
}
