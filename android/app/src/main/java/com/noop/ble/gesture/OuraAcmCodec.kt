package com.noop.ble.gesture

/**
 * Candidate Oura Gen 3 realtime 0x33 accelerometer wire format, independently
 * re-derived from protocol facts and an offline fixture, with the length byte
 * corrected against one real frame (BLB_04B ring, 2026-10-20). Pure decoder +
 * command builder only: no GATT writes, no background collection, no app-key
 * handling. Tap recognition on real motion remains unverified.
 */
object OuraAcmCodec {
    data class Axes(val x: Int, val y: Int, val z: Int)

    private const val ACCEL_TAG = 0x33
    private const val RATE_50_HZ = 0x32

    /**
     * Expected wire frame: 0x33, length of the bytes that follow (8 or 14), 50 Hz, one header
     * byte of unknown purpose, followed by one or two signed i16-LE xyz triples.
     * Reject unknown framing/rates rather than guessing at timestamps or axes.
     */
    fun decode(frame: ByteArray): List<Axes> {
        if (frame.size != 10 && frame.size != 16) return emptyList()
        if ((frame[0].toInt() and 0xff) != ACCEL_TAG) return emptyList()
        // The length byte is the ring's ordinary outer-frame length: everything after it (rate, header,
        // samples). Observed on hardware (Oura BLB_04B, 2026-10-20): `33 0e 32 ...` in a 16-byte frame.
        if ((frame[1].toInt() and 0xff) != frame.size - 2) return emptyList()
        if ((frame[2].toInt() and 0xff) != RATE_50_HZ) return emptyList()

        fun signedAxis(offset: Int): Int =
            ((frame[offset].toInt() and 0xff) or (frame[offset + 1].toInt() shl 8))
                .toShort().toInt()

        return (4 until frame.size step 6).map {
            Axes(signedAxis(it), signedAxis(it + 2), signedAxis(it + 4))
        }
    }

    /** The ring's frame counter (byte 3, wraps at 256) of a frame [decode] accepts, else null. */
    fun sequence(frame: ByteArray): Int? =
        if (decode(frame).isEmpty()) null else frame[3].toInt() and 0xff

    /** Frames the ring sent between two consecutive counters that never arrived (0 for a repeat). */
    fun framesLostBetween(previous: Int, next: Int): Int = (next - previous - 1).mod(256).let { if (it == 255) 0 else it }

    /** Opt-in bounded request candidate. Never write unless user explicitly arms. */
    fun enableForSeconds(seconds: Int): IntArray {
        require(seconds in 1..300) { "Capture must be 1-300 seconds" }
        val minutes = (seconds + 59) / 60
        return intArrayOf(0x06, 0x07, 0x20, 0, 0, 0,
            minutes and 0xff, (minutes shr 8) and 0xff, 0)
    }

    /** Mandatory realtime-OFF candidate for the transport's finally block. */
    fun stop(): IntArray = intArrayOf(0x06, 0x04, 0, 0, 0, 0)
}
