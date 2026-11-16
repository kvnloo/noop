import Foundation

/// Habit follow-through comes from explicit Journal Yes answers, never from the
/// availability of a biometric outcome sample. Dates are local-day keys.
enum ExperimentAdherence {
    static func fraction(
        yesDays: Set<String>, startedDay: String, today: String, durationDays: Int
    ) -> Double {
        guard let start = dayNumber(startedDay), let end = dayNumber(today), end >= start else {
            return 0
        }
        let elapsed = min(max(1, durationDays), end - start + 1)
        let completed = yesDays.compactMap(dayNumber).filter { day in
            day >= start && day - start < elapsed
        }.count
        return Double(completed) / Double(elapsed)
    }

    /// The experiment measures outcomes in its *calendar* window, not the next N
    /// available sensor days, which may arrive weeks after the plan ended.
    static func includes(_ day: String, startedDay: String, today: String, durationDays: Int) -> Bool {
        guard let start = dayNumber(startedDay), let end = dayNumber(today),
              let observed = dayNumber(day) else { return false }
        return observed >= start && observed <= end && observed - start < max(1, durationDays)
    }

    private static func dayNumber(_ key: String) -> Int? {
        // Numeric parsing accepts signed components such as +1. Reject aliases before
        // counting Set<String> members, or one civil day can produce >100% adherence.
        let bytes = Array(key.utf8)
        guard bytes.count == 10, bytes[4] == 45, bytes[7] == 45,
              (0..<10).allSatisfy({ i in i == 4 || i == 7 || (48...57).contains(bytes[i]) }) else {
            return nil
        }
        let parts = key.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2,
              parts[2].count == 2, let year = Int(parts[0]), let month = Int(parts[1]),
              let day = Int(parts[2]), year > 0 else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let date = calendar.date(from: DateComponents(year: year, month: month, day: day)) else {
            return nil
        }
        let resolved = calendar.dateComponents([.year, .month, .day], from: date)
        guard resolved.year == year, resolved.month == month, resolved.day == day else { return nil }
        return calendar.ordinality(of: .day, in: .era, for: date)
    }
}
