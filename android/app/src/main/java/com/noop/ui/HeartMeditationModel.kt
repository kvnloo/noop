package com.noop.ui

import com.noop.analytics.HrvAnalyzer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// MARK: - Heart meditation model (pure, JVM-tested)
//
// The display model behind the Live console's heart meditation view: a calm, beat-synced rendering of
// the live R-R stream. Everything here is pure and clock-injected (callers pass `nowMs`), so it is
// covered by JVM tests with no strap, no Compose and no frame clock.
//
// It is PRESENTATION, not analytics: nothing here is stored, exported or fed to recovery/readiness, and
// every number it produces is labelled on screen as an estimate. Live R-R from a ring or a WHOOP 5/MG is
// optical (PPG) beat timing, so it only means something while the wearer is still.
//
// Approaches borrowed (re-implemented here, no code copied):
//  • Artifact gate — the same 300–2000 ms range and 20% threshold NOOP's HrvAnalyzer applies offline, in
//    Malik's original causal form: a beat that differs from the previous ACCEPTED beat by more than 20%
//    is an artifact. (HrvAnalyzer's centred local median needs future beats; a trailing median lags a
//    deep breathing wave and clipped real ±8 bpm troughs in testing.) A rhythm must first seed from
//    three agreeing beats. OpenHRV (JanCBrammer/OpenHRV, LGPL-2.1) likewise gates live IBIs before use.
//  • Wave swing — OpenHRV's "local HRV": the peak-to-trough distance between successive turning points
//    of the beat series, EWMA-smoothed (Brammer et al. 2019, Sci Rep 9:7934, doi:10.1038/s41598-019-44201-7,
//    Fig. 2). Here it is measured on instantaneous heart rate (60000/RR, bpm), as NOOP's ResonanceEngine
//    measures RSA amplitude.
//  • Rhythm focus — the share of heart-rate-wave power that sits in one narrow band around the dominant
//    0.04–0.26 Hz peak, the shape of the published HeartMath coherence measure (McCraty et al. 2009,
//    "The Coherent Heart", Integral Review 5(2)). Reported as a plain percentage, NOT as a HeartMath score.
//  • Pacer — a smooth cosine inhale/exhale cycle like OpenHRV's pacer disk, at the user's locked
//    resonance pace when they have one (BiofeedbackPrefs) and Breathe's 5.5 br/min coherence pace otherwise.

/** Tunables, named so the tests and the screen read one set of numbers. */
internal object HeartMeditationTuning {
    /** Physiological range for a live beat — the same bounds HrvAnalyzer uses offline. */
    const val RR_MIN_MS: Int = 300
    const val RR_MAX_MS: Int = 2000

    /** A beat that differs from the previous accepted beat by more than this fraction is an artifact. */
    const val DEVIATION: Double = HrvAnalyzer.ECTOPIC_THRESHOLD

    /** Consecutive in-range beats that must agree with each other before the gate trusts a rhythm. */
    const val SEED_BEATS: Int = 3

    /** After this many rejections in a row the reference is dropped and the gate re-seeds, so a real
     *  change of pace (standing up, a deep sigh) cannot lock the gate out forever. */
    const val RESEED_AFTER_REJECTS: Int = 4

    /** Decisions kept for the steadiness verdict. */
    const val STEADINESS_WINDOW: Int = 20

    /** Accepted beats in the window before the view says anything but "waiting". */
    const val MIN_ACCEPTED_FOR_STEADY: Int = 8

    /** Accepted fraction at or above which the beats count as steady. */
    const val STEADY_FRACTION: Double = 0.8

    /** With no accepted beat for this long the rhythm is treated as lost. */
    const val STALE_MS: Long = 5_000

    /** Beats waiting in the playout queue beyond this are dropped (the view catches up, never lags). */
    const val MAX_QUEUED_BEATS: Int = 4

    /** Rest fill of the vessel, and how far a full wave swings it either way. */
    const val LEVEL_REST: Double = 0.52
    const val LEVEL_SWING: Double = 0.30

    /** The smallest half-swing (bpm) used for scaling, so a flat trace stays calm rather than amplified. */
    const val MIN_HALF_SWING_BPM: Double = 3.0

    /** Slow baseline EWMA weight per beat (~20-beat memory, about two slow breaths). */
    const val BASELINE_WEIGHT: Double = 0.05

    /** A turning point must be left by this much (bpm) before it counts. */
    const val TURN_HYSTERESIS_BPM: Double = 0.6

    /** Wave-swing EWMA weight per turning point. */
    const val SWING_WEIGHT: Double = 0.2

    /** Rhythm focus needs this much clean beat time. */
    const val FOCUS_WINDOW_SEC: Double = 64.0
    const val FOCUS_SAMPLE_HZ: Double = 2.0
}

// MARK: - Artifact gate

/** What the gate decided about one interval. */
internal enum class BeatVerdict {
    /** A clean beat — pulse it and use it. */
    ACCEPTED,

    /** Outside 300–2000 ms: a sentinel, a dropped beat or noise. */
    OUT_OF_RANGE,

    /** In range but more than 20% from the recent rhythm: ectopic or a mis-detected beat. */
    DEVIANT,

    /** In range, but there is no trusted rhythm yet to compare against. */
    SEEDING,
}

/**
 * A causal artifact gate for live R-R. Until [HeartMeditationTuning.SEED_BEATS] consecutive in-range
 * beats agree within 20% of their own median it accepts nothing (it would rather wait than invent a
 * rhythm); after that each beat is judged against the previous accepted beat.
 */
internal class BeatGate {
    private var reference: Int? = null
    private val seed = ArrayDeque<Int>()
    private var rejectsInRow = 0

    val seeded: Boolean get() = reference != null

    fun reset() {
        reference = null
        seed.clear()
        rejectsInRow = 0
    }

    fun offer(rrMs: Int): BeatVerdict {
        if (rrMs < HeartMeditationTuning.RR_MIN_MS || rrMs > HeartMeditationTuning.RR_MAX_MS) {
            noteReject()
            seed.clear()
            return BeatVerdict.OUT_OF_RANGE
        }
        val ref = reference
        if (ref == null) {
            seed.addLast(rrMs)
            while (seed.size > HeartMeditationTuning.SEED_BEATS) seed.removeFirst()
            if (seed.size == HeartMeditationTuning.SEED_BEATS && agrees(seed.toList())) {
                reference = rrMs
                seed.clear()
                rejectsInRow = 0
                return BeatVerdict.ACCEPTED
            }
            return BeatVerdict.SEEDING
        }
        return if (abs(rrMs - ref).toDouble() / ref <= HeartMeditationTuning.DEVIATION) {
            reference = rrMs
            rejectsInRow = 0
            BeatVerdict.ACCEPTED
        } else {
            noteReject()
            BeatVerdict.DEVIANT
        }
    }

    private fun noteReject() {
        rejectsInRow += 1
        if (rejectsInRow >= HeartMeditationTuning.RESEED_AFTER_REJECTS && reference != null) {
            reference = null
            seed.clear()
        }
    }

    private fun agrees(beats: List<Int>): Boolean {
        val med = median(beats)
        return beats.all { abs(it - med) / med <= HeartMeditationTuning.DEVIATION }
    }
}

internal fun median(values: List<Int>): Double {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2].toDouble() else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

// MARK: - Steadiness

/** The one verdict the view's status line reads. */
internal enum class BeatSteadiness {
    /** Not enough clean beats yet (or the rhythm was lost): show "waiting for steady beats". */
    WAITING,

    /** Beats are arriving but too many are artifacts to trust the wave. */
    NOISY,

    /** Mostly clean beats. */
    STEADY,
}

internal fun steadiness(recent: List<BeatVerdict>): BeatSteadiness {
    val accepted = recent.count { it == BeatVerdict.ACCEPTED }
    if (accepted < HeartMeditationTuning.MIN_ACCEPTED_FOR_STEADY) return BeatSteadiness.WAITING
    val judged = recent.count { it != BeatVerdict.SEEDING }
    val fraction = if (judged == 0) 0.0 else accepted.toDouble() / judged
    return if (fraction >= HeartMeditationTuning.STEADY_FRACTION) BeatSteadiness.STEADY else BeatSteadiness.NOISY
}

// MARK: - Beat playout

/**
 * Plays accepted beats out at their own R-R spacing, so the vessel pulses in the heart's real rhythm
 * even when BLE hands over two beats in one packet. A beat is due when its interval has elapsed since
 * the previous pulse (or at once if it arrived late); a queue that falls more than
 * [HeartMeditationTuning.MAX_QUEUED_BEATS] behind drops its oldest beats instead of lagging.
 */
internal class BeatPlayout {
    private val queue = ArrayDeque<Int>()
    private var lastPulseMs: Long? = null

    val queued: Int get() = queue.size

    fun enqueue(rrMs: Int) {
        queue.addLast(rrMs)
        while (queue.size > HeartMeditationTuning.MAX_QUEUED_BEATS) queue.removeFirst()
    }

    fun reset() {
        queue.clear()
        lastPulseMs = null
    }

    /** The interval of the beat that is due at [nowMs], or null. At most one beat per call. */
    fun poll(nowMs: Long): Int? {
        val next = queue.firstOrNull() ?: return null
        val last = lastPulseMs
        if (last == null || nowMs - last >= next) {
            queue.removeFirst()
            // Anchor to the beat's due time when we are only slightly late, so the spacing stays the
            // heart's own; re-anchor to now after a gap (a late packet) so we never burst to catch up.
            lastPulseMs = if (last != null && nowMs - last < next * 2L) last + next else nowMs
            return next
        }
        return null
    }
}

// MARK: - Rhythm (wave swing + vessel level)

/**
 * Follows the clean beat series as an instantaneous-heart-rate wave and maps it to a vessel fill: the
 * fill rises as the heart speeds up (on an inhale, through respiratory sinus arrhythmia) and falls as
 * it slows. Between pulses the level glides from the previous beat's point to the latest one over the
 * latest beat's own interval, so motion is continuous and tied to beat time.
 */
internal class RhythmTracker {
    private var baselineHr: Double? = null
    private val lastThree = ArrayDeque<Double>()

    private var lastDirection = 0
    private var candidate = 0.0
    private var runLow: Double? = null
    private var runHigh: Double? = null
    private var lastExtreme: Double? = null

    /** EWMA peak-to-trough swing of instantaneous HR, bpm. Null until two turning points. */
    var swingBpm: Double? = null
        private set

    private var fromLevel = HeartMeditationTuning.LEVEL_REST
    private var toLevel = HeartMeditationTuning.LEVEL_REST
    private var segmentStartMs = 0L
    private var segmentMs = 1L

    /** The recent smoothed HR series for the thread drawing (bpm). */
    val wave = ArrayDeque<Double>()

    /** Forget the beat series. The level glide is kept unless [toRest], so a lost rhythm can ease out. */
    fun reset(toRest: Boolean = true) {
        baselineHr = null
        lastThree.clear()
        lastDirection = 0
        candidate = 0.0
        runLow = null
        runHigh = null
        lastExtreme = null
        swingBpm = null
        wave.clear()
        if (!toRest) return
        fromLevel = HeartMeditationTuning.LEVEL_REST
        toLevel = HeartMeditationTuning.LEVEL_REST
        segmentStartMs = 0L
        segmentMs = 1L
    }

    /** Record one pulsed beat at [nowMs]. */
    fun onBeat(rrMs: Int, nowMs: Long) {
        val hr = 60_000.0 / rrMs
        lastThree.addLast(hr)
        while (lastThree.size > 3) lastThree.removeFirst()
        val smooth = lastThree.average()
        val base = baselineHr?.let { it + HeartMeditationTuning.BASELINE_WEIGHT * (smooth - it) } ?: smooth
        baselineHr = base
        trackTurningPoint(smooth)

        wave.addLast(smooth)
        while (wave.size > 40) wave.removeFirst()

        val half = max(HeartMeditationTuning.MIN_HALF_SWING_BPM, (swingBpm ?: 0.0) / 2.0)
        val unit = ((smooth - base) / half).coerceIn(-1.0, 1.0)
        fromLevel = levelAt(nowMs)
        toLevel = HeartMeditationTuning.LEVEL_REST + HeartMeditationTuning.LEVEL_SWING * unit
        segmentStartMs = nowMs
        segmentMs = rrMs.toLong().coerceAtLeast(1L)
    }

    private fun trackTurningPoint(hr: Double) {
        // Turning points with hysteresis: a peak (or trough) is confirmed only once the series has moved
        // [HeartMeditationTuning.TURN_HYSTERESIS_BPM] away from it, so beat-sampling and 1 ms rounding
        // blips near a crest are not counted as tiny extra swings that drag the average down.
        val h = HeartMeditationTuning.TURN_HYSTERESIS_BPM
        when (lastDirection) {
            0 -> {
                val lo = min(runLow ?: hr, hr)
                val hi = max(runHigh ?: hr, hr)
                runLow = lo
                runHigh = hi
                if (hr - lo > h) { lastDirection = 1; candidate = hr }
                else if (hi - hr > h) { lastDirection = -1; candidate = hr }
            }
            1 -> if (hr >= candidate) candidate = hr else if (candidate - hr > h) confirmTurn(-1, hr)
            else -> if (hr <= candidate) candidate = hr else if (hr - candidate > h) confirmTurn(1, hr)
        }
    }

    private fun confirmTurn(newDirection: Int, hr: Double) {
        val extreme = candidate
        lastExtreme?.let { prev ->
            val swing = abs(extreme - prev)
            swingBpm = swingBpm?.let { it + HeartMeditationTuning.SWING_WEIGHT * (swing - it) } ?: swing
        }
        lastExtreme = extreme
        lastDirection = newDirection
        candidate = hr
    }

    /** Vessel fill (0..1) at [nowMs]: an eased glide across the latest beat's interval. */
    fun levelAt(nowMs: Long): Double {
        val t = ((nowMs - segmentStartMs).toDouble() / segmentMs).coerceIn(0.0, 1.0)
        val eased = 0.5 - 0.5 * cos(PI * t)
        return fromLevel + (toLevel - fromLevel) * eased
    }

    /** Glide back to the rest level (the rhythm was lost). */
    fun settle(nowMs: Long, overMs: Long = 1_500) {
        fromLevel = levelAt(nowMs)
        toLevel = HeartMeditationTuning.LEVEL_REST
        segmentStartMs = nowMs
        segmentMs = overMs
    }
}

// MARK: - Rhythm focus (coherence-shaped estimate)

/** The share of HR-wave power near one steady rhythm ([focus], 0..1) and that rhythm in cycles/min. */
internal data class RhythmFocus(val focus: Double, val perMinute: Double)

/**
 * Rhythm focus over the last [HeartMeditationTuning.FOCUS_WINDOW_SEC] of clean beats, or null when there
 * is not that much clean beat time. The beat series is placed on its own time axis (cumulative R-R),
 * resampled to an even grid as instantaneous HR, detrended and Hann-windowed; power is read with a
 * direct DFT at the grid's natural resolution (1/64 Hz). Focus = power in the dominant 0.04–0.26 Hz bin
 * and its two neighbours, over total power 0.0033–0.4 Hz.
 */
internal fun rhythmFocus(cleanRrMs: List<Int>): RhythmFocus? {
    val window = HeartMeditationTuning.FOCUS_WINDOW_SEC
    val hz = HeartMeditationTuning.FOCUS_SAMPLE_HZ
    if (cleanRrMs.size < 8) return null
    // Walk back from the newest beat until the window is covered.
    var covered = 0.0
    var start = cleanRrMs.size
    while (start > 0 && covered < window * 1000.0) {
        start -= 1
        covered += cleanRrMs[start]
    }
    if (covered < window * 1000.0) return null
    val beats = cleanRrMs.subList(start, cleanRrMs.size)
    // Beat times (s) at the END of each interval, relative to the window start.
    val times = DoubleArray(beats.size)
    var acc = 0.0
    for (i in beats.indices) {
        acc += beats[i] / 1000.0
        times[i] = acc
    }
    val hr = DoubleArray(beats.size) { 60_000.0 / beats[it] }
    val endT = times.last()
    val n = (window * hz).roundToInt()
    val samples = DoubleArray(n)
    var j = 0
    for (k in 0 until n) {
        val t = endT - window + k / hz
        while (j < times.size - 2 && times[j + 1] < t) j += 1
        val t0 = times[j]
        val t1 = times[min(j + 1, times.size - 1)]
        val v0 = hr[j]
        val v1 = hr[min(j + 1, hr.size - 1)]
        samples[k] = if (t1 > t0) v0 + (v1 - v0) * ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0) else v0
    }
    val mean = samples.average()
    for (k in 0 until n) {
        val hann = 0.5 - 0.5 * cos(2.0 * PI * k / (n - 1))
        samples[k] = (samples[k] - mean) * hann
    }
    val df = hz / n
    val lowBin = max(1, (0.0033 / df).roundToInt())
    val highBin = (0.4 / df).toInt()
    val power = DoubleArray(highBin + 2)
    for (b in lowBin..highBin + 1) {
        var re = 0.0
        var im = 0.0
        for (k in 0 until n) {
            val a = 2.0 * PI * b * k / n
            re += samples[k] * cos(a)
            im -= samples[k] * sin(a)
        }
        power[b] = re * re + im * im
    }
    val total = (lowBin..highBin).sumOf { power[it] }
    if (total <= 1e-9) return null
    // First bin at or above 0.04 Hz, so the ±1-bin peak window never reaches down into the VLF drift.
    val peakLo = kotlin.math.ceil(0.04 / df).toInt().coerceAtLeast(lowBin + 1)
    val peakHi = (0.26 / df).toInt()
    var peak = peakLo
    for (b in peakLo..peakHi) if (power[b] > power[peak]) peak = b
    val peakPower = power[peak - 1] + power[peak] + power[peak + 1]
    return RhythmFocus(focus = (peakPower / total).coerceIn(0.0, 1.0), perMinute = peak * df * 60.0)
}

// MARK: - Breathing pacer

/**
 * The pacer's expansion (0 = empty lungs, 1 = full) at [elapsedMs] into a session at [breathsPerMin]:
 * a cosine rise across the inhale share of the cycle and a cosine fall across the rest.
 */
internal fun pacerLevel(elapsedMs: Long, breathsPerMin: Double, inhaleFraction: Double = 0.5): Double {
    val cycleMs = 60_000.0 / breathsPerMin.coerceIn(3.0, 12.0)
    val frac = inhaleFraction.coerceIn(0.1, 0.9)
    val pos = ((elapsedMs.toDouble() % cycleMs) + cycleMs) % cycleMs / cycleMs
    return if (pos < frac) {
        0.5 - 0.5 * cos(PI * pos / frac)
    } else {
        0.5 + 0.5 * cos(PI * (pos - frac) / (1.0 - frac))
    }
}

/** True while the pacer is in its inhale share at [elapsedMs]. */
internal fun pacerInhaling(elapsedMs: Long, breathsPerMin: Double, inhaleFraction: Double = 0.5): Boolean {
    val cycleMs = 60_000.0 / breathsPerMin.coerceIn(3.0, 12.0)
    val pos = ((elapsedMs.toDouble() % cycleMs) + cycleMs) % cycleMs / cycleMs
    return pos < inhaleFraction.coerceIn(0.1, 0.9)
}

/** Milliseconds from [elapsedMs] to the pacer's next inhale/exhale boundary (for still mode's timer). */
internal fun pacerMsToNextPhase(elapsedMs: Long, breathsPerMin: Double, inhaleFraction: Double = 0.5): Long {
    val cycleMs = 60_000.0 / breathsPerMin.coerceIn(3.0, 12.0)
    val frac = inhaleFraction.coerceIn(0.1, 0.9)
    val inCycle = ((elapsedMs.toDouble() % cycleMs) + cycleMs) % cycleMs
    val boundary = if (inCycle < cycleMs * frac) cycleMs * frac else cycleMs
    return max(1L, (boundary - inCycle).roundToInt().toLong())
}

// MARK: - Session (ties the pieces together)

/**
 * One meditation session: feed it packets as they arrive, poll it once per frame. Owns the gate, the
 * playout queue, the rhythm tracker and the clean series for rhythm focus.
 */
internal class HeartMeditationSession {
    val gate = BeatGate()
    private val playout = BeatPlayout()
    val rhythm = RhythmTracker()
    private val verdicts = ArrayDeque<BeatVerdict>()
    private val clean = ArrayDeque<Int>()
    private var lastAcceptedMs: Long? = null

    /** Pulses played so far; the vessel splashes whenever this changes. */
    var pulses: Int = 0
        private set

    var focus: RhythmFocus? = null
        private set

    val steadiness: BeatSteadiness get() = steadiness(verdicts.toList())

    fun onPacket(intervals: List<Int>, nowMs: Long) {
        for (rr in intervals) {
            if (rr <= 0) continue   // non-positive sentinels never reach the gate (LiveState drops them too)
            val v = gate.offer(rr)
            verdicts.addLast(v)
            while (verdicts.size > HeartMeditationTuning.STEADINESS_WINDOW) verdicts.removeFirst()
            if (v == BeatVerdict.ACCEPTED) {
                lastAcceptedMs = nowMs
                playout.enqueue(rr)
                clean.addLast(rr)
                while (clean.size > 200) clean.removeFirst()
            }
        }
        focus = if (steadiness == BeatSteadiness.STEADY) rhythmFocus(clean.toList()) else null
    }

    /** Advance to [nowMs]; returns true when a beat pulsed on this call. */
    fun tick(nowMs: Long): Boolean {
        val last = lastAcceptedMs
        if (last != null && nowMs - last > HeartMeditationTuning.STALE_MS) {
            // The rhythm is lost (ring off, motion, link drop): forget it and glide back to rest
            // rather than keep animating a rhythm that is no longer there.
            lastAcceptedMs = null
            gate.reset()
            playout.reset()
            verdicts.clear()
            clean.clear()
            focus = null
            rhythm.reset(toRest = false)
            rhythm.settle(nowMs)
            return false
        }
        val rr = playout.poll(nowMs) ?: return false
        rhythm.onBeat(rr, nowMs)
        if (steadiness == BeatSteadiness.WAITING) {
            // Not trusted yet: let the tracker warm its baseline, but neither pulse nor move the vessel,
            // so the picture never disagrees with the "waiting for steady beats" line above it.
            rhythm.settle(nowMs)
            return false
        }
        pulses += 1
        return true
    }

    /** Heart rate from the last few CLEAN beats (bpm), or null while waiting. The view shows this rather
     *  than the raw live HR so the number and the vessel come from one gated funnel. */
    val cleanBpm: Int?
        get() {
            if (steadiness == BeatSteadiness.WAITING || clean.isEmpty()) return null
            val recent = clean.toList().takeLast(5)
            return (60_000.0 / recent.average()).roundToInt()
        }

    fun levelAt(nowMs: Long): Double = rhythm.levelAt(nowMs)
}
