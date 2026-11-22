package com.noop.analytics

/** Pure, same-day comparison for the Personal Experiment card. */
internal object PersonalExperiment {
    data class Snapshot(
        val durationDays: Int,
        val daysElapsed: Int,
        val baselineMean: Double?,
        val baselineCount: Int,
        val interventionMean: Double?,
        val interventionCount: Int,
        val loggedToday: Boolean,
        /** 0…100 percent. */
        val compliance: Double,
        val confidence: Confidence,
    ) {
        val delta: Double?
            get() {
                val intervention = interventionMean ?: return null
                val baseline = baselineMean ?: return null
                return intervention - baseline
            }
    }

    enum class Confidence { Low, Early, Stronger }

    /** Day keys are yyyy-MM-dd; daysSinceStart excludes the start day. */
    fun snapshot(
        outcomeDays: Map<String, Double>,
        loggedDays: Set<String>,
        controlDays: Set<String>,
        startedDay: String,
        today: String,
        daysSinceStart: Int,
        durationDays: Int,
        baselineDays: Int,
    ): Snapshot {
        val duration = durationDays.coerceAtLeast(1)
        // Use the same explicit No controls as Behaviour Effects, before the experiment only.
        val baseline = outcomeDays.keys
            .filter { it < startedDay && it in controlDays }
            .sorted()
            .takeLast(baselineDays.coerceAtLeast(1))
            .mapNotNull { outcomeDays[it] }
        // The first duration outcome days form the window; only Yes days contribute.
        val intervention = outcomeDays.keys
            .filter { it >= startedDay && it <= today }
            .sorted()
            .take(duration)
            .filter { it in loggedDays }
            .mapNotNull { outcomeDays[it] }

        val daysElapsed = (daysSinceStart + 1).coerceIn(1, duration)
        val compliance = intervention.size.toDouble() / daysElapsed
        val paired = minOf(baseline.size, intervention.size)
        val confidence = when {
            paired >= 10 && compliance >= 0.65 -> Confidence.Stronger
            paired >= 5 -> Confidence.Early
            else -> Confidence.Low
        }
        return Snapshot(
            durationDays = duration,
            daysElapsed = daysElapsed,
            baselineMean = mean(baseline),
            baselineCount = baseline.size,
            interventionMean = mean(intervention),
            interventionCount = intervention.size,
            loggedToday = today in loggedDays,
            compliance = compliance * 100,
            confidence = confidence,
        )
    }

    private fun mean(values: List<Double>): Double? =
        if (values.isEmpty()) null else values.sum() / values.size
}
