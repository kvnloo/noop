package com.noop.ui

import com.noop.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.ble.LiveState
import com.noop.breathcam.BreathCameraCard
import com.noop.breathcam.BreathCameraPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - HeartMeditationScreen — the Live console's heart vessel, expanded into a beat-synced meditation
//
// Opened by tapping the Live console's heart-rate vessel. The SAME liquid vessel, now driven by the
// beat-to-beat stream instead of the bpm number:
//   • every clean heartbeat throws a gentle splash and a soft halo, played out at the heart's own R-R
//     spacing (HeartMeditationSession's playout), so the pulse lands on real beats;
//   • the fill rises as the heart speeds up and falls as it slows: the respiratory-sinus-arrhythmia wave
//     that breathing puts into heart rate. Breathe slowly with the guide ring and the two move together;
//   • an optional pacer ring breathes at the user's locked resonance pace (Breathe's sweep) or Breathe's
//     5.5 br/min coherence pace.
// Artifacts are skipped, not drawn: until a few clean beats agree the view says "waiting for steady
// beats" and the vessel rests. Reduce-motion / power-save / quiet motion: no frame clock at all — the
// vessel is posed per beat and the guide is a phase word that changes twice a breath.
//
// Display-only: nothing is stored, exported or used by any score. The readouts are labelled estimates.

/** NoopPrefs key for the breathing-guide toggle (display-only; not part of the .noopbak whitelist). */
private const val KEY_HEART_MEDITATION_GUIDE = "live.heartMeditation.guide"

@Composable
fun HeartMeditationScreen(
    live: Flow<LiveState>,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val still = rememberPoseStill()
    val session = remember { HeartMeditationSession() }
    val pace = remember { BiofeedbackPrefs.lockedPace(context) ?: BiofeedbackPrefs.fallbackBpm }
    var guideOn by remember {
        mutableStateOf(NoopPrefs.of(context).getBoolean(KEY_HEART_MEDITATION_GUIDE, true))
    }
    val sessionStartMs = remember { nowMs() }

    // Experimental breath camera (com.noop.breathcam, Settings, default off). When it adapts the pace, the
    // guide switches only at the start of an inhale and restarts its cycle there, so the ring never jumps.
    val breathCamera = remember { BreathCameraPrefs.enabled(context) }
    var cameraBreath by remember { mutableStateOf<Double?>(null) }
    var pendingPace by remember { mutableStateOf<Double?>(null) }
    var guidePace by remember { mutableStateOf(pace) }
    var pacerStartMs by remember { mutableLongStateOf(sessionStartMs) }

    // Snapshots the UI reads; refreshed per packet / per pulse, never per frame.
    var pulses by remember { mutableIntStateOf(0) }
    var packets by remember { mutableIntStateOf(0) }
    var steadiness by remember { mutableStateOf(BeatSteadiness.WAITING) }
    var swing by remember { mutableStateOf<Double?>(null) }
    var focus by remember { mutableStateOf<RhythmFocus?>(null) }
    var wave by remember { mutableStateOf<List<Double>>(emptyList()) }
    var bpm by remember { mutableStateOf<Int?>(null) }
    var stillLevel by remember { mutableStateOf(HeartMeditationTuning.LEVEL_REST) }
    var frameMs by remember { mutableLongStateOf(sessionStartMs) }
    var inhaling by remember { mutableStateOf(true) }

    fun publish() {
        steadiness = session.steadiness
        swing = session.rhythm.swingBpm
        focus = session.focus
        wave = session.rhythm.wave.toList()
        pulses = session.pulses
        bpm = session.cleanBpm
    }

    // A meditation is watched, not touched: keep the screen awake while it is open.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Packets in (rrSeq-keyed, so two identical consecutive packets both count — LiveRrPackets.kt).
    val stillNow by rememberUpdatedState(still)
    LaunchedEffect(Unit) {
        live.rrPackets().collect { rr ->
            val now = nowMs()
            session.onPacket(rr, now)
            if (stillNow) {
                // No frame clock: pulse every queued beat at once and pose the vessel at the new level.
                while (session.tick(now)) Unit
                stillLevel = session.levelAt(now + 60_000)
            }
            packets += 1
            publish()
        }
    }
    if (still) {
        // Still mode loses the rhythm only when packets stop: one timer, re-armed by each packet.
        LaunchedEffect(packets) {
            delay(HeartMeditationTuning.STALE_MS + 250)
            val now = nowMs()
            session.tick(now)
            stillLevel = session.levelAt(now + 60_000)
            publish()
        }
        // The guide as a phase word: one wake-up per inhale/exhale boundary, no animation.
        LaunchedEffect(guideOn, guidePace, pacerStartMs) {
            while (guideOn) {
                val elapsed = nowMs() - pacerStartMs
                inhaling = pacerInhaling(elapsed, guidePace)
                delay(pacerMsToNextPhase(elapsed, guidePace))
            }
        }
    } else {
        // The one frame clock: plays beats out on time and moves the pacer. Stops with the composition.
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos { frame ->
                    val now = frame / 1_000_000L
                    frameMs = now
                    if (session.tick(now)) publish() else if (session.steadiness != steadiness) publish()
                }
            }
        }
    }

    // The parent recomposes only when the guide flips phase; the per-frame reads live in MeditationStage
    // (the vessel's level) and in the halo's draw block.
    val inhaleNow by remember(still, guidePace, pacerStartMs) {
        derivedStateOf { if (still) inhaling else pacerInhaling(frameMs - pacerStartMs, guidePace) }
    }
    LaunchedEffect(inhaleNow) {
        val next = pendingPace
        if (inhaleNow && next != null && next != guidePace) {
            pacerStartMs = if (still) nowMs() else frameMs
            guidePace = next
        }
    }
    val beating = steadiness != BeatSteadiness.WAITING

    Box(modifier = Modifier.fillMaxSize().background(Palette.surfaceBase)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.screenPadding, vertical = Metrics.space16),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space18),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(uiString(R.string.l10n_heart_meditation_title))
                    Text(
                        uiString(R.string.l10n_heart_meditation_subtitle),
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = uiString(R.string.l10n_heart_meditation_close),
                        tint = Palette.textTertiary,
                    )
                }
            }

            StatePill(
                title = steadinessTitle(steadiness),
                tone = when (steadiness) {
                    BeatSteadiness.WAITING -> StrandTone.Neutral
                    BeatSteadiness.NOISY -> StrandTone.Warning
                    BeatSteadiness.STEADY -> StrandTone.Positive
                },
                pulsing = steadiness == BeatSteadiness.WAITING,
            )

            MeditationStage(
                frameMs = { frameMs },
                level = { if (still) stillLevel else session.levelAt(frameMs) },
                pacer = { if (still || !guideOn) null else pacerLevel(frameMs - pacerStartMs, guidePace) },
                breath = { if (still) null else cameraBreath },
                pulses = pulses,
                halo = !still && beating,
                bpm = bpm,
            )

            // The guide's word for this moment (or why there is nothing to follow yet).
            Text(
                text = when {
                    !beating -> uiString(R.string.l10n_heart_meditation_waiting_detail)
                    guideOn -> uiString(
                        if (inhaleNow) R.string.l10n_heart_meditation_breathe_in
                        else R.string.l10n_heart_meditation_breathe_out,
                    )
                    else -> uiString(R.string.l10n_heart_meditation_follow_wave)
                },
                style = if (beating && guideOn) NoopType.title2 else NoopType.subhead,
                color = if (beating && guideOn) Palette.textPrimary else Palette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            // The heart-rate wave itself, as the console's live thread: a breath-paced sine when calm.
            if (wave.size >= 2) {
                LiquidThread(bpm = wave, animated = true, height = 64.dp, modifier = Modifier.fillMaxWidth())
            } else {
                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxWidth().height(2.dp)
                            .clip(RoundedCornerShape(Metrics.cornerPill)).background(Palette.hairline),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), modifier = Modifier.fillMaxWidth()) {
                MeditationReadout(
                    modifier = Modifier.weight(1f),
                    label = uiString(R.string.l10n_heart_meditation_swing_label),
                    value = swing?.takeIf { beating }
                        ?.let { uiString(R.string.l10n_heart_meditation_swing_value, (it / 2.0).roundToInt()) }
                        ?: "—",
                )
                MeditationReadout(
                    modifier = Modifier.weight(1f),
                    label = uiString(R.string.l10n_heart_meditation_focus_label),
                    value = focus?.let {
                        uiString(
                            R.string.l10n_heart_meditation_focus_value,
                            (it.focus * 100).roundToInt(),
                            String.format(Locale.getDefault(), "%.1f", it.perMinute),
                        )
                    } ?: uiString(R.string.l10n_heart_meditation_focus_building),
                )
            }

            if (breathCamera) {
                BreathCameraCard(
                    live = live,
                    pacedBpm = pace,
                    pacerSign = { if (!guideOn) 0 else if (inhaleNow) 1 else -1 },
                    onSuggestedPace = { pendingPace = it ?: pace },
                    onLevel = { cameraBreath = it },
                )
            }

            GuideToggle(on = guideOn, pace = guidePace) {
                guideOn = !guideOn
                NoopPrefs.of(context).edit().putBoolean(KEY_HEART_MEDITATION_GUIDE, guideOn).apply()
            }

            Text(
                uiString(R.string.l10n_heart_meditation_estimate_note),
                style = NoopType.footnote, color = Palette.textTertiary, textAlign = TextAlign.Center,
            )
        }
    }
}

/** The vessel, its rings and the bpm over it — the only part of the view that redraws every frame. */
@Composable
private fun MeditationStage(
    frameMs: () -> Long,
    level: () -> Double,
    pacer: () -> Double?,
    breath: () -> Double?,
    pulses: Int,
    halo: Boolean,
    bpm: Int?,
) {
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        MeditationHalo(
            pacer = pacer, breath = breath, pulses = pulses, frameMs = frameMs, animate = halo,
            modifier = Modifier.fillMaxSize(),
        )
        LiquidVessel(
            value = level(),
            tint = liquidHeartPink,
            animated = true,
            beat = pulses,
            modifier = Modifier.fillMaxSize(0.72f),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clearAndSetSemantics {},
        ) {
            Text(
                bpm?.toString() ?: "—",
                style = NoopType.number(56f, weight = FontWeight.Bold)
                    .copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(0f, 1f), blurRadius = 6f)),
                color = Color.White,
            )
            Text("bpm", style = NoopType.subhead, color = Palette.textSecondary)
        }
    }
}

private fun nowMs(): Long = System.nanoTime() / 1_000_000L

private fun steadinessTitle(s: BeatSteadiness): String = uiString(
    when (s) {
        BeatSteadiness.WAITING -> R.string.l10n_heart_meditation_waiting
        BeatSteadiness.NOISY -> R.string.l10n_heart_meditation_noisy
        BeatSteadiness.STEADY -> R.string.l10n_heart_meditation_steady
    },
)

/**
 * The rings around the vessel: the breathing guide (a soft ring that widens on the inhale and narrows on
 * the exhale) and a heartbeat halo that blooms out from the vessel's rim and fades over ~0.9 s after each
 * pulse. Draws only from [frameMs]; with [animate] off (still mode, or no steady beats) the halo is
 * hidden and the guide ring, if any, sits at rest.
 */
@Composable
private fun MeditationHalo(
    pacer: () -> Double?,
    breath: () -> Double?,
    pulses: Int,
    frameMs: () -> Long,
    animate: Boolean,
    modifier: Modifier,
) {
    var pulseAtMs by remember { mutableLongStateOf(Long.MIN_VALUE) }
    LaunchedEffect(pulses) { if (pulses != 0) pulseAtMs = frameMs() }
    val guideColor = Palette.restBright
    val haloColor = liquidHeartPink
    val breathColor = Palette.textPrimary.copy(alpha = StrandAlpha.subtleLine)
    Canvas(modifier = modifier) {
        val c = center
        val vesselR = size.minDimension * 0.36f
        val pacer = pacer()
        val now = frameMs()
        if (pacer != null) {
            val r = vesselR * (1.06f + 0.30f * pacer.toFloat())
            drawCircle(color = guideColor.copy(alpha = 0.02f + 0.05f * pacer.toFloat()), radius = r, center = c)
            drawCircle(
                color = guideColor.copy(alpha = 0.55f),
                radius = r,
                center = c,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        // The experimental breath camera's measured breath (-1 empty … +1 full) on the guide's own scale:
        // following the guide means this thin ring rides on the guide ring.
        breath()?.let { b ->
            val unit = ((b + 1.0) / 2.0).coerceIn(0.0, 1.0).toFloat()
            drawCircle(
                color = breathColor,
                radius = vesselR * (1.06f + 0.30f * unit),
                center = c,
                style = Stroke(width = Metrics.space2.toPx() * 0.75f),
            )
        }
        if (animate && pulseAtMs != Long.MIN_VALUE) {
            val age = ((now - pulseAtMs) / 900f).coerceIn(0f, 1f)
            if (age < 1f) {
                drawCircle(
                    color = haloColor.copy(alpha = 0.45f * (1f - age)),
                    radius = vesselR * (1.0f + 0.22f * age),
                    center = c,
                    style = Stroke(width = (3.dp.toPx() * (1f - age)).coerceAtLeast(0.5f)),
                )
            }
        }
    }
}

@Composable
private fun MeditationReadout(modifier: Modifier, label: String, value: String) {
    val shape = RoundedCornerShape(Metrics.cornerSm)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(1.dp, Palette.hairline, shape)
            .padding(Metrics.space12),
        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
    ) {
        Text(label, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
        Text(value, style = NoopType.headline, color = Palette.textPrimary, maxLines = 2)
    }
}

@Composable
private fun GuideToggle(on: Boolean, pace: Double, onToggle: () -> Unit) {
    val label = if (on) {
        uiString(R.string.l10n_heart_meditation_guide_on, String.format(Locale.getDefault(), "%.1f", pace))
    } else {
        uiString(R.string.l10n_heart_meditation_guide_off)
    }
    val state = uiString(if (on) R.string.l10n_heart_meditation_guide_state_on else R.string.l10n_heart_meditation_guide_state_off)
    val shape = RoundedCornerShape(Metrics.cornerPill)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (on) Palette.restBright.copy(alpha = 0.14f) else Palette.surfaceInset)
            .border(1.dp, if (on) Palette.restBright.copy(alpha = 0.45f) else Palette.hairline, shape)
            .clickable(role = Role.Switch, onClick = onToggle)
            .semantics { stateDescription = state }
            .padding(horizontal = Metrics.space16, vertical = Metrics.space10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = NoopType.subhead, color = if (on) Palette.textPrimary else Palette.textSecondary)
    }
    Spacer(Modifier.height(Metrics.space2))
}
