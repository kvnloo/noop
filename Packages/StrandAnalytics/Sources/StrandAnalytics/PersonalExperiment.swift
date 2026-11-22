/// Pure, same-day comparison for the Personal Experiment card.
public enum PersonalExperiment {
    public struct Snapshot {
        public let durationDays: Int
        public let daysElapsed: Int
        public let baselineMean: Double?
        public let baselineCount: Int
        public let interventionMean: Double?
        public let interventionCount: Int
        public let loggedToday: Bool
        /// 0…100 percent.
        public let compliance: Double
        public let confidence: Confidence

        public var delta: Double? {
            guard let interventionMean, let baselineMean else { return nil }
            return interventionMean - baselineMean
        }
    }

    public enum Confidence {
        case low, early, stronger
    }

    /// Day keys are yyyy-MM-dd; daysSinceStart excludes the start day.
    public static func snapshot(
        outcomeDays: [String: Double],
        loggedDays: Set<String>,
        controlDays: Set<String>,
        startedDay: String,
        today: String,
        daysSinceStart: Int,
        durationDays: Int,
        baselineDays: Int
    ) -> Snapshot {
        let duration = max(1, durationDays)
        // Use the same explicit No controls as Behaviour Effects, before the experiment only.
        let baseline = outcomeDays.keys
            .filter { $0 < startedDay && controlDays.contains($0) }
            .sorted()
            .suffix(max(1, baselineDays))
            .compactMap { outcomeDays[$0] }
        // The first duration outcome days form the window; only Yes days contribute.
        let intervention = outcomeDays.keys
            .filter { $0 >= startedDay && $0 <= today }
            .sorted()
            .prefix(duration)
            .filter { loggedDays.contains($0) }
            .compactMap { outcomeDays[$0] }

        let daysElapsed = max(1, min(duration, daysSinceStart + 1))
        let compliance = Double(intervention.count) / Double(daysElapsed)
        let paired = min(baseline.count, intervention.count)
        let confidence: Confidence
        if paired >= 10 && compliance >= 0.65 {
            confidence = .stronger
        } else if paired >= 5 {
            confidence = .early
        } else {
            confidence = .low
        }
        return Snapshot(
            durationDays: duration,
            daysElapsed: daysElapsed,
            baselineMean: mean(baseline),
            baselineCount: baseline.count,
            interventionMean: mean(intervention),
            interventionCount: intervention.count,
            loggedToday: loggedDays.contains(today),
            compliance: compliance * 100,
            confidence: confidence
        )
    }

    private static func mean(_ values: [Double]) -> Double? {
        values.isEmpty ? nil : values.reduce(0, +) / Double(values.count)
    }
}
