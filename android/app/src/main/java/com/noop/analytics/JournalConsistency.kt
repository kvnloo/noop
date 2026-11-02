package com.noop.analytics

/**
 * Read-only counts and Yes-streaks for one canonical Journal question.
 *
 * Missing answers are not No, and a Yes is not necessarily a "good" habit
 * (e.g. "Did you drink alcohol?"). The caller supplies the merged Journal rows
 * for one question with its local wake-day keys. No scoring or storage writes.
 * Mirrors StrandAnalytics/JournalConsistency.swift.
 */
object JournalConsistency {
    data class Summary(
        val windowDays: Int,
        val yesDays: Int,
        val noDays: Int,
        val unloggedDays: Int,
        val currentYesStreak: Int,
        val longestYesStreak: Int,
        val todayAnswer: Boolean?,
    )

    // Baselines.isoEpochDay intentionally accepts day 32. Imported Journal keys
    // must be canonical dates so impossible dates cannot become false evidence.
    // Keep this calendar gate value-for-value with the Swift twin.
    /** Swift twin: `JournalConsistency.canonicalEpochDay`. */
    private fun canonicalEpochDay(iso: String): Int? {
        if (iso.length != 10 || iso[4] != '-' || iso[7] != '-' ||
            iso.indices.any { i -> i != 4 && i != 7 && iso[i] !in '0'..'9' }) return null
        val year = iso.substring(0, 4).toInt()
        val month = iso.substring(5, 7).toInt()
        val day = iso.substring(8, 10).toInt()
        if (month !in 1..12) return null
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val lengths = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (day !in 1..lengths[month - 1]) return null
        return Baselines.isoEpochDay(iso)
    }

    /**
     * Later duplicate rows win; tomorrow/future rows do not count today.
     * A missing today preserves yesterday's Yes streak, but an explicit No
     * ends the current streak. Window clamps to 1..90 local civil days.
     * Swift twin: `JournalConsistency.summarize`.
     */
    fun summarize(
        dayKeys: List<String>,
        answeredYes: List<Boolean>,
        today: String,
        windowDays: Int = 7,
    ): Summary? {
        val t = canonicalEpochDay(today) ?: return null
        val window = windowDays.coerceIn(1, 90)
        val answers = HashMap<Int, Boolean>()
        for (i in 0 until minOf(dayKeys.size, answeredYes.size)) {
            val d = canonicalEpochDay(dayKeys[i]) ?: continue
            if (d <= t) answers[d] = answeredYes[i]
        }

        val start = t - (window - 1)
        val yes = answers.count { (day, answer) -> day >= start && answer }
        val no = answers.count { (day, answer) -> day >= start && !answer }
        val yesDays = answers.filterValues { it }.keys

        var longest = 0
        for (day in yesDays) {
            if (day - 1 !in yesDays) {
                var length = 1
                while (day + length in yesDays) length++
                longest = maxOf(longest, length)
            }
        }

        val anchor = when {
            answers[t] == true -> t
            answers[t] == null && answers[t - 1] == true -> t - 1
            else -> null
        }
        var current = 0
        if (anchor != null) {
            current = 1
            while (anchor - current in yesDays) current++
        }
        return Summary(
            windowDays = window,
            yesDays = yes,
            noDays = no,
            unloggedDays = window - yes - no,
            currentYesStreak = current,
            longestYesStreak = longest,
            todayAnswer = answers[t],
        )
    }
}
