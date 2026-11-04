package com.noop.ble.gesture

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Experimental, platform-pure tap classifier for Oura live-ACM samples (~50 Hz, two XYZ samples per
 * 0x33 frame). Not a BLE decoder or a contact sensor. A time-boxed sensor session must be explicitly
 * armed and calibrated before any result may control an assistant.
 *
 * Method, derived from one owner's labelled double taps on a worn Oura Ring 4 (20 marked double taps
 * plus ~32 s of typing, 2026-10-21; one person, one ring, one session):
 *  - The signal is the per-sample jerk `e = |dx| + |dy| + |dz|` in raw counts (1 g ≈ 1024). A worn,
 *    idle ring sits near e ≈ 30; a tap rings for 100–200 ms with e peaking around 800–4000. Taps
 *    barely change |a| (most moved it by less than 380 counts), which is why the earlier
 *    gravity-magnitude threshold missed them.
 *  - Noise floor: the median of e over the previous [floorWindow] samples. The quiet level is
 *    `max(minQuietLevel, quietFloorMultiplier × floor)`, so busy hands (typing) raise it.
 *  - A burst opens when e reaches the quiet level after [quietBeforeSamples] quiet samples and closes
 *    after [quietAfterSamples] quiet samples. Activity without a quiet lead-in, or longer than
 *    [maxBurstSamples], is not a tap.
 *  - Impacts inside a burst are local maxima of e (radius [peakRadius]) that reach
 *    [minImpactPercentOfPeak]% of the burst peak, merged when closer than [minImpactSpacingSamples].
 *    The two taps of the recorded doubles were 140–320 ms apart (median 200 ms).
 *  - 2, 3 or 4 impacts, each gap at most [maxImpactSpacingSamples], within the length cap for that
 *    count, give DOUBLE, TRIPLE or QUADRUPLE.
 *  - Stillness gate (retuned 2026-10-21 from the guided recording: 16 cued doubles/triples, 74 s of
 *    phone handling and walking, plus the 20 marked doubles and 32 s of typing): a deliberate tap comes
 *    from a still hand, while handling the phone or walking moves the whole ring. The standard deviation
 *    of |a| over the [stillnessSamples] samples before a burst opens must be at most [maxStillness]
 *    counts (1 g ≈ 1024). Offline, on the arrival-order replay, this cut gestures in the handling and
 *    walking blocks from 14 to 1 while doubles went from 17 to 19 of 28 (the double length cap was
 *    raised from 640 to 800 ms in the same change) and triples stayed at 4 of 8.
 *
 * Everything is integer and per sample. Times are one monotonic clock (Android elapsedRealtime, ms);
 * the detector counts samples and uses the clock only for gaps, regressions and [flush].
 */
class OuraTapDetector(
    private val quietFloorMultiplier: Int = 3,
    private val minQuietLevel: Int = 150,
    private val minPeak: Int = 300,
    private val quietBeforeSamples: Int = 8,
    private val quietAfterSamples: Int = 12,
    private val peakRadius: Int = 4,
    private val minImpactPercentOfPeak: Int = 20,
    private val minImpactSpacingSamples: Int = 7,
    private val maxImpactSpacingSamples: Int = 17,
    private val maxDoubleSamples: Int = 40,
    private val maxBurstSamples: Int = 60,
    private val floorWindow: Int = 64,
    private val stillnessSamples: Int = 25,
    /** 0 turns the stillness gate off. */
    private val maxStillness: Int = 60,
    /**
     * Diagnostic only; never changes a decision. Called once per closed burst whose peak reached half of
     * [minPeak], so a missed tap can be explained from the log (too weak, one impact, too long, ...).
     */
    private val observer: ((Burst) -> Unit)? = null,
) {
    enum class Gesture { DOUBLE_TAP, TRIPLE_TAP, QUADRUPLE_TAP }

    /** What the detector decided about one burst. */
    enum class Verdict { GESTURE, TOO_WEAK, TOO_LONG, MOVING, IMPACT_COUNT, IMPACT_SPACING, BURST_LENGTH }

    /**
     * One burst of jerk between quiet stretches. [atMs]: sample clock when it closed. [peak]: largest
     * jerk (counts). [quietLevel]: the level it had to reach. [impacts]: impacts counted (0 when not
     * evaluated; 5 means "more than 4"). [firstSpacingMs]: spacing of the first two impacts at the nominal
     * 20 ms per sample, or -1. [durationMs]: nominal burst length. [gesture]: what was emitted, if anything.
     * [stillness]: standard deviation of |a| (counts) just before the burst opened; 0 until enough samples.
     */
    data class Burst(
        val atMs: Long,
        val peak: Int,
        val quietLevel: Int,
        val impacts: Int,
        val firstSpacingMs: Long,
        val durationMs: Long,
        val verdict: Verdict,
        val gesture: Gesture?,
        val stillness: Int = 0,
    )

    private val history = IntArray(floorWindow)
    private val sortScratch = IntArray(floorWindow)
    private var historyCount = 0
    private var historyNext = 0

    private val burst = IntArray(maxBurstSamples + quietAfterSamples)
    private val impactAt = IntArray(MAX_IMPACTS + 1)
    private var burstLength = 0
    private var inBurst = false
    private var tooLong = false
    private var below = 0
    private var quiet = 0
    private var quietLevel = minQuietLevel

    private val magnitudes = IntArray(stillnessSamples)
    private var magnitudeCount = 0
    private var magnitudeNext = 0
    private var stillness = 0

    private var hasPrevious = false
    private var px = 0
    private var py = 0
    private var pz = 0
    private var lastSampleMs = -1L

    init {
        require(quietFloorMultiplier > 0 && minQuietLevel > 0 && minPeak >= minQuietLevel)
        require(quietBeforeSamples > 0 && quietAfterSamples > 0 && peakRadius > 0)
        require(minImpactPercentOfPeak in 1..100)
        require(minImpactSpacingSamples > 0 && maxImpactSpacingSamples > minImpactSpacingSamples)
        require(maxDoubleSamples > 0 && maxBurstSamples >= maxDoubleSamples && floorWindow > 0)
        require(stillnessSamples > 1 && maxStillness >= 0)
    }

    /** Feed raw accelerometer XYZ *after* the BLE packet framing is validated. */
    fun sample(elapsedMs: Long, x: Int, y: Int, z: Int): Gesture? {
        if (elapsedMs < 0 || (lastSampleMs >= 0 && elapsedMs < lastSampleMs)) {
            reset()
            return null
        }
        if (lastSampleMs >= 0 && elapsedMs - lastSampleMs > MAX_GAP_MS) reset()
        lastSampleMs = elapsedMs
        val magnitude = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z).toInt()
        if (!hasPrevious) {
            hasPrevious = true
            px = x; py = y; pz = z
            rememberMagnitude(magnitude)
            return null
        }
        val e = abs(x - px) + abs(y - py) + abs(z - pz)
        px = x; py = y; pz = z
        var out: Gesture? = null
        if (!inBurst) {
            quietLevel = maxOf(minQuietLevel, quietFloorMultiplier * floor())
            if (e >= quietLevel) {
                if (quiet >= quietBeforeSamples) {
                    inBurst = true
                    tooLong = false
                    burstLength = 0
                    below = 0
                    push(e)
                    stillness = measureStillness()
                }
                quiet = 0
            } else {
                quiet++
            }
        } else {
            push(e)
            below = if (e < quietLevel) below + 1 else 0
            if (below >= quietAfterSamples) out = close(elapsedMs)
        }
        remember(e)
        rememberMagnitude(magnitude)
        return out
    }

    /**
     * Close an open burst once samples have stopped for longer than its quiet tail would take, so a tap
     * at the very end of a capture is not held back. Returns null when nothing is open.
     */
    fun flush(elapsedMs: Long): Gesture? {
        if (!inBurst || lastSampleMs < 0 || elapsedMs < lastSampleMs) return null
        if (elapsedMs - lastSampleMs < quietAfterSamples * NOMINAL_SAMPLE_MS) return null
        return close(elapsedMs)
    }

    /** Reset on disconnect, Bluetooth shutdown, capture expiry, or user disarm. */
    fun reset() {
        historyCount = 0
        historyNext = 0
        burstLength = 0
        inBurst = false
        tooLong = false
        below = 0
        quiet = 0
        quietLevel = minQuietLevel
        magnitudeCount = 0
        magnitudeNext = 0
        stillness = 0
        hasPrevious = false
        lastSampleMs = -1L
    }

    private fun push(e: Int) {
        if (burstLength < burst.size) burst[burstLength++] = e else tooLong = true
    }

    private fun remember(e: Int) {
        history[historyNext] = e
        historyNext = (historyNext + 1) % floorWindow
        if (historyCount < floorWindow) historyCount++
    }

    private fun rememberMagnitude(m: Int) {
        magnitudes[magnitudeNext] = m
        magnitudeNext = (magnitudeNext + 1) % stillnessSamples
        if (magnitudeCount < stillnessSamples) magnitudeCount++
    }

    /** Standard deviation of |a| over the last [stillnessSamples] samples; 0 until the window is full. */
    private fun measureStillness(): Int {
        if (magnitudeCount < stillnessSamples) return 0
        var s = 0L
        var s2 = 0L
        for (m in magnitudes) { s += m; s2 += m.toLong() * m }
        val n = stillnessSamples.toLong()
        val variance = (s2 * n - s * s) / (n * n)
        return sqrt(maxOf(0L, variance).toDouble()).toInt()
    }

    /** Median of the remembered jerk values (upper median for an even count); 0 before any. */
    private fun floor(): Int {
        if (historyCount == 0) return 0
        history.copyInto(sortScratch, 0, 0, historyCount)
        sortScratch.sort(0, historyCount)
        return sortScratch[historyCount / 2]
    }

    private fun close(atMs: Long): Gesture? {
        inBurst = false
        quiet = below
        val length = burstLength - minOf(below, burstLength)
        if (length <= 0) return null
        var peak = 0
        for (i in 0 until length) if (burst[i] > peak) peak = burst[i]
        val durationMs = length.toLong() * NOMINAL_SAMPLE_MS
        if (tooLong || length > maxBurstSamples) return report(atMs, peak, 0, -1, durationMs, Verdict.TOO_LONG, null)
        if (peak < minPeak) return report(atMs, peak, 0, -1, durationMs, Verdict.TOO_WEAK, null)
        if (maxStillness > 0 && stillness > maxStillness) {
            return report(atMs, peak, 0, -1, durationMs, Verdict.MOVING, null)
        }

        var count = 0
        for (i in 0 until length) {
            if (count > MAX_IMPACTS) break
            val v = burst[i]
            if (v * 100L < minImpactPercentOfPeak.toLong() * peak) continue
            var isPeak = true
            for (j in maxOf(0, i - peakRadius) until i) if (burst[j] >= v) { isPeak = false; break }
            if (isPeak) for (j in i + 1..minOf(length - 1, i + peakRadius)) if (burst[j] > v) { isPeak = false; break }
            if (!isPeak) continue
            if (count > 0 && i - impactAt[count - 1] < minImpactSpacingSamples) {
                if (v > burst[impactAt[count - 1]]) impactAt[count - 1] = i
            } else {
                impactAt[count++] = i
            }
        }
        val firstSpacing = if (count >= 2) (impactAt[1] - impactAt[0]).toLong() * NOMINAL_SAMPLE_MS else -1L
        if (count < 2 || count > MAX_IMPACTS) {
            return report(atMs, peak, count, firstSpacing, durationMs, Verdict.IMPACT_COUNT, null)
        }
        for (k in 1 until count) {
            if (impactAt[k] - impactAt[k - 1] > maxImpactSpacingSamples) {
                return report(atMs, peak, count, firstSpacing, durationMs, Verdict.IMPACT_SPACING, null)
            }
        }
        val gesture = when (count) {
            2 -> Gesture.DOUBLE_TAP.takeIf { length <= maxDoubleSamples }
            3 -> Gesture.TRIPLE_TAP.takeIf { length <= maxDoubleSamples * 3 / 2 }
            else -> Gesture.QUADRUPLE_TAP.takeIf { length <= maxDoubleSamples * 2 }
        }
        val verdict = if (gesture == null) Verdict.BURST_LENGTH else Verdict.GESTURE
        return report(atMs, peak, count, firstSpacing, durationMs, verdict, gesture)
    }

    private fun report(
        atMs: Long, peak: Int, impacts: Int, firstSpacingMs: Long, durationMs: Long,
        verdict: Verdict, gesture: Gesture?,
    ): Gesture? {
        val obs = observer
        if (obs != null && peak * 2 >= minPeak) {
            obs(Burst(atMs, peak, quietLevel, impacts, firstSpacingMs, durationMs, verdict, gesture, stillness))
        }
        return gesture
    }

    private companion object {
        const val NOMINAL_SAMPLE_MS = 20L
        const val MAX_GAP_MS = 2_000L
        const val MAX_IMPACTS = 4
    }
}
