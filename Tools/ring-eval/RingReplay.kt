import com.noop.ble.gesture.OuraTapDetector
import java.io.File

private fun emit(name: String, b: OuraTapDetector.Burst) {
    println(listOf(File(name).name, b.atMs, b.peak, b.quietLevel, b.impacts,
        b.firstSpacingMs, b.durationMs, b.verdict.name, b.gesture?.name ?: "-", b.stillness, b.lastImpactMs).joinToString("\t"))
}

/** CLI adapter: replays production Kotlin, never a translated Python approximation. */
fun main(args: Array<String>) {
    require(args.size >= 2) { "Usage: RingReplay <old|new|candidate|off> CSV..." }
    val mode = args[0]
    val gate = if (mode == "off") 0 else 60
    for (name in args.drop(1)) {
        var last = -1L
        val detector = if (mode == "new") OuraTapDetector(observer = { b ->
            emit(name, b)
        }) else OuraTapDetector(maxStillness = gate,
            quietAfterSamples = if (mode == "candidate") 23 else 12,
            minImpactSpacingSamples = if (mode == "candidate") 6 else 7,
            maxImpactSpacingSamples = if (mode == "candidate") 22 else 17,
            movingPeakMultiplier = if (mode == "candidate") 6 else 0, observer = { b ->
            emit(name, b)
        })
        val exactSamples = File(name).useLines { lines -> lines.any { it.startsWith("s,") } }
        File(name).forEachLine { line ->
            val v = line.split(',')
            if (v.size == 6 && v[0] == "s") {
                last = v[1].toLong()
                detector.sample(last, v[3].toInt(), v[4].toInt(), v[5].toInt())
            }
            if (!exactSamples && v.size >= 6 && v[0] == "f") {
                val arrival = v[1].toLong()
                for (i in 3 until v.size - 2 step 3) {
                    val stamp = maxOf(arrival, last + 20)
                    detector.sample(stamp, v[i].toInt(), v[i+1].toInt(), v[i+2].toInt())
                    last = stamp
                }
            }
        }
        if (last >= 0) detector.flush(last + 500)
    }
}
