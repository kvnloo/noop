package com.noop.location

/**
 * Pure workout split interpolation. Inputs are cumulative moving metres and pause-excluding
 * active elapsed milliseconds; no GPS, Room, or UI dependency.
 *
 * Once ryanbr/noop#2611 lands, its timed route can be projected into these points by summing
 * Haversine legs only within each pause segment. Absent active elapsed time is not guessed.
 * Kotlin twin of Strand/App/GpsSplitMath.swift.
 */
object GpsSplitMath {
    data class Point(val distanceMetres: Double, val activeElapsedMs: Long, val segment: Int = 0)

    data class Split(
        val distanceMetres: Double,
        val elapsedSeconds: Double,
        val durationSeconds: Double,
        val complete: Boolean,
    )

    private const val maximumCompleteSplits = 10_000.0

    /**
     * Returns complete fixed-distance splits followed by an optional final incomplete split.
     * Invalid or reordered input yields an empty list, not a misleading prefix.
     */
    fun calculate(points: List<Point>, splitMetres: Double): List<Split> {
        if (points.size < 2 || !splitMetres.isFinite() || splitMetres <= 0.0) return emptyList()
        val first = points.first()
        if (!first.distanceMetres.isFinite() || first.distanceMetres != 0.0 ||
            first.activeElapsedMs < 0L || first.segment < 0) return emptyList()

        var previous = first
        for (current in points.drop(1)) {
            if (!current.distanceMetres.isFinite() ||
                current.distanceMetres < previous.distanceMetres ||
                current.activeElapsedMs < previous.activeElapsedMs ||
                current.segment < previous.segment) return emptyList()
            if (current.segment != previous.segment) {
                if (current.distanceMetres != previous.distanceMetres) return emptyList()
            } else if (current.distanceMetres > previous.distanceMetres) {
                if (current.activeElapsedMs <= previous.activeElapsedMs) return emptyList()
            }
            previous = current
        }

        // Bound allocation for malformed tiny strides or enormous synthetic distances.
        if (previous.distanceMetres / splitMetres > maximumCompleteSplits) return emptyList()

        val splits = ArrayList<Split>()
        var nextBoundaryIndex = 1
        var nextBoundaryMetres = splitMetres
        var lastBoundaryMetres = 0.0
        var lastCrossingMs = 0.0

        for (index in 1 until points.size) {
            val before = points[index - 1]
            val after = points[index]
            if (before.segment != after.segment || after.distanceMetres <= before.distanceMetres) continue

            val spanMetres = after.distanceMetres - before.distanceMetres
            while (nextBoundaryMetres <= after.distanceMetres) {
                val fraction = (nextBoundaryMetres - before.distanceMetres) / spanMetres
                val crossedMs = before.activeElapsedMs.toDouble() +
                    fraction * (after.activeElapsedMs.toDouble() - before.activeElapsedMs.toDouble())
                splits.add(Split(
                    distanceMetres = splitMetres,
                    elapsedSeconds = crossedMs / 1_000.0,
                    durationSeconds = (crossedMs - lastCrossingMs) / 1_000.0,
                    complete = true,
                ))
                lastCrossingMs = crossedMs
                lastBoundaryMetres = nextBoundaryMetres
                // Derive each boundary independently: repeated addition drifts for mile
                // and decimal strides, inventing tails or misclassifying complete splits.
                nextBoundaryIndex += 1
                nextBoundaryMetres = nextBoundaryIndex.toDouble() * splitMetres
            }
        }

        val remainingMetres = previous.distanceMetres - lastBoundaryMetres
        if (remainingMetres > 0.0) {
            val endMs = previous.activeElapsedMs.toDouble()
            splits.add(Split(
                distanceMetres = remainingMetres,
                elapsedSeconds = endMs / 1_000.0,
                durationSeconds = (endMs - lastCrossingMs) / 1_000.0,
                complete = false,
            ))
        }
        return splits
    }
}
