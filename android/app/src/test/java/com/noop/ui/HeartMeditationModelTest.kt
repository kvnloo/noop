package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The pure pieces behind the Live console's heart meditation view (HeartMeditationModel.kt). */
class HeartMeditationModelTest {

    // ── Artifact gate ─────────────────────────────────────────────────────────

    @Test
    fun `noisy owner sample never seeds a rhythm`() {
        // Intervals seen on the owner's Live screen with an Oura Ring 3: 2000 / 567 / 762 ms. They disagree
        // by far more than 20%, so the view must keep waiting rather than animate them.
        val gate = BeatGate()
        val verdicts = listOf(2000, 567, 762).map { gate.offer(it) }
        assertTrue(verdicts.none { it == BeatVerdict.ACCEPTED })
        assertEquals(BeatSteadiness.WAITING, steadiness(verdicts))
    }

    @Test
    fun `steady rhythm seeds after three agreeing beats then rejects artifacts`() {
        val gate = BeatGate()
        assertEquals(BeatVerdict.SEEDING, gate.offer(800))
        assertEquals(BeatVerdict.SEEDING, gate.offer(820))
        assertEquals(BeatVerdict.ACCEPTED, gate.offer(790))
        assertEquals(BeatVerdict.ACCEPTED, gate.offer(810))
        assertEquals(BeatVerdict.DEVIANT, gate.offer(1600))       // a missed beat (two intervals as one)
        assertEquals(BeatVerdict.DEVIANT, gate.offer(400))        // a split beat
        assertEquals(BeatVerdict.OUT_OF_RANGE, gate.offer(2500))  // a sentinel / gap
        assertEquals(BeatVerdict.ACCEPTED, gate.offer(805))       // the rhythm survives three rejects
        repeat(HeartMeditationTuning.RESEED_AFTER_REJECTS) { gate.offer(250) }
        assertTrue("four rejects in a row drop the reference", !gate.seeded)
        assertEquals(BeatVerdict.SEEDING, gate.offer(805))
    }

    @Test
    fun `a real change of pace re-seeds instead of locking the gate out`() {
        val gate = BeatGate()
        repeat(6) { gate.offer(1000) }
        // Heart rate jumps 60 → ~86 bpm and stays there.
        val after = (0 until 10).map { gate.offer(700) }
        assertTrue("re-seeds and accepts the new pace", after.takeLast(3).all { it == BeatVerdict.ACCEPTED })
        val firstAccept = after.indexOf(BeatVerdict.ACCEPTED)
        assertTrue("within reseed + seed beats, was $firstAccept",
            firstAccept in 0 until HeartMeditationTuning.RESEED_AFTER_REJECTS + HeartMeditationTuning.SEED_BEATS)
    }

    @Test
    fun `a deep breathing wave is not mistaken for artifacts`() {
        // ±12 bpm RSA at 6 br/min (a young, relaxed slow breather) swings R-R ~800 ↔ ~1200 ms each breath.
        val gate = BeatGate()
        val verdicts = rsaBeats(seconds = 60.0, meanBpm = 62.0, ampBpm = 12.0, hz = 0.1).map { gate.offer(it) }
        assertEquals(0, verdicts.count { it == BeatVerdict.DEVIANT || it == BeatVerdict.OUT_OF_RANGE })
    }

    @Test
    fun `steadiness reads steady, noisy and waiting`() {
        val clean = List(20) { BeatVerdict.ACCEPTED }
        assertEquals(BeatSteadiness.STEADY, steadiness(clean))
        val noisy = List(20) { if (it % 3 == 0) BeatVerdict.DEVIANT else BeatVerdict.ACCEPTED }
        assertEquals(BeatSteadiness.NOISY, steadiness(noisy))
        assertEquals(BeatSteadiness.WAITING, steadiness(List(7) { BeatVerdict.ACCEPTED }))
        assertEquals(BeatSteadiness.WAITING, steadiness(List(20) { BeatVerdict.SEEDING }))
    }

    // ── Playout ───────────────────────────────────────────────────────────────

    @Test
    fun `beats batched in one packet are played out at their own spacing`() {
        val p = BeatPlayout()
        p.enqueue(800)
        p.enqueue(820)
        assertEquals(800, p.poll(0))
        assertNull(p.poll(400))
        assertNull(p.poll(819))
        assertEquals(820, p.poll(820))
        assertNull(p.poll(5000))
    }

    @Test
    fun `a late beat fires at once and re-anchors instead of bursting`() {
        val p = BeatPlayout()
        p.enqueue(800)
        assertEquals(800, p.poll(0))
        p.enqueue(800)
        assertEquals(800, p.poll(5000))   // arrived long after it was due: pulse now
        p.enqueue(800)
        assertNull(p.poll(5400))          // next spacing measured from 5000, not from 1600
        assertEquals(800, p.poll(5800))
    }

    @Test
    fun `a long backlog is trimmed so the view never lags behind the heart`() {
        val p = BeatPlayout()
        repeat(10) { p.enqueue(800 + it) }
        assertEquals(HeartMeditationTuning.MAX_QUEUED_BEATS, p.queued)
        assertEquals(806, p.poll(0))      // the oldest six were dropped
    }

    // ── R-R → vessel level (the RSA wave) ─────────────────────────────────────

    /** A synthetic RSA heart: HR = mean + amp·sin(2π f t), beats emitted at their own intervals. */
    private fun rsaBeats(seconds: Double, meanBpm: Double, ampBpm: Double, hz: Double): List<Int> {
        val out = ArrayList<Int>()
        var t = 0.0
        while (t < seconds) {
            val hr = meanBpm + ampBpm * sin(2 * PI * hz * t)
            val rr = (60_000.0 / hr).roundToInt()
            out.add(rr)
            t += rr / 1000.0
        }
        return out
    }

    @Test
    fun `vessel level rises and falls with the heart-rate wave`() {
        val session = HeartMeditationSession()
        val beats = rsaBeats(seconds = 90.0, meanBpm = 65.0, ampBpm = 5.0, hz = 0.1)
        var beatTime = 0L
        var frame = 0L
        val levels = ArrayList<Double>()
        val hrs = ArrayList<Double>()
        for (rr in beats) {
            beatTime += rr
            // Run frames until this beat is delivered, then keep ticking until it pulses.
            while (frame < beatTime) { session.tick(frame); frame += 16 }
            session.onPacket(listOf(rr), frame)
            val before = session.pulses
            while (session.pulses == before && frame < beatTime + 3000) { session.tick(frame); frame += 16 }
            if (beatTime > 20_000) {
                levels.add(session.levelAt(frame + rr))   // where this beat steers the level
                hrs.add(60_000.0 / rr)
            }
        }
        assertEquals(BeatSteadiness.STEADY, session.steadiness)
        assertTrue("level stays inside the vessel", levels.all { it in 0.2..0.85 })
        val r = pearson(levels, hrs)
        assertTrue("level tracks the HR wave, r = $r", r > 0.7)
        val swing = session.rhythm.swingBpm!!
        // Peak-to-trough of a ±5 bpm wave is ~10 bpm (3-beat smoothing shaves a little).
        assertTrue("swing ≈ 10 bpm, was $swing", swing in 6.0..11.0)
    }

    @Test
    fun `swing tracks different injected amplitudes`() {
        for (amp in listOf(2.0, 4.0, 8.0)) {
            val session = HeartMeditationSession()
            var t = 0L
            for (rr in rsaBeats(80.0, 62.0, amp, 0.1)) {
                t += rr
                session.onPacket(listOf(rr), t)
                session.tick(t + 2)
            }
            val swing = session.rhythm.swingBpm!!
            assertTrue("amp $amp → swing $swing", abs(swing - 2 * amp) <= 0.35 * 2 * amp + 1.0)
        }
    }

    @Test
    fun `lost beats glide the vessel back to rest and say waiting`() {
        val session = HeartMeditationSession()
        var t = 0L
        repeat(30) { session.onPacket(listOf(900), t); session.tick(t + 1); t += 900 }
        assertEquals(BeatSteadiness.STEADY, session.steadiness)
        session.tick(t + HeartMeditationTuning.STALE_MS + 100)
        assertEquals(BeatSteadiness.WAITING, session.steadiness)
        assertEquals(HeartMeditationTuning.LEVEL_REST, session.levelAt(t + 20_000), 1e-9)
    }

    @Test
    fun `nothing pulses and the vessel rests until the beats are steady`() {
        val session = HeartMeditationSession()
        var t = 0L
        // Seeded after 3 beats, but WAITING until 8 clean beats are in the window.
        repeat(7) { session.onPacket(listOf(900), t); session.tick(t + 1); t += 900 }
        assertEquals(BeatSteadiness.WAITING, session.steadiness)
        assertEquals(0, session.pulses)
        assertNull(session.cleanBpm)
        assertEquals(HeartMeditationTuning.LEVEL_REST, session.levelAt(t + 5_000), 1e-9)
        repeat(3) { session.onPacket(listOf(900), t); session.tick(t + 1); t += 900 }
        assertEquals(BeatSteadiness.STEADY, session.steadiness)
        assertTrue(session.pulses > 0)
        assertEquals(67, session.cleanBpm)
    }

    // ── Rhythm focus ──────────────────────────────────────────────────────────

    @Test
    fun `rhythm focus recovers several injected breathing rhythms`() {
        for (perMin in listOf(5.0, 6.0, 7.0, 9.0)) {
            val f = rhythmFocus(rsaBeats(90.0, 64.0, 5.0, perMin / 60.0))!!
            assertTrue("focus high for a pure wave at $perMin/min, was ${f.focus}", f.focus > 0.6)
            assertEquals("dominant rhythm for $perMin/min", perMin, f.perMinute, 0.6)
        }
    }

    @Test
    fun `rhythm focus is low for an irregular heart rate`() {
        val rnd = Random(7)
        // Random-walk heart rate: power spread across the band, no single rhythm.
        var hr = 65.0
        val beats = List(120) {
            hr = (hr + rnd.nextDouble(-3.0, 3.0)).coerceIn(55.0, 80.0)
            (60_000.0 / hr).roundToInt()
        }
        val f = rhythmFocus(beats)!!
        assertTrue("irregular wave should not look focused, was ${f.focus}", f.focus < 0.45)
    }

    @Test
    fun `rhythm focus waits for a full window of clean beats`() {
        assertNull(rhythmFocus(rsaBeats(40.0, 64.0, 5.0, 0.1)))
        assertNull(rhythmFocus(emptyList()))
    }

    // ── Pacer ─────────────────────────────────────────────────────────────────

    @Test
    fun `pacer breathes in then out across one cycle`() {
        val bpm = 6.0                    // 10 s cycle
        assertEquals(0.0, pacerLevel(0, bpm), 1e-9)
        assertEquals(0.5, pacerLevel(2_500, bpm), 1e-9)
        assertEquals(1.0, pacerLevel(5_000, bpm), 1e-9)
        assertEquals(0.0, pacerLevel(10_000, bpm), 1e-9)
        assertTrue(pacerInhaling(4_999, bpm))
        assertTrue(!pacerInhaling(5_001, bpm))
        assertEquals(1_000L, pacerMsToNextPhase(4_000, bpm))
        assertEquals(5_000L, pacerMsToNextPhase(5_000, bpm))
        // Rising through the inhale, falling through the exhale.
        val inhale = (0..50).map { pacerLevel(it * 100L, bpm) }
        assertTrue(inhale.zipWithNext().all { (a, b) -> b >= a })
        val exhale = (50..100).map { pacerLevel(it * 100L, bpm) }
        assertTrue(exhale.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test
    fun `pacer honours an uneven inhale share`() {
        // 40:60 like Breathe's Relax preset, at 5.5 br/min.
        val cycle = 60_000.0 / 5.5
        assertEquals(1.0, pacerLevel((cycle * 0.4).roundToInt().toLong(), 5.5, inhaleFraction = 0.4), 1e-3)
        assertTrue(pacerInhaling((cycle * 0.39).toLong(), 5.5, inhaleFraction = 0.4))
        assertTrue(!pacerInhaling((cycle * 0.41).toLong(), 5.5, inhaleFraction = 0.4))
    }

    private fun pearson(a: List<Double>, b: List<Double>): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            num += (a[i] - ma) * (b[i] - mb)
            da += (a[i] - ma) * (a[i] - ma)
            db += (b[i] - mb) * (b[i] - mb)
        }
        return num / sqrt(da * db)
    }
}
