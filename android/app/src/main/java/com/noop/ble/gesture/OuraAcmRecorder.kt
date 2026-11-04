package com.noop.ble.gesture

import java.io.BufferedWriter
import java.io.File
import java.io.Writer

/**
 * Debug-only raw motion recorder for tuning [OuraTapDetector]. It is off by default: it exists only
 * while the user has turned recording on in the gesture setup screen AND armed one bounded capture.
 * It writes to app-private storage, never to the network, and holds accelerometer bytes only (no key,
 * serial, address or health data).
 *
 * One CSV per capture. Times are the phone's monotonic clock (`elapsedRealtime`, ms) at arrival.
 *   `# noop-oura-acm v2 label=<label> gen=<generation>`
 *   `f,<ms>,<raw hex of the whole 0x33 frame>,<x1>,<y1>,<z1>[,<x2>,<y2>,<z2>]` (no axes = codec rejected it)
 *   `s,<sample ms>,<phone elapsed ms>,<x>,<y>,<z>` exact ordered detector inputs and label clock mapping
 *   `m,<ms>,<label>`   a ground-truth mark (the user pressed MARK / volume-down)
 *   `g,<ms>,<gesture>` what the current detector emitted
 *   `b,<ms>,<peak>,<quiet level>,<impacts>,<first spacing ms or -1>,<duration ms>,<verdict>,<gesture or ->,<stillness>,<last impact ms or -1>`
 *      one detector burst ([OuraTapDetector.Burst]); `<ms>` is the detector's sample clock
 *   `k,<ms>,<start|end|abort>,<block index>,<block id>,<kind>,<taps>,<intensity>,<reps>,<period ms>,<duration ms>`
 *      a guided-recording block boundary ([OuraGuidedPlan]); `start` is when the first cue fires
 *   `c,<ms>,<block index>,<rep>` the cue (beep) for one requested repetition inside a TAPS block
 * (v1 files carried `p,...` rows from the earlier gravity-magnitude detector instead of `b,...`.)
 *
 * Thread-safe: frames arrive on the GATT binder thread, marks on main.
 */
class OuraAcmRecorder(private val out: Writer, label: String, generation: String) {
    @Volatile var frames = 0
        private set
    var undecoded = 0
        private set
    var marks = 0
        private set
    private var closed = false

    init {
        out.write("# noop-oura-acm v2 label=${clean(label)} gen=${clean(generation)}\n")
    }

    @Synchronized
    fun frame(elapsedMs: Long, wire: ByteArray, axes: List<OuraAcmCodec.Axes>) {
        if (closed) return
        frames++
        if (axes.isEmpty()) undecoded++
        val sb = StringBuilder(64)
        sb.append("f,").append(elapsedMs).append(',')
        for (b in wire) sb.append("%02x".format(b.toInt() and 0xff))
        for (a in axes) sb.append(',').append(a.x).append(',').append(a.y).append(',').append(a.z)
        out.write(sb.append('\n').toString())
    }

    /** Exact detector input clock, plus phone monotonic time for voice-label alignment. */
    @Synchronized
    fun sample(sampleMs: Long, elapsedMs: Long, x: Int, y: Int, z: Int) {
        if (!closed) out.write("s,$sampleMs,$elapsedMs,$x,$y,$z\n")
    }

    @Synchronized
    fun mark(elapsedMs: Long, label: String) {
        if (closed) return
        marks++
        out.write("m,$elapsedMs,${clean(label)}\n")
    }

    @Synchronized
    fun gesture(elapsedMs: Long, gesture: String) {
        if (closed) return
        out.write("g,$elapsedMs,${clean(gesture)}\n")
    }

    @Synchronized
    fun burst(b: OuraTapDetector.Burst) {
        if (closed) return
        out.write("b,${b.atMs},${b.peak},${b.quietLevel},${b.impacts},${b.firstSpacingMs},${b.durationMs}," +
            "${b.verdict.name},${b.gesture?.name ?: "-"},${b.stillness},${b.lastImpactMs}\n")
    }

    @Synchronized
    fun block(elapsedMs: Long, event: String, index: Int, b: OuraGuidedPlan.Block) {
        if (closed) return
        out.write("k,$elapsedMs,${clean(event)},$index,${clean(b.id)},${b.kind.name},${b.taps},${clean(b.intensity)}," +
            "${b.reps},${b.periodMs},${b.durationMs}\n")
    }

    @Synchronized
    fun cue(elapsedMs: Long, index: Int, rep: Int) {
        if (closed) return
        marks++
        out.write("c,$elapsedMs,$index,$rep\n")
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { out.flush() }
        runCatching { out.close() }
    }

    companion object {
        private fun clean(s: String) = s.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(32)

        /** Open a new recording under `<filesDir>/diagnostics/oura-acm/`. */
        fun open(filesDir: File, label: String, generation: String, wallMs: Long): Pair<OuraAcmRecorder, File> {
            val dir = File(filesDir, "diagnostics/oura-acm").apply { mkdirs() }
            val file = File(dir, "acm-$wallMs-${clean(label)}.csv")
            val writer: BufferedWriter = file.bufferedWriter()
            return OuraAcmRecorder(writer, label, generation) to file
        }
    }
}
