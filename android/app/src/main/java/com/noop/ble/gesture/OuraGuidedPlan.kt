package com.noop.ble.gesture

/**
 * The guided labelled-recording session for tuning [OuraTapDetector]: ordered blocks of cued taps and
 * thumb scrolls, so a recording carries exact ground truth instead of hand-pressed marks.
 *
 * The short plan (about 3.5 minutes). Walking, handling and typing are not in it: those negatives were
 * already recorded, and real-use strap logs keep supplying them. Four cued reps per tap class, about two
 * seconds apart:
 *  - double and triple taps (the two gestures that drive z0live) at normal, then light, then firm;
 *  - quadruple and quintuple taps and rapid doubles, recorded only so they are never read as a double
 *    or a triple;
 *  - single taps (the commonest accidental impact);
 *  - thumb scrolls on the ring (worn on the index finger, swiped with the thumb): six up, six down, then
 *    five seconds of continuous up and five of continuous down. Recorded for a future scroll gesture
 *    and as negatives for the tap classes.
 * Ordered by importance, so a session stopped early is still useful.
 *
 * Pure (no Android), so the plan, its length and its cue text are JVM-tested.
 */
object OuraGuidedPlan {
    /**
     * TAPS / SCROLL: [Block.reps] cued repetitions [Block.periodMs] apart. RAPID / SCROLL_HOLD: one
     * continuous stretch of [Block.durationMs], cued only at start and end. NEGATIVE: kept so older
     * recordings still parse; the short plan has none.
     */
    enum class Kind { TAPS, RAPID, SCROLL, SCROLL_HOLD, NEGATIVE }

    /**
     * One block. [taps] is the taps per repetition (0 for scrolls). [intensity] is light/normal/firm for
     * taps and the direction (up/down) for scrolls.
     */
    data class Block(
        val id: String,
        val kind: Kind,
        val taps: Int,
        val intensity: String,
        val reps: Int,
        val periodMs: Long,
        val durationMs: Long,
        val spoken: String,
    )

    /** Spoken/announced before a block's first cue; the ring hand should be still when cues start. */
    const val LEAD_IN_MS = 4_000L
    /** Quiet gap after a block before the next announcement. */
    const val GAP_MS = 1_500L
    /** Cued reps per tap block. */
    const val TAP_REPS = 4
    /** Cued reps per discrete scroll block. */
    const val SCROLL_REPS = 6

    private val NAMES = mapOf(1 to "single", 2 to "double", 3 to "triple", 4 to "quadruple", 5 to "quintuple")
    /** Cue spacing: about two seconds, a little more for the longer patterns so each fits between beeps. */
    private val PERIOD = mapOf(1 to 2_000L, 2 to 2_000L, 3 to 2_500L, 4 to 2_500L, 5 to 3_000L)

    private fun taps(n: Int, intensity: String): Block {
        val name = NAMES.getValue(n)
        val period = PERIOD.getValue(n)
        return Block("$name-$intensity", Kind.TAPS, n, intensity, TAP_REPS, period, TAP_REPS * period,
            "$name taps, $intensity, $TAP_REPS times, one at each beep")
    }

    private fun rapid(n: Int, intensity: String): Block =
        Block("rapid-${NAMES.getValue(n)}-$intensity", Kind.RAPID, n, intensity, 0, 0, 8_000L,
            "rapid ${NAMES.getValue(n)}s, $intensity, as fast as you can until the beep")

    private fun scroll(direction: String): Block =
        Block("scroll-$direction", Kind.SCROLL, 0, direction, SCROLL_REPS, 2_000L, SCROLL_REPS * 2_000L,
            "thumb scroll $direction on the ring, $SCROLL_REPS times, one swipe at each beep")

    private fun scrollHold(direction: String): Block =
        Block("scroll-$direction-hold", Kind.SCROLL_HOLD, 0, direction, 0, 0, 5_000L,
            "keep scrolling $direction with your thumb, nonstop, until the beep")

    val BLOCKS: List<Block> = listOf(
        taps(2, "normal"),
        taps(3, "normal"),
        taps(2, "light"),
        taps(2, "firm"),
        taps(3, "light"),
        taps(3, "firm"),
        taps(4, "normal"),
        taps(5, "normal"),
        rapid(2, "normal"),
        taps(1, "normal"),
        scroll("up"),
        scroll("down"),
        scrollHold("up"),
        scrollHold("down"),
    )

    /**
     * Where a saved position from the previous 29-block plan resumes in this one. That plan began with
     * the same double-normal and triple-normal blocks, so an owner who finished them (old position 2 or
     * later) starts at block 3 here instead of repeating them; anything else starts at block 1.
     */
    fun resumeFromLegacy(legacyNext: Int): Int = if (legacyNext >= 2) 2 else 0

    /** Wall time of the whole plan from [from], including lead-ins and gaps. */
    fun totalMs(from: Int = 0): Long = BLOCKS.drop(from).sumOf { LEAD_IN_MS + it.durationMs + GAP_MS }

    /** What is announced (and spoken) before block [index]. */
    fun cue(index: Int): String = "Block ${index + 1} of ${BLOCKS.size}: ${BLOCKS[index].spoken}. Go."

    /** Offsets (ms after the block starts) of each repetition's beep; empty for continuous blocks. */
    fun repOffsets(block: Block): List<Long> =
        if (block.kind != Kind.TAPS && block.kind != Kind.SCROLL) emptyList()
        else List(block.reps) { it * block.periodMs }
}
