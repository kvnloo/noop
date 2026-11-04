package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the Android-wide ring/vessel preference and its source wiring (#2345). */
class GlobalGaugeStyleTest {
    @Test
    fun invalidAndOutOfRangeFractionsProduceHonestArcs() {
        assertEquals(0f, gaugeRingFraction(null), 0f)
        assertEquals(0f, gaugeRingFraction(Double.NaN), 0f)
        assertEquals(0f, gaugeRingFraction(Double.POSITIVE_INFINITY), 0f)
        assertEquals(0f, gaugeRingFraction(Double.NEGATIVE_INFINITY), 0f)
        assertEquals(0f, gaugeRingFraction(-0.4), 0f)
        assertEquals(0.25f, gaugeRingFraction(0.25), 0f)
        assertEquals(1f, gaugeRingFraction(1.4), 0f)
    }

    @Test
    fun circularGaugesShareTheSavedChoiceAndPreserveLabelsTapsAndMotion() {
        val src = read("android/app/src/main/java/com/noop/ui/LiquidPrimitives.kt")
        val vessel = src.substringAfter("fun LiquidVessel(", "")
            .substringBefore("// MARK: - LiquidTube", "")
        check(vessel.isNotBlank()) { "LiquidVessel declaration missing" }
        val ringBranch = vessel.substringBefore("val renderStill = rememberPoseStill()", "")
        check(ringBranch.isNotBlank()) { "Preference must precede liquid simulation" }
        assertTrue(ringBranch.contains("NoopPrefs.todayRingGauges(gaugeContext)"))
        assertTrue(ringBranch.contains("if (ringGauges)") && ringBranch.contains("GlowRing("))
        assertTrue(ringBranch.contains("showsLabel = false"))
        assertTrue(ringBranch.contains("animated = animated"))
        assertTrue(ringBranch.contains("Modifier.clickable(onClick = onTap)"))
        assertTrue(vessel.contains("LiquidRender) { vessel(size = size, sim = sim"))
        assertTrue(vessel.contains("LiquidRender) { vessel(size = size, sim = posed"))
        assertTrue(read("android/app/src/main/java/com/noop/ui/Components.kt")
            .contains("val renderStill = rememberPoseStill() || !animated"))
    }

    @Test
    fun intelligenceForecastUsesTheSameChoiceAndKeepsTheValue() {
        val src = read("android/app/src/main/java/com/noop/ui/IntelligenceScreen.kt")
            .substringAfter("private fun ForecastCard(", "")
            .substringBefore("private fun sleepHoursLabel(", "")
        check(src.isNotBlank()) { "ForecastCard declaration missing" }
        assertTrue(src.contains("NoopPrefs.todayRingGauges(gaugeContext)"))
        assertTrue(src.contains("GlowRing(") && src.contains("LiquidVessel("))
        assertTrue(src.contains("CountUpText(") && src.contains("value = f.charge"))
    }

    private fun read(path: String): String {
        var root = java.io.File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(4) {
            val file = java.io.File(root, path)
            if (file.isFile) return file.readText()
            root = root.parentFile ?: root
        }
        error("$path not found")
    }
}
