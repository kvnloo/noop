/// Pure workout split interpolation. The caller supplies cumulative moving distance in metres
/// and pause-excluding active elapsed milliseconds; no GPS, database, or UI dependency.
///
/// A future adapter for ryanbr/noop#2611 can project its timed route to these points by summing
/// Haversine legs only within each pause segment. Missing active elapsed time must not be guessed.
enum GpsSplitMath {
    struct Point: Equatable {
        let distanceMetres: Double
        let activeElapsedMs: Int64
        let segment: Int

        init(distanceMetres: Double, activeElapsedMs: Int64, segment: Int = 0) {
            self.distanceMetres = distanceMetres
            self.activeElapsedMs = activeElapsedMs
            self.segment = segment
        }
    }

    struct Split: Equatable {
        let distanceMetres: Double
        let elapsedSeconds: Double
        let durationSeconds: Double
        let complete: Bool
    }

    private static let maximumCompleteSplits = 10_000.0

    /// Calculates complete, fixed-distance splits and an optional final partial split.
    /// Invalid or out-of-order input yields no splits, never a believable-looking prefix.
    /// An unchanged-distance segment seam is permitted; moving across a pause seam is not.
    static func calculate(points: [Point], splitMetres: Double) -> [Split] {
        guard points.count >= 2, splitMetres.isFinite, splitMetres > 0 else { return [] }
        let first = points[0]
        guard first.distanceMetres.isFinite, first.distanceMetres == 0,
              first.activeElapsedMs >= 0, first.segment >= 0 else { return [] }

        var previous = first
        for current in points.dropFirst() {
            guard current.distanceMetres.isFinite,
                  current.distanceMetres >= previous.distanceMetres,
                  current.activeElapsedMs >= previous.activeElapsedMs,
                  current.segment >= previous.segment else { return [] }
            if current.segment != previous.segment {
                guard current.distanceMetres == previous.distanceMetres else { return [] }
            } else if current.distanceMetres > previous.distanceMetres {
                guard current.activeElapsedMs > previous.activeElapsedMs else { return [] }
            }
            previous = current
        }

        // Bound allocation for malformed tiny strides or enormous synthetic distances.
        guard previous.distanceMetres / splitMetres <= maximumCompleteSplits else { return [] }

        var splits: [Split] = []
        var nextBoundaryMetres = splitMetres
        var lastBoundaryMetres = 0.0
        var lastCrossingMs = 0.0

        for index in 1..<points.count {
            let before = points[index - 1]
            let after = points[index]
            guard before.segment == after.segment,
                  after.distanceMetres > before.distanceMetres else { continue }

            let spanMetres = after.distanceMetres - before.distanceMetres
            while nextBoundaryMetres <= after.distanceMetres {
                let fraction = (nextBoundaryMetres - before.distanceMetres) / spanMetres
                let crossedMs = Double(before.activeElapsedMs)
                    + fraction * (Double(after.activeElapsedMs) - Double(before.activeElapsedMs))
                splits.append(Split(distanceMetres: splitMetres,
                                    elapsedSeconds: crossedMs / 1_000,
                                    durationSeconds: (crossedMs - lastCrossingMs) / 1_000,
                                    complete: true))
                lastCrossingMs = crossedMs
                lastBoundaryMetres = nextBoundaryMetres
                nextBoundaryMetres += splitMetres
            }
        }

        let remainingMetres = previous.distanceMetres - lastBoundaryMetres
        if remainingMetres > 0 {
            let endMs = Double(previous.activeElapsedMs)
            splits.append(Split(distanceMetres: remainingMetres,
                                elapsedSeconds: endMs / 1_000,
                                durationSeconds: (endMs - lastCrossingMs) / 1_000,
                                complete: false))
        }
        return splits
    }
}
