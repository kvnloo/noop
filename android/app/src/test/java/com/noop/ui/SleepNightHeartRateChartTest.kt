package com.noop.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #579 / #1537: the nightly HR line existed only in the stage-less fallback.
 * A typical night with two or more real stage segments took the Classic timeline
 * branch and never called LineChart, even with valid one-minute HR buckets.
 */
class SleepNightHeartRateChartTest {
    @Test
    fun twoBucketsAreRequiredForALine() {
        // 0 or 1 sample cannot make a line; two one-minute samples can.
        val syntheticNightBpm = listOf(71.0, 74.0, 69.0)
        assertFalse(hasSleepNightHeartRateLine(0))
        assertFalse(hasSleepNightHeartRateLine(syntheticNightBpm.take(1).size))
        assertTrue(hasSleepNightHeartRateLine(syntheticNightBpm.take(2).size))
        assertTrue(hasSleepNightHeartRateLine(syntheticNightBpm.size))
    }

    /**
     * JVM cannot mount the Compose sleep card. Pin the ACTUAL call site as well as
     * the pure bucket-count gate: testing only the gate would have passed before
     * the bug, because the original call lived inside the wrong else branch.
     * The repository already uses source-level UI-contract tests for row order.
     */
    @Test
    fun realStageClassicCardIncludesTheNightLineAheadOfStageTracks() {
        val here = File(System.getProperty("user.dir") ?: ".")
        val relativePath = "android/app/src/main/java/com/noop/ui/SleepScreen.kt"
        val root = listOf(here, File(here, ".."), File(here, "../.."))
            .firstOrNull { File(it, relativePath).isFile }
            ?: error("cannot locate repository root from ${here.absolutePath}")
        val src = File(root, relativePath).readText()

        val mode = src.indexOf("if (chartStyle != SleepChartStyle.CLASSIC && filledSegments != null) {")
        assertTrue("missing stepped-vs-Classic decision", mode >= 0)
        val classic = src.indexOf("\n                } else {\n                    SleepChartCard(", mode)
        assertTrue("missing Classic real-stage chart", classic > mode)
        val fallback = src.indexOf("\n            } else {\n                SleepChartCard(", classic)
        assertTrue("missing stage-less fallback chart", fallback > classic)
        val end = src.indexOf("// For an Oura-provided night", fallback)
        assertTrue("missing end of stage chart paths", end > fallback)

        val steppedBlock = src.substring(mode, classic)
        val classicBlock = src.substring(classic, fallback)
        val fallbackBlock = src.substring(fallback, end)
        val hrCall = "SleepNightHeartRateLine(nightHr)"
        assertFalse("stepped stage chart remains stage-only", steppedBlock.contains(hrCall))
        val hrAt = classicBlock.indexOf(hrCall)
        val tracksAt = classicBlock.indexOf("StageTimeline(")
        assertTrue("real-stage Classic must call the HR line", hrAt >= 0)
        assertTrue("HR line must precede Classic stage tracks", tracksAt > hrAt)
        val fallbackHrAt = fallbackBlock.indexOf(hrCall)
        val stripAt = fallbackBlock.indexOf("HypnogramWithAxis(")
        assertTrue("stage-less Classic fallback must keep HR line", fallbackHrAt >= 0)
        assertTrue("fallback HR line must precede the proportional strip", stripAt > fallbackHrAt)
    }
}
