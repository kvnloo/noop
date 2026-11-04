package com.noop.agent

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The only database seam the agent reader needs. Android wraps Room; JVM tests wrap sqlite-jdbc. */
fun interface AgentSql {
    fun rows(sql: String, args: List<Any>): List<Map<String, Any?>>
}

/**
 * Bounded, minimized reads behind the agent tools. Tool names and argument names follow the desktop
 * `NoopLocalAccess` contract (health_snapshot, metric_series, data_freshness, sleep_summary,
 * workout_summary), but the phone answers with aggregates by default and per-row values only when the
 * grant carries [AgentScope.READ_DETAIL].
 *
 * Never returned: device ids/serials/MACs, free text (journal, workout notes, coach messages), GPS
 * routes, raw sample streams, sleep stage JSON. Sources are labelled by kind only.
 */
class AgentHealthReader(
    private val sql: AgentSql,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private fun today(): LocalDate = Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate()

    private fun dayRange(days: Int): Pair<String, String> {
        val to = today()
        return to.minusDays((days - 1).toLong()).toString() to to.toString()
    }

    /** Per day, keep the most complete row across sources; ties prefer NOOP-computed rows. */
    internal fun dailyRows(days: Int): List<Map<String, Any?>> {
        val (from, to) = dayRange(days)
        val cols = METRICS.values.joinToString { it.column }
        val rows = sql.rows(
            "SELECT deviceId, day, $cols FROM dailyMetric WHERE day BETWEEN ? AND ? ORDER BY day LIMIT 2000",
            listOf(from, to),
        )
        return rows.groupBy { it["day"] as String }.toSortedMap().map { (_, candidates) ->
            candidates.maxWith(
                compareBy<Map<String, Any?>>({ row -> METRICS.values.count { row[it.column] != null } })
                    .thenBy { if ((it["deviceId"] as? String).orEmpty().endsWith("-noop")) 1 else 0 },
            )
        }
    }

    fun healthSnapshot(days: Int, detail: Boolean): JSONObject {
        val rows = dailyRows(days)
        val metrics = JSONObject()
        for ((key, metric) in METRICS) {
            val series = rows.mapNotNull { row -> num(row[metric.column])?.let { row["day"] as String to it } }
            if (series.isNotEmpty()) metrics.put(key, stats(series, metric))
        }
        val out = JSONObject()
            .put("window", window(days))
            .put("granularity", if (detail) "daily" else "summary")
            .put("daysWithData", rows.size)
            .put("sources", sourceKinds(rows.mapNotNull { it["deviceId"] as? String }))
            .put("metrics", metrics)
            .put("freshness", dataFreshness().optJSONObject("latest"))
            .put("note", NOT_MEDICAL)
        if (detail) {
            out.put("daily", JSONArray(rows.map { row ->
                JSONObject().put("day", row["day"]).also { o ->
                    for ((key, metric) in METRICS) num(row[metric.column])?.let { o.put(key, round(it, metric.decimals)) }
                }
            }))
        }
        return out
    }

    fun metricSeries(key: String, days: Int, detail: Boolean): JSONObject {
        val metric = METRICS[key] ?: throw AgentToolError("unknown key; use one of ${METRICS.keys.joinToString()}")
        val series = dailyRows(days).mapNotNull { row -> num(row[metric.column])?.let { row["day"] as String to it } }
        val out = JSONObject()
            .put("key", key)
            .put("unit", metric.unit)
            .put("window", window(days))
            .put("granularity", if (detail) "daily" else "summary")
            .put("returned", if (detail) series.size else 0)
        if (series.isNotEmpty()) out.put("summary", stats(series, metric))
        if (detail) out.put("points", JSONArray(series.map { (day, v) -> JSONObject().put("day", day).put("value", round(v, metric.decimals)) }))
        return out
    }

    fun dataFreshness(): JSONObject {
        val now = nowMs() / 1000
        fun one(sqlText: String, args: List<Any> = emptyList()): Long? =
            sql.rows(sqlText, args).firstOrNull()?.values?.firstOrNull()?.let(::num)?.toLong()
        val hr = one("SELECT MAX(ts) AS v FROM hrSample")?.let(::seconds)
        val sleep = one("SELECT MAX(endTs) AS v FROM sleepSession")
        val workout = one("SELECT MAX(startTs) AS v FROM workout")
        val dailyDay = sql.rows("SELECT MAX(day) AS v FROM dailyMetric", emptyList()).firstOrNull()?.get("v") as? String
        val (from, to) = dayRange(30)
        val covered = one("SELECT COUNT(DISTINCT day) AS v FROM dailyMetric WHERE day BETWEEN ? AND ?", listOf(from, to)) ?: 0
        fun age(ts: Long?) = ts?.let { ((now - it).coerceAtLeast(0) / 360.0).roundToInt() / 10.0 }
        val latest = JSONObject()
            .put("heartRateSampleAgeHours", age(hr) ?: JSONObject.NULL)
            .put("sleepSessionEndAgeHours", age(sleep) ?: JSONObject.NULL)
            .put("workoutStartAgeHours", age(workout) ?: JSONObject.NULL)
            .put("latestDailyMetricDay", dailyDay ?: JSONObject.NULL)
        return JSONObject()
            .put("generatedAt", Instant.ofEpochSecond(now).toString())
            .put("latest", latest)
            .put("dailyCoverageLast30Days", covered)
            .put("stale", hr == null || now - hr > 36 * 3600)
    }

    fun sleepSummary(days: Int, detail: Boolean): JSONObject {
        val endFrom = today().minusDays((days - 1).toLong()).atStartOfDay(zone).toEpochSecond()
        val rows = sql.rows(
            "SELECT deviceId, startTs, startTsAdjusted, endTs, efficiency FROM sleepSession " +
                "WHERE endTs >= ? ORDER BY endTs LIMIT 1000",
            listOf(endFrom),
        )
        val nights = dedupeIntervals(rows.map {
            Interval(num(it["startTsAdjusted"])?.toLong() ?: num(it["startTs"])!!.toLong(), num(it["endTs"])!!.toLong(), num(it["efficiency"]))
        })
        val main = nights.filter { it.end - it.start >= 3 * 3600 }
        val naps = nights.size - main.size
        val durations = main.map { (it.end - it.start) / 60.0 }
        val bedMinutes = main.map { minuteOfEvening(it.start) }
        val wakeMinutes = main.map { minuteOfDay(it.end) }
        val out = JSONObject()
            .put("window", window(days))
            .put("granularity", if (detail) "nightly" else "summary")
            .put("nights", main.size)
            .put("naps", naps)
        if (main.isNotEmpty()) {
            out.put("durationMin", JSONObject().put("mean", round(durations.average(), 0)).put("min", round(durations.min(), 0)).put("max", round(durations.max(), 0)))
            main.mapNotNull { it.efficiency }.takeIf { it.isNotEmpty() }?.let { out.put("efficiencyMean", round(it.average(), 1)) }
            out.put("medianBedtime", clock(median(bedMinutes) - 12 * 60))
            out.put("medianWake", clock(median(wakeMinutes)))
            out.put("bedtimeSpreadMin", round(stddev(bedMinutes), 0))
        }
        if (detail) out.put("sessions", JSONArray(main.takeLast(MAX_ROWS).map {
            JSONObject().put("wakeDay", localDay(it.end)).put("bed", clock(minuteOfDay(it.start))).put("wake", clock(minuteOfDay(it.end)))
                .put("durationMin", round((it.end - it.start) / 60.0, 0)).also { o -> it.efficiency?.let { e -> o.put("efficiency", round(e, 1)) } }
        }))
        return out.put("note", NOT_MEDICAL)
    }

    fun workoutSummary(days: Int, detail: Boolean): JSONObject {
        val from = today().minusDays((days - 1).toLong()).atStartOfDay(zone).toEpochSecond()
        val raw = sql.rows(
            "SELECT sport, startTs, endTs, durationS, avgHr, strain FROM workout WHERE startTs >= ? ORDER BY startTs LIMIT 1000",
            listOf(from),
        )
        // The same session can arrive from the strap and an import; keep one per sport per minute.
        val rows = raw.distinctBy { "${it["sport"]}|${num(it["startTs"])!!.toLong() / 60}" }
        fun minutes(r: Map<String, Any?>) = num(r["durationS"])?.div(60.0) ?: ((num(r["endTs"])!! - num(r["startTs"])!!) / 60.0).coerceAtLeast(0.0)
        val bySport = JSONObject()
        rows.groupBy { sportLabel(it["sport"] as? String) }.toSortedMap().forEach { (sport, list) ->
            bySport.put(sport, JSONObject().put("count", list.size).put("minutes", round(list.sumOf(::minutes), 0)))
        }
        val out = JSONObject()
            .put("window", window(days))
            .put("granularity", if (detail) "per_workout" else "summary")
            .put("count", rows.size)
            .put("totalMinutes", round(rows.sumOf(::minutes), 0))
            .put("bySport", bySport)
        rows.mapNotNull { num(it["strain"]) }.takeIf { it.isNotEmpty() }?.let { out.put("strainMean", round(it.average(), 1)) }
        if (detail) out.put("workouts", JSONArray(rows.takeLast(MAX_ROWS).map { r ->
            JSONObject().put("day", localDay(num(r["startTs"])!!.toLong())).put("sport", sportLabel(r["sport"] as? String))
                .put("minutes", round(minutes(r), 0)).also { o ->
                    num(r["avgHr"])?.let { o.put("avgHr", it.roundToInt()) }
                    num(r["strain"])?.let { o.put("strain", round(it, 1)) }
                }
        }))
        return out
    }

    // ---- helpers -------------------------------------------------------------------------------

    private data class Interval(val start: Long, val end: Long, val efficiency: Double?)

    /** Strap, computed and imported copies of one night overlap; keep the longest of each overlap set. */
    private fun dedupeIntervals(all: List<Interval>): List<Interval> {
        val kept = mutableListOf<Interval>()
        for (cur in all.filter { it.end > it.start }.sortedByDescending { it.end - it.start }) {
            val overlaps = kept.any { k -> minOf(k.end, cur.end) - maxOf(k.start, cur.start) > (cur.end - cur.start) / 2 }
            if (!overlaps) kept += cur
        }
        return kept.sortedBy { it.start }
    }

    private fun stats(series: List<Pair<String, Double>>, metric: Metric): JSONObject {
        val values = series.map { it.second }
        val o = JSONObject()
            .put("unit", metric.unit)
            .put("n", values.size)
            .put("mean", round(values.average(), metric.decimals))
            .put("min", round(values.min(), metric.decimals))
            .put("max", round(values.max(), metric.decimals))
            .put("latest", round(values.last(), metric.decimals))
            .put("latestDay", series.last().first)
        if (values.size >= 6) {
            val recent = values.takeLast(3).average()
            val prior = values.dropLast(3).average()
            o.put("recent3VsPriorMean", round(recent - prior, metric.decimals))
        }
        return o
    }

    private fun window(days: Int): JSONObject = dayRange(days).let { (f, t) -> JSONObject().put("from", f).put("to", t).put("days", days) }
    private fun seconds(ts: Long): Long = if (ts > 100_000_000_000L) ts / 1000 else ts
    private fun localDay(ts: Long): String = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().toString()
    private fun minuteOfDay(ts: Long): Int = Instant.ofEpochSecond(ts).atZone(zone).let { it.hour * 60 + it.minute }
    /** Minutes since noon, so 23:30 and 00:30 bedtimes average to midnight instead of noon. */
    private fun minuteOfEvening(ts: Long): Int = (minuteOfDay(ts) + 12 * 60) % (24 * 60)
    private fun clock(minute: Int): String = "%02d:%02d".format(((minute % 1440) + 1440) % 1440 / 60, ((minute % 60) + 60) % 60)
    private fun median(values: List<Int>): Int = values.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
    private fun stddev(values: List<Int>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    companion object {
        const val MAX_ROWS = 62
        const val NOT_MEDICAL = "Wearable estimates for self-reflection, not medical advice or diagnosis."

        data class Metric(val column: String, val unit: String, val decimals: Int)

        /** Closed key set (desktop metric catalog names) mapped to dailyMetric columns. */
        val METRICS: Map<String, Metric> = linkedMapOf(
            "recovery" to Metric("recovery", "%", 0),
            "hrv" to Metric("avgHrv", "ms", 0),
            "rhr" to Metric("restingHr", "bpm", 0),
            "sleep_total_min" to Metric("totalSleepMin", "min", 0),
            "sleep_efficiency" to Metric("efficiency", "%", 0),
            "sleep_deep_min" to Metric("deepMin", "min", 0),
            "sleep_rem_min" to Metric("remMin", "min", 0),
            "strain" to Metric("strain", "score", 1),
            "resp_rate" to Metric("respRateBpm", "breaths/min", 1),
            "spo2" to Metric("spo2Pct", "%", 1),
            "skin_temp" to Metric("skinTempDevC", "°C dev", 2),
            "steps" to Metric("steps", "steps", 0),
        )

        fun num(value: Any?): Double? = when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() }
            is String -> value.toDoubleOrNull()?.takeIf { it.isFinite() }
            else -> null
        }

        fun round(value: Double, decimals: Int): Double {
            var f = 1.0
            repeat(decimals) { f *= 10 }
            return (value * f).roundToInt() / f
        }

        /** Label a source by kind only. Raw device ids embed serials/MACs and never leave the phone. */
        fun sourceKind(deviceId: String): String {
            val id = deviceId.lowercase()
            val base = when {
                "apple" in id -> "apple-health"
                "health-connect" in id || id.startsWith("hc") -> "health-connect"
                "oura" in id -> "oura"
                "polar" in id -> "polar"
                "whoop" in id -> "whoop"
                else -> "wearable"
            }
            return if (id.endsWith("-noop")) "$base (NOOP-computed)" else base
        }

        fun sourceKinds(ids: List<String>): JSONArray = JSONArray(ids.map(::sourceKind).distinct().sorted())

        fun sportLabel(raw: String?): String {
            val s = raw.orEmpty().lowercase().replace(Regex("[^a-z0-9 _-]"), "").take(32)
            return s.ifBlank { "other" }
        }
    }
}
