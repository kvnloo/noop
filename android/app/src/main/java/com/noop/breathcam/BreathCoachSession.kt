package com.noop.breathcam

import com.noop.analytics.HrvAnalyzer
import kotlin.math.abs

/**
 * What the meditation view shows from the camera + heart fusion. Every number is an estimate and the
 * view labels it so; [phase] UNKNOWN means "don't draw a breath claim".
 */
data class BreathCoachState(
    val phase: BreathPhase = BreathPhase.UNKNOWN,
    val holdKind: HoldKind = HoldKind.NONE,
    val level: Double = 0.0,
    val rateBpm: Double? = null,
    val confidence: Double = 0.0,
    val lastInhaleMs: Long? = null,
    val lastExhaleMs: Long? = null,
    val rsaBpm: Double? = null,
    val phaseDeg: Double? = null,
    val lagMs: Long? = null,
    /** Share of measured breaths that followed the pacer (null until one completes). */
    val adherence: Double? = null,
    /** The pace the adaptive pacer wants next (null when adapting is off). */
    val suggestedPaceBpm: Double? = null,
    val seeking: ResonanceSeeker.State? = null,
)

/**
 * Glue for one camera breath session: estimates from [BreathCameraPipeline], R-R packets from the live
 * stream, a causal beat gate, the coupling tracker and (optionally) the resonance seeker. Pure and
 * clock-injected — the caller passes elapsed-realtime milliseconds for both streams.
 */
class BreathCoachSession(
    startPaceBpm: Double,
    private val adaptPace: Boolean,
) {
    private val coupling = BreathHeartCouplingTracker()
    val seeker: ResonanceSeeker? = if (adaptPace) ResonanceSeeker(startPaceBpm) else null
    private val accepted = ArrayDeque<Int>()
    private var lastOnsetCount = 0
    private var lastOnsetMs = Long.MIN_VALUE

    var state: BreathCoachState = BreathCoachState(suggestedPaceBpm = seeker?.targetBpm, seeking = seeker?.state)
        private set

    /** One packet of R-R intervals that arrived at [arrivalMs]. Out-of-rhythm beats are dropped. */
    fun onRrPacket(arrivalMs: Long, rrMs: List<Int>) {
        val times = BreathHeartCouplingTracker.beatTimes(arrivalMs, rrMs)
        for (i in rrMs.indices) {
            val rr = rrMs[i]
            if (rr !in 300..2000) continue
            if (accepted.size >= 3) {
                val med = accepted.sorted()[accepted.size / 2]
                if (abs(rr - med).toDouble() / med > ECTOPIC_FRACTION) continue
            }
            accepted.addLast(rr)
            while (accepted.size > 5) accepted.removeFirst()
            coupling.onBeat(times[i], rr)
        }
    }

    fun onEstimate(tMs: Long, estimate: BreathEstimate): BreathCoachState {
        coupling.onBreath(tMs, estimate)
        val c = coupling.evaluate(tMs, estimate.rateBpm)
        if (estimate.inhaleOnsets > lastOnsetCount && estimate.phase == BreathPhase.INHALE) {
            val onset = estimate.phaseSinceMs
            if (lastOnsetMs != Long.MIN_VALUE && onset > lastOnsetMs) {
                val cycleRate = 60_000.0 / (onset - lastOnsetMs)
                seeker?.onCycle(cycleRate, c.rsaBpm, c.phaseDeg)
            }
            lastOnsetMs = onset
            lastOnsetCount = estimate.inhaleOnsets
        }
        if (estimate.phase == BreathPhase.UNKNOWN) lastOnsetMs = Long.MIN_VALUE
        state = BreathCoachState(
            phase = estimate.phase,
            holdKind = estimate.holdKind,
            level = estimate.level,
            rateBpm = estimate.rateBpm,
            confidence = estimate.confidence,
            lastInhaleMs = estimate.lastInhaleMs,
            lastExhaleMs = estimate.lastExhaleMs,
            rsaBpm = c.rsaBpm,
            phaseDeg = c.phaseDeg,
            lagMs = c.lagMs,
            adherence = seeker?.adherence,
            suggestedPaceBpm = seeker?.targetBpm,
            seeking = seeker?.state,
        )
        return state
    }

    companion object {
        /** The same deviation rule NOOP's offline HRV gate uses. */
        const val ECTOPIC_FRACTION = HrvAnalyzer.ECTOPIC_THRESHOLD
    }
}
