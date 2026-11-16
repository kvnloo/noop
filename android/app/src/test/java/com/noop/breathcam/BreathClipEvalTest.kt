package com.noop.breathcam

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlin.math.abs

/**
 * Offline evaluation of the breath camera on clips the owner records (docs/experiments/BREATH_CAMERA.md).
 * Skipped unless BREATHCAM_CLIPS points at a directory prepared by Tools/breathcam/prepare_clip.py.
 * Runs the SAME [BreathCameraPipeline] the app runs, frame by frame, with the guide schedule as truth, and
 * writes DIR/report.json. No accuracy claim is made anywhere until this has been run on real clips.
 *
 *   BREATHCAM_CLIPS=/path/to/prepared ./gradlew testFullDebugUnitTest --tests '*BreathClipEvalTest*'
 */
class BreathClipEvalTest {

    @Test
    fun evaluateRecordedClips() {
        val dir = System.getenv("BREATHCAM_CLIPS")
        Assume.assumeTrue("set BREATHCAM_CLIPS to a prepared clip directory", !dir.isNullOrBlank())
        val metas = File(dir!!).listFiles { f -> f.name.endsWith(".json") && f.name != "report.json" }
            ?.sortedBy { it.name }.orEmpty()
        Assume.assumeTrue("no prepared clips in $dir", metas.isNotEmpty())
        val clips = JSONArray()
        val all = ArrayList<JSONObject>()
        for (m in metas) {
            val r = evaluate(m)
            clips.put(r)
            all.add(r)
            println(summaryLine(r))
        }
        val report = JSONObject()
            .put("estimator", "com.noop.breathcam.BreathCameraPipeline")
            .put("clips", clips)
            .put("gate", gate(all))
        File(dir, "report.json").writeText(report.toString(1))
        println("gate: " + report.getJSONObject("gate").toString())
    }

    @Test
    fun `evaluator scores a synthetic clip end to end`() {
        // Plumbing check only (no accuracy claim): a rendered clip that follows a short schedule, with the
        // protocol starting 2 s into the clip, must come back well scored through the same file format.
        val dir = java.nio.file.Files.createTempDirectory("breathcam").toFile()
        try {
            val w = 120; val h = 160; val fps = 15.0; val zeroMs = 2_000L
            val sched = JSONArray()
            var t = 0.0
            fun seg(block: String, phase: String, dur: Double, bpm: Double?) {
                sched.put(JSONObject().put("block", block).put("phase", phase).put("start_s", t).put("end_s", t + dur)
                    .put("nominal_bpm", bpm ?: JSONObject.NULL))
                t += dur
            }
            seg("settle", "free", 10.0, null)
            repeat(8) { seg("pace10", "inhale", 3.0, 10.0); seg("pace10", "exhale", 3.0, 10.0) }
            repeat(4) {
                seg("box4", "inhale", 4.0, 3.75); seg("box4", "hold", 4.0, 3.75)
                seg("box4", "exhale", 4.0, 3.75); seg("box4", "hold", 4.0, 3.75)
            }
            val frames = ((t * 1000 + zeroMs) / 1000.0 * fps).toInt()
            val rnd = java.util.Random(4)
            File(dir, "synthetic.gray").outputStream().buffered().use { out ->
                for (i in 0 until frames) {
                    val ps = (i * 1000.0 / fps - zeroMs) / 1000.0
                    val f = BreathSynth.frame(w, h, 1.2 * lungLevel(sched, ps), 0, noiseSd = 2.0, rnd = rnd)
                    out.write(f.data)
                }
            }
            File(dir, "synthetic.json").writeText(
                JSONObject().put("label", "synthetic").put("width", w).put("height", h).put("fps", fps)
                    .put("frames", frames).put("zero_ms", zeroMs).put("schedule", sched).toString(),
            )
            val r = evaluate(File(dir, "synthetic.json"))
            val b = r.getJSONObject("blocks")
            org.junit.Assert.assertEquals(10.0, b.getJSONObject("pace10").getDouble("rate_median_bpm"), 0.5)
            org.junit.Assert.assertTrue(b.getJSONObject("pace10").getDouble("phase_accuracy") >= 0.85)
            org.junit.Assert.assertTrue(b.getJSONObject("box4").getDouble("hold_recall") >= 0.8)
            org.junit.Assert.assertTrue(gate(listOf(r)).has("pass"))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Lung level 0..1 at protocol second [ps] for a schedule (raised-cosine inhale/exhale, flat holds). */
    private fun lungLevel(sched: JSONArray, ps: Double): Double {
        var level = 0.0
        for (k in 0 until sched.length()) {
            val s = sched.getJSONObject(k)
            val a = s.getDouble("start_s"); val e = s.getDouble("end_s")
            val target = when (s.getString("phase")) { "inhale" -> 1.0; "exhale" -> 0.0; else -> level }
            if (ps < a) return level
            if (ps < e) {
                val f = (ps - a) / (e - a)
                return level + (target - level) * (1 - kotlin.math.cos(Math.PI * f)) / 2
            }
            level = target
        }
        return level
    }

    private data class Truth(val phase: BreathPhase?, val block: String, val sinceMs: Long, val nominalBpm: Double?)

    private fun evaluate(metaFile: File): JSONObject {
        val meta = JSONObject(metaFile.readText())
        val w = meta.getInt("width"); val h = meta.getInt("height"); val fps = meta.getDouble("fps")
        val zero = meta.getLong("zero_ms")
        val sched = meta.getJSONArray("schedule")
        val gray = File(metaFile.parentFile, meta.getString("label") + ".gray")
        val pipeline = BreathCameraPipeline()
        val check = PacerPolarityCheck()
        var flips = 0
        val truth = ArrayList<Truth>()
        val est = ArrayList<BreathEstimate>()
        val frame = ByteArray(w * h)
        BufferedInputStream(FileInputStream(gray), 1 shl 20).use { input ->
            var i = 0
            while (input.readNBytes(frame, 0, frame.size) == frame.size) {
                val tMs = (i * 1000.0 / fps).toLong()
                val t = truthAt(sched, (tMs - zero) / 1000.0)
                truth.add(t)
                val e = pipeline.onFrame(LumaFrame(frame, w, h), 0, tMs)
                est.add(e)
                val pacer = when (t.phase) { BreathPhase.INHALE -> 1; BreathPhase.EXHALE -> -1; else -> 0 }
                val cam = when (e.phase) { BreathPhase.INHALE -> 1; BreathPhase.EXHALE -> -1; else -> 0 }
                check.offer(pacer, cam)
                if (check.verdict() == -1) {
                    pipeline.estimator.polarity = -pipeline.estimator.polarity
                    check.reset(); flips++
                }
                i++
            }
        }
        val tOf = { i: Int -> (i * 1000.0 / fps).toLong() - zero }
        val blocks = JSONObject()
        for (block in truth.map { it.block }.distinct().filter { it != "settle" && it.isNotEmpty() }) {
            val idx = truth.indices.filter { truth[it].block == block }
            if (idx.isEmpty()) continue
            var n = 0; var ok = 0; var known = 0
            val confusion = HashMap<String, Int>()
            for (i in idx) {
                val tr = truth[i]
                val grace = if (tr.phase == BreathPhase.HOLD) HOLD_GRACE_MS else GRACE_MS
                if (tOf(i) - tr.sinceMs < grace) continue
                n++
                if (est[i].phase != BreathPhase.UNKNOWN) known++
                if (est[i].phase == tr.phase) ok++
                val key = "${tr.phase}->${est[i].phase}"
                confusion[key] = (confusion[key] ?: 0) + 1
            }
            val tail = idx.drop((idx.size * 0.4).toInt())
            val rates = tail.mapNotNull { est[it].rateBpm }.sorted()
            val nominal = truth[idx.first()].nominalBpm
            val holdTruth = idx.filter { truth[it].phase == BreathPhase.HOLD && tOf(it) - truth[it].sinceMs >= HOLD_GRACE_MS }
            val holdPred = idx.filter { est[it].phase == BreathPhase.HOLD }
            val o = JSONObject()
                .put("nominal_bpm", nominal)
                .put("frames_scored", n)
                .put("phase_accuracy", if (n > 0) ok.toDouble() / n else JSONObject.NULL)
                .put("coverage", if (n > 0) known.toDouble() / n else JSONObject.NULL)
                .put("rate_median_bpm", if (rates.isNotEmpty()) rates[rates.size / 2] else JSONObject.NULL)
                .put("rate_abs_err_bpm", if (rates.isNotEmpty() && nominal != null) abs(rates[rates.size / 2] - nominal) else JSONObject.NULL)
                .put("rate_coverage", if (tail.isNotEmpty()) rates.size.toDouble() / tail.size else 0.0)
                .put("median_flip_latency_ms", medianLatency(idx, truth, est, tOf) ?: JSONObject.NULL)
                .put("confusion", JSONObject(confusion as Map<*, *>))
            if (holdTruth.isNotEmpty()) {
                o.put("hold_recall", holdTruth.count { est[it].phase == BreathPhase.HOLD }.toDouble() / holdTruth.size)
                o.put("hold_precision", if (holdPred.isEmpty()) JSONObject.NULL
                    else holdPred.count { truth[it].phase == BreathPhase.HOLD }.toDouble() / holdPred.size)
            }
            blocks.put(block, o)
        }
        return JSONObject()
            .put("label", meta.getString("label"))
            .put("frames", est.size)
            .put("polarity_flips", flips)
            .put("blocks", blocks)
    }

    private fun medianLatency(idx: List<Int>, truth: List<Truth>, est: List<BreathEstimate>, tOf: (Int) -> Long): Long? {
        val lat = ArrayList<Long>()
        for (k in 1 until idx.size) {
            val i = idx[k]
            val p = truth[i].phase ?: continue
            if (p == truth[idx[k - 1]].phase || p == BreathPhase.HOLD) continue
            val hit = (i until minOf(est.size, i + 60)).firstOrNull { est[it].phase == p } ?: continue
            lat.add(tOf(hit) - tOf(i))
        }
        return lat.sorted().getOrNull(lat.size / 2)
    }

    private fun truthAt(sched: JSONArray, tS: Double): Truth {
        for (k in 0 until sched.length()) {
            val s = sched.getJSONObject(k)
            if (tS >= s.getDouble("start_s") && tS < s.getDouble("end_s")) {
                val phase = when (s.getString("phase")) {
                    "inhale" -> BreathPhase.INHALE
                    "exhale" -> BreathPhase.EXHALE
                    "hold" -> BreathPhase.HOLD
                    else -> null
                }
                val nom = if (s.isNull("nominal_bpm")) null else s.getDouble("nominal_bpm")
                return Truth(phase, s.getString("block"), (s.getDouble("start_s") * 1000).toLong(), nom)
            }
        }
        return Truth(null, "", 0, null)
    }

    /** The bar in BREATH_CAMERA.md: every paced block, every clip. */
    private fun gate(clips: List<JSONObject>): JSONObject {
        var rateOk = true; var phaseOk = true; var holdOk = true
        for (c in clips) {
            val b = c.getJSONObject("blocks")
            for (name in b.keys()) {
                val o = b.getJSONObject(name)
                if (!o.isNull("rate_abs_err_bpm") && o.getDouble("rate_abs_err_bpm") > GATE_RATE_ERR) rateOk = false
                if (o.isNull("rate_abs_err_bpm")) rateOk = false
                if (o.isNull("phase_accuracy") || o.getDouble("phase_accuracy") < GATE_PHASE_ACC) phaseOk = false
                if (o.isNull("coverage") || o.getDouble("coverage") < GATE_COVERAGE) phaseOk = false
                if (o.has("hold_recall") && (o.getDouble("hold_recall") < GATE_HOLD ||
                        o.isNull("hold_precision") || o.getDouble("hold_precision") < GATE_HOLD)) holdOk = false
            }
        }
        return JSONObject()
            .put("clips", clips.size)
            .put("rate_ok", rateOk).put("phase_ok", phaseOk).put("hold_ok", holdOk)
            .put("enough_clips", clips.size >= GATE_MIN_CLIPS)
            .put("pass", rateOk && phaseOk && holdOk && clips.size >= GATE_MIN_CLIPS)
    }

    private fun summaryLine(r: JSONObject): String {
        val b = r.getJSONObject("blocks")
        val parts = b.keys().asSequence().map { k ->
            val o = b.getJSONObject(k)
            val acc = if (o.isNull("phase_accuracy")) "-" else String.format(Locale.US, "%.0f%%", 100 * o.getDouble("phase_accuracy"))
            val err = if (o.isNull("rate_abs_err_bpm")) "-" else String.format(Locale.US, "%.2f", o.getDouble("rate_abs_err_bpm"))
            "$k acc=$acc rateErr=$err"
        }.joinToString("  ")
        return "${r.getString("label")}: $parts"
    }

    companion object {
        const val GRACE_MS = 750L
        const val HOLD_GRACE_MS = 1_500L
        const val GATE_RATE_ERR = 1.0
        const val GATE_PHASE_ACC = 0.85
        const val GATE_COVERAGE = 0.80
        const val GATE_HOLD = 0.80
        const val GATE_MIN_CLIPS = 6
    }
}
