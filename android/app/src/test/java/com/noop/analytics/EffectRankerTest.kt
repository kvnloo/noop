package com.noop.analytics

import com.noop.data.DailyMetric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Faithful Kotlin port of
 * Packages/StrandAnalytics/Tests/StrandAnalyticsTests/EffectRankerTests.swift.
 * Same fixtures, same numbers — cross-platform parity is the contract.
 */
class EffectRankerTest {

    private fun ymd(y: Int, m: Int, d: Int): String = "%04d-%02d-%02d".format(y, m, d)

    private fun row(rows: List<RankedEffect>, behavior: String): RankedEffect? =
        rows.firstOrNull { it.behavior == behavior }

    /** Deterministic per-calendar-day jitter in {-2,-1,0,1,2}, mirroring the Swift fixture, so
     *  with/without groups carry real spread (a constant group yields pooled SD 0 / d 0). */
    private fun jitter(dayOfMonth: Int): Double = ((dayOfMonth * 7) % 5 - 2).toDouble()

    /** Every outcome day the behaviour was not logged on. These fixtures predate the Yes/No split and
     *  test the LAG math, so they keep their original partition by declaring the complement explicitly —
     *  the engine no longer assumes it, and a test that quietly lost its control group would still pass
     *  by returning nothing. */
    private fun controlsFor(outcome: Map<String, Double>, behaviorDays: Set<String>): Set<String> =
        outcome.keys - behaviorDays

    /** Test-only: the BehaviorEffect at a specific lag, via the engine's own shift alignment. */
    private fun effectAtLag(behaviorDays: Set<String>, outcome: Map<String, Double>, lag: Int) =
        EffectRanker.effect(
            behaviorDays, controlsFor(outcome, behaviorDays),
            EffectRanker.shiftedOutcome(outcome, lag), "Alcohol", "Charge",
        )

    // Planted lag-1 effect is found at L=1 and beats L=0/L=2

    @Test
    fun plantedLag1IsFoundAndWins() {
        val outcome = HashMap<String, Double>()
        val behaviorDays = HashSet<String>()

        // Anchors Jun 1,5,9,13,17,21 (6, spaced 4 apart).
        for (i in 0 until 6) behaviorDays.add(ymd(2026, 6, 1 + 4 * i))
        for (d in 1..30) outcome[ymd(2026, 6, d)] = 70.0 + jitter(d)
        for (d in 1..8) outcome[ymd(2026, 7, d)] = 70.0 + jitter(d)
        for (i in 0 until 6) {
            val dip = 2 + 4 * i
            outcome[ymd(2026, 6, dip)] = 50.0 + jitter(dip)
        }

        val out = EffectRanker.rank(
            mapOf("Alcohol" to behaviorDays), mapOf("Alcohol" to controlsFor(outcome, behaviorDays)),
            outcome, "Charge",
        )
        val r = row(out, "Alcohol")
        assertNotNull(r)
        assertEquals(1, r!!.lag)
        assertEquals("next morning", r.leadLagText)
        assertTrue(r.effect.cohensD < 0)
        assertTrue(r.effect.significant)
        assertTrue(r.effect.meanWith < 55)
        assertTrue(r.effect.meanWithout > 65)

        val d1 = abs(r.effect.cohensD)
        val d0 = abs(effectAtLag(behaviorDays, outcome, 0)!!.cohensD)
        val d2 = abs(effectAtLag(behaviorDays, outcome, 2)!!.cohensD)
        assertTrue(d1 > d0)
        assertTrue(d1 > d2)
    }

    // Group gate suppresses thin behaviours

    @Test
    fun thinBehaviourIsDropped() {
        val outcome = HashMap<String, Double>()
        val thin = HashSet<String>()
        for (d in 1..3) {
            val day = ymd(2026, 6, d)
            thin.add(day)
            outcome[day] = 50.0 + jitter(d)
            outcome[ymd(2026, 6, d + 1)] = 50.0 + jitter(d + 1)
        }
        for (d in 1..8) outcome[ymd(2026, 7, d)] = 70.0 + jitter(d)

        val out = EffectRanker.rank(
            mapOf("Sparse" to thin), mapOf("Sparse" to controlsFor(outcome, thin)), outcome, "Charge",
        )
        assertTrue(out.isEmpty())
    }

    // Ranking order matches BehaviorInsights.rank

    @Test
    fun rankingOrder() {
        val outcome = HashMap<String, Double>()
        val big = HashSet<String>()
        for (d in 1..6) {
            val day = ymd(2026, 1, d)
            big.add(day)
            outcome[day] = 50.0 + jitter(d)
        }
        val small = HashSet<String>()
        for (d in 1..6) {
            val day = ymd(2026, 3, d)
            small.add(day)
            outcome[day] = 66.0 + jitter(d)
        }
        for (d in 10..20) outcome[ymd(2026, 5, d)] = 70.0 + jitter(d)

        val out = EffectRanker.rank(
            mapOf("Big" to big, "Small" to small),
            mapOf("Big" to controlsFor(outcome, big), "Small" to controlsFor(outcome, small)),
            outcome, "Charge",
        )
        assertEquals(listOf("Big", "Small"), out.map { it.behavior })
        assertEquals(0, row(out, "Big")!!.lag)
        assertEquals(0, row(out, "Small")!!.lag)
    }

    // Confidence tiers from paired-day count

    @Test
    fun confidenceTiers() {
        assertEquals(ScoreConfidence.CALIBRATING, EffectRanker.confidence(4))
        assertEquals(ScoreConfidence.BUILDING, EffectRanker.confidence(5))
        assertEquals(ScoreConfidence.BUILDING, EffectRanker.confidence(9))
        assertEquals(ScoreConfidence.SOLID, EffectRanker.confidence(10))
    }

    // shiftedOutcome alignment

    @Test
    fun shiftedOutcomeAlignment() {
        val outcome = mapOf(ymd(2026, 6, 2) to 55.0, ymd(2026, 6, 3) to 60.0)
        assertEquals(outcome, EffectRanker.shiftedOutcome(outcome, 0))
        val s1 = EffectRanker.shiftedOutcome(outcome, 1)
        assertEquals(55.0, s1[ymd(2026, 6, 1)])
        assertEquals(60.0, s1[ymd(2026, 6, 2)])
        assertNull(s1[ymd(2026, 6, 3)])
    }

    // sentence appends the lead/lag clause

    @Test
    fun sentenceAppendsLeadLag() {
        val e = BehaviorEffect(
            behavior = "Alcohol", outcome = "Charge",
            meanWith = 50.0, meanWithout = 70.0, delta = -20.0,
            pctChange = -100.0 * 20.0 / 70.0, nWith = 6, nWithout = 8,
            cohensD = -2.0, pApprox = 0.001, significant = true,
        )
        val r = RankedEffect("Alcohol", "Charge", 1, e, ScoreConfidence.BUILDING)
        assertTrue(r.sentence().endsWith("(next morning)."))
    }
    // The Reddit report: unlogged days are not answers.

    /**
     * "If I didn't track something for 100 days, NOOP takes that as a NO for 100 days, whereas it simply
     * was not logged at all." Reported by a user on Reddit, and it was exactly what the split did.
     *
     * The fixture is that report: 8 days logged Yes with a real dip, 6 days logged No, and 60 days with
     * an outcome and no journal row at all. Those 60 must not reach the control group, because nothing
     * about them says the user did not do the thing.
     */
    @Test
    fun daysWithNoJournalRowAreNotControls() {
        val outcome = HashMap<String, Double>()
        val yes = HashSet<String>()
        val no = HashSet<String>()
        for (d in 1..8) { yes.add(ymd(2026, 6, d)); outcome[ymd(2026, 6, d)] = 50.0 + jitter(d) }
        for (d in 9..14) { no.add(ymd(2026, 6, d)); outcome[ymd(2026, 6, d)] = 70.0 + jitter(d) }
        // Never opened the journal on these, and their values sit far from BOTH answered groups.
        for (d in 15..30) outcome[ymd(2026, 6, d)] = 20.0 + jitter(d)
        for (d in 1..31) outcome[ymd(2026, 7, d)] = 20.0 + jitter(d)
        for (d in 1..13) outcome[ymd(2026, 8, d)] = 20.0 + jitter(d)

        val e = EffectRanker.effect(yes, no, outcome, "Alcohol", "Charge")
        assertNotNull(e)
        // Controls are the six NO days only. If the 60 unlogged days leaked in, nWithout would be 66 and
        // meanWithout would be dragged towards 20.
        assertEquals(6, e!!.nWithout)
        assertEquals(8, e.nWith)
        assertTrue("controls must average near the NO days, not the unlogged ones", e.meanWithout > 60.0)
    }

    /**
     * A behaviour the user only ever ticks Yes has no control group, so there is no comparison to make
     * and the honest answer is none. Previously it got one, built from every day the journal was never
     * opened - the most confident-looking findings in the app came from the least evidence.
     */
    @Test
    fun aBehaviourNeverLoggedNoYieldsNothing() {
        val outcome = HashMap<String, Double>()
        val yes = HashSet<String>()
        for (d in 1..20) { yes.add(ymd(2026, 6, d)); outcome[ymd(2026, 6, d)] = 50.0 + jitter(d) }
        for (d in 21..30) outcome[ymd(2026, 6, d)] = 80.0 + jitter(d)

        assertNull(EffectRanker.effect(yes, emptySet(), outcome, "Alcohol", "Charge"))
        assertTrue(
            EffectRanker.rank(mapOf("Alcohol" to yes), emptyMap(), outcome, "Charge").isEmpty(),
        )
    }

    /**
     * rank() fails CLOSED on a caller that forgets the controls: no insight, rather than a wrong one
     * measured against every day the user never opened the journal. There are two production callers and
     * this is what stops a third from reintroducing the bug silently.
     */
    @Test
    fun rankWithoutControlsProducesNothingRatherThanGuessing() {
        val outcome = HashMap<String, Double>()
        val yes = HashSet<String>()
        for (d in 1..10) { yes.add(ymd(2026, 6, d)); outcome[ymd(2026, 6, d)] = 50.0 + jitter(d) }
        for (d in 11..30) outcome[ymd(2026, 6, d)] = 75.0 + jitter(d)
        assertTrue(EffectRanker.rank(mapOf("Alcohol" to yes), emptyMap(), outcome, "Charge").isEmpty())
    }

    // rankNoLag: the Kotlin twin of Swift BehaviorInsights.rank, which the Insights screen now uses.

    /**
     * Twin of Swift `testRankOrdersByEffectSizeSignificantFirst`, same fixture and same expectations.
     *
     * The Insights screen used to rank with its own copy of this — a "crude significance" of |d| >= 0.5
     * with >= 3 a side, where iOS ran a Welch p on the same screen. Identical journals could flag
     * different behaviours per platform. Pinning the ordering here is what stops that reopening.
     */
    @Test
    fun rankNoLagOrdersByEffectSizeSignificantFirst() {
        val outcome = mapOf(
            "d1" to 50.0, "d2" to 52.0, "d3" to 48.0, "d4" to 51.0, "d5" to 49.0, "d6" to 53.0,
            "d7" to 70.0, "d8" to 72.0, "d9" to 68.0, "d10" to 71.0, "d11" to 69.0, "d12" to 73.0,
        )
        val strong = setOf("d1", "d2", "d3", "d4", "d5", "d6")
        val weak = setOf("d1", "d7", "d2")
        val tiny = setOf("d3", "d9")
        val all = outcome.keys
        val ranked = EffectRanker.rankNoLag(
            mapOf("Strong" to strong, "Weak" to weak, "Tiny" to tiny),
            mapOf("Strong" to (all - strong), "Weak" to (all - weak), "Tiny" to (all - tiny)),
            outcome, "Recovery",
        )
        assertEquals(3, ranked.size)
        assertEquals("Strong", ranked.first().behavior)
        assertTrue(ranked.first().significant)
        assertFalse(ranked[1].significant)
        assertFalse(ranked[2].significant)
        assertTrue(abs(ranked[1].cohensD) >= abs(ranked[2].cohensD))
    }

    /**
     * rankNoLag fails CLOSED too, not only rank().
     *
     * It is a second public entry point into the same split, and this PR exists because that split had
     * four separate homes. A new door into it gets the same lock: no controls means no comparison, so a
     * caller that forgets loses the insight rather than getting one measured against unlogged days.
     */
    @Test
    fun rankNoLagWithoutControlsProducesNothing() {
        val outcome = HashMap<String, Double>()
        val yes = HashSet<String>()
        for (d in 1..10) { yes.add(ymd(2026, 6, d)); outcome[ymd(2026, 6, d)] = 50.0 + jitter(d) }
        for (d in 11..30) outcome[ymd(2026, 6, d)] = 75.0 + jitter(d)
        assertTrue(EffectRanker.rankNoLag(mapOf("Alcohol" to yes), emptyMap(), outcome, "Charge").isEmpty())
    }

    /** Twin of Swift `testRankDropsUncomputableBehaviors`: a behaviour covering every day has no
     *  without-group and is dropped rather than ranked at zero. */
    @Test
    fun rankNoLagDropsUncomputableBehaviours() {
        val outcome = mapOf("a" to 60.0, "b" to 62.0, "c" to 70.0, "d" to 72.0)
        val allDays = setOf("a", "b", "c", "d")
        val half = setOf("a", "b")
        val all = outcome.keys
        val ranked = EffectRanker.rankNoLag(
            mapOf("AllDays" to allDays, "Half" to half),
            mapOf("AllDays" to (all - allDays), "Half" to (all - half)),
            outcome, "Recovery",
        )
        assertEquals(1, ranked.size)
        assertEquals("Half", ranked.first().behavior)
    }


    // --- Rest outcome: missing coverage and local day keys --------------------

    /** Synthetic persisted row: the score is derived by RestScorer, never supplied by the test. */
    private fun restRow(
        day: String, tstMin: Double?, eff: Double?,
        deepMin: Double? = 80.0, remMin: Double? = 90.0,
    ): DailyMetric = DailyMetric(
        deviceId = "t", day = day, totalSleepMin = tstMin, efficiency = eff,
        deepMin = deepMin, remMin = remMin,
    )

    /** The same scored-only sleep_performance series as Swift Rest.composite(daily:). */
    private fun restOutcome(rows: List<DailyMetric>): Map<String, Double> {
        val outcome = HashMap<String, Double>()
        for (row in rows) {
            val score = RestScorer.restFromDaily(row)
            if (score != null) outcome[row.day] = score
        }
        return outcome
    }

    /**
     * Twin of Swift testAnsweredDaysWithoutRestCoverageLeaveBothGroups.
     * Answered journal days without a scorable night are absent, never zero-valued.
     */
    @Test
    fun answeredDaysWithoutRestCoverageLeaveBothGroups() {
        val rows = mutableListOf<DailyMetric>()
        val yes = mutableSetOf<String>()
        val no = mutableSetOf<String>()
        for (d in 1..6) {
            val day = "y$d"
            yes.add(day)
            rows.add(restRow(day, tstMin = 300.0 + d, eff = 0.80))
        }
        for (d in 1..6) {
            val day = "n$d"
            no.add(day)
            rows.add(restRow(day, tstMin = 450.0 + d, eff = 0.92))
        }
        val gaps: List<Triple<String, Double?, Double?>> = listOf(
            Triple("y-gap1", null, 0.95),
            Triple("y-gap2", 0.0, 0.95),
            Triple("n-gap1", null, null),
            Triple("n-gap2", null, 0.97),
        )
        for ((day, total, eff) in gaps) {
            if (day.startsWith("y")) yes.add(day) else no.add(day)
            rows.add(restRow(day, tstMin = total, eff = eff))
        }
        val outcome = restOutcome(rows)
        assertEquals("only scorable nights enter the series", 12, outcome.size)
        for ((day, _, _) in gaps) {
            assertNull("$day: unknown Rest has no outcome point", outcome[day])
        }

        val e = EffectRanker.effect(yes, no, outcome, "Late meal", "Rest")
        assertNotNull(e)
        val effect = e!!
        assertEquals(6, effect.nWith)
        assertEquals(6, effect.nWithout)
        assertEquals(yes.mapNotNull { outcome[it] }.average(), effect.meanWith, 1e-9)
        assertEquals(no.mapNotNull { outcome[it] }.average(), effect.meanWithout, 1e-9)
        assertTrue("a zero outcome would drag down the mean", effect.meanWith > 40.0)
    }

    /**
     * Twin of Swift testBehaviourWhoseYesDaysAllLackRestYieldsNoInsight.
     * Six explicit Yes answers without nights cannot be interpreted as a low Rest effect.
     */
    @Test
    fun behaviourWhoseYesDaysAllLackRestYieldsNoInsight() {
        val rows = mutableListOf<DailyMetric>()
        val yes = mutableSetOf<String>()
        val no = mutableSetOf<String>()
        for (d in 1..6) {
            val day = "y$d"
            yes.add(day)
            rows.add(restRow(day, tstMin = null, eff = 0.9))
        }
        for (d in 1..6) {
            val day = "n$d"
            no.add(day)
            rows.add(restRow(day, tstMin = 420.0, eff = 0.9))
        }
        val outcome = restOutcome(rows)
        assertEquals(6, outcome.size)
        assertNull(EffectRanker.effect(yes, no, outcome, "Alcohol", "Rest"))
        assertTrue(
            EffectRanker.rankNoLag(
                behaviors = mapOf("Alcohol" to yes), controls = mapOf("Alcohol" to no),
                outcomeByDay = outcome, outcome = "Rest",
            ).isEmpty(),
        )
    }

    /**
     * Twin of Swift testRestOutcomeValueIsTheCompositeNotARawComponent.
     * 4 h / 8 h need, 80% efficiency, 60 min deep + 60 min REM, neutral consistency:
     * 0.50 * 0.50 + 0.20 * 0.80 + 0.20 * 1.0 + 0.10 * 0.50 = 0.66.
     */
    @Test
    fun restOutcomeValueIsTheCompositeNotARawComponent() {
        val outcome = restOutcome(
            listOf(restRow("d", tstMin = 240.0, eff = 0.80, deepMin = 60.0, remMin = 60.0)),
        )
        assertEquals(66.0, outcome.getValue("d"), 1e-9)
    }

    /**
     * Twin of Swift testJournalAndRestJoinOnTheLocalDayKeyAtAFractionalOffset.
     * At +5:30, the first half hour of the local day is still the previous UTC date;
     * UTC-joining No answers would overlap the Yes days.
     */
    @Test
    fun journalAndRestJoinOnTheLocalDayKeyAtAFractionalOffset() {
        val off = 5 * 3600L + 1800L
        fun local(day: String, secondsAfterMidnight: Long): Long =
            AnalyticsEngine.dayStartUtcSeconds(day) - off + secondsAfterMidnight

        val yesDays = (10..15).map { "2021-06-$it" }
        val noDays = (16..21).map { "2021-06-$it" }
        val yesTs = yesDays.map { local(it, 86_399L) }
        val noTs = noDays.map { local(it, 30L) }

        val yesLocal = yesTs.map { AnalyticsEngine.dayString(it, off) }.toSet()
        val noLocal = noTs.map { AnalyticsEngine.dayString(it, off) }.toSet()
        assertEquals(yesDays.toSet(), yesLocal)
        assertEquals(noDays.toSet(), noLocal)
        val noUtc = noTs.map { AnalyticsEngine.dayString(it, 0L) }.toSet()
        assertTrue("fixture must cross a UTC day edge", noUtc != noLocal)
        assertFalse("UTC No keys would mix into Yes days", noUtc.intersect(yesLocal).isEmpty())
        assertTrue("local groups must be disjoint", yesLocal.intersect(noLocal).isEmpty())

        val rows = yesDays.map { restRow(it, tstMin = 330.0, eff = 0.82) } +
            noDays.map { restRow(it, tstMin = 470.0, eff = 0.93) }
        val outcome = restOutcome(rows)
        val e = EffectRanker.effect(yesLocal, noLocal, outcome, "Late screen", "Rest")
        assertNotNull(e)
        val effect = e!!
        assertEquals(6, effect.nWith)
        assertEquals(6, effect.nWithout)
        assertEquals(outcome.getValue("2021-06-10"), effect.meanWith, 1e-9)
        assertEquals(outcome.getValue("2021-06-16"), effect.meanWithout, 1e-9)
        assertTrue(effect.delta < 0.0)
    }
}
