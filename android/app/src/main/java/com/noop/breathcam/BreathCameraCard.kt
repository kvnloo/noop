package com.noop.breathcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noop.R
import com.noop.ble.LiveState
import com.noop.ui.Metrics
import com.noop.ui.NoopType
import com.noop.ui.Palette
import com.noop.ui.rrPackets
import com.noop.ui.uiString
import com.noop.ui.StrandAlpha
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.abs

/**
 * EXPERIMENTAL breath camera for the heart meditation view. Off unless [BreathCameraPrefs.enabled]; even
 * then the camera starts only when the user taps "Watch my breathing" and grants CAMERA, shows a visible
 * "Camera on" line the whole time it runs, and stops when tapped or when this card leaves the screen.
 *
 * [pacerSign] reads the meditation guide (+1 inhale, -1 exhale, 0 off/hold) so the card can check which
 * way is "in" for this camera angle. [onSuggestedPace] hands the adaptive pace back to the guide when the
 * user turned adapting on; null means "keep your own pace".
 */
@Composable
fun BreathCameraCard(
    live: Flow<LiveState>,
    pacedBpm: Double,
    pacerSign: () -> Int,
    onSuggestedPace: (Double?) -> Unit,
    onLevel: (Double?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var state by remember { mutableStateOf(BreathCoachState()) }
    val adapt = remember { BreathCameraPrefs.adaptPace(context) }
    val currentPacer by rememberUpdatedState(pacerSign)
    val currentSuggest by rememberUpdatedState(onSuggestedPace)
    val currentLevel by rememberUpdatedState(onLevel)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) running = true else message = R.string.l10n_breath_camera_permission_needed
    }

    if (running) {
        val session = remember(pacedBpm) { BreathCoachSession(pacedBpm, adaptPace = adapt) }
        val polarityCheck = remember { PacerPolarityCheck() }
        val source = remember {
            BreathCameraSource { tMs, est ->
                // Analyzer thread → session (synchronized), then publish a snapshot to Compose.
                val next = synchronized(session) { session.onEstimate(tMs, est) }
                state = next
            }
        }
        LaunchedEffect(source) {
            live.rrPackets().collect { rr ->
                synchronized(session) { session.onRrPacket(SystemClock.elapsedRealtime(), rr) }
            }
        }
        // Pacer polarity check (10 Hz): does the guide's in/out agree with what the camera calls in/out?
        LaunchedEffect(source) {
            while (true) {
                delay(POLARITY_TICK_MS)
                val camSign = when (state.phase) { BreathPhase.INHALE -> 1; BreathPhase.EXHALE -> -1; else -> 0 }
                polarityCheck.offer(currentPacer(), camSign)
                if (polarityCheck.verdict() == -1) {
                    source.polarity = -source.polarity
                    polarityCheck.reset()
                }
            }
        }
        LaunchedEffect(state) {
            currentLevel(if (state.phase == BreathPhase.UNKNOWN) null else state.level)
            currentSuggest(if (adapt) state.suggestedPaceBpm else null)
        }
        LaunchedEffect(source) {
            try {
                source.start(context, owner)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                message = R.string.l10n_breath_camera_failed
                running = false
            }
        }
        DisposableEffect(source) {
            onDispose {
                source.stop()
                currentLevel(null)
                currentSuggest(null)
            }
        }
    }

    val shape = RoundedCornerShape(Metrics.cornerSm)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(Metrics.divider, Palette.hairline, shape)
            .padding(Metrics.space12),
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Text(uiString(R.string.l10n_breath_camera_title), style = NoopType.footnote, color = Palette.textTertiary)
        if (!running) {
            Text(uiString(R.string.l10n_breath_camera_privacy), style = NoopType.footnote, color = Palette.textSecondary)
            message?.let { Text(uiString(it), style = NoopType.footnote, color = Palette.textSecondary) }
            PillButton(uiString(R.string.l10n_breath_camera_start)) {
                message = null
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) running = true else permission.launch(Manifest.permission.CAMERA)
            }
        } else {
            CameraOnIndicator()
            val phaseText = phaseLabel(state)
            Text(
                phaseText,
                style = NoopType.headline,
                color = Palette.textPrimary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (state.phase == BreathPhase.UNKNOWN) {
                Text(uiString(R.string.l10n_breath_camera_framing), style = NoopType.footnote, color = Palette.textSecondary)
            }
            BreathTrace(level = state.level, known = state.phase != BreathPhase.UNKNOWN)
            state.rateBpm?.let {
                Text(uiString(R.string.l10n_breath_camera_rate, fmt1(it)), style = NoopType.subhead, color = Palette.textSecondary)
            }
            state.rsaBpm?.let {
                Text(uiString(R.string.l10n_breath_camera_heart_swing, fmt1(it)), style = NoopType.subhead, color = Palette.textSecondary)
            }
            state.phaseDeg?.let { deg ->
                val line = if (abs(deg) <= IN_STEP_DEG) {
                    uiString(R.string.l10n_breath_camera_in_step)
                } else {
                    uiString(R.string.l10n_breath_camera_lag, fmt1((state.lagMs ?: 0L) / 1000.0))
                }
                Text(line, style = NoopType.footnote, color = Palette.textSecondary)
            }
            val adherence = state.adherence
            if (adherence != null && adherence < LOW_ADHERENCE) {
                Text(uiString(R.string.l10n_breath_camera_follow_guide), style = NoopType.footnote, color = Palette.textSecondary)
            }
            state.suggestedPaceBpm?.let {
                val res = if (state.seeking == ResonanceSeeker.State.SETTLED) {
                    R.string.l10n_breath_camera_pace_settled
                } else {
                    R.string.l10n_breath_camera_pace_adapting
                }
                Text(uiString(res, fmt1(it)), style = NoopType.footnote, color = Palette.textSecondary)
            }
            Text(uiString(R.string.l10n_breath_camera_estimate_note), style = NoopType.footnote, color = Palette.textTertiary)
            PillButton(uiString(R.string.l10n_breath_camera_stop)) { running = false }
        }
    }
}

private const val IN_STEP_DEG = 30.0
private const val POLARITY_TICK_MS = 100L
private const val LOW_ADHERENCE = 0.6

private fun fmt1(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)

@Composable
private fun phaseLabel(s: BreathCoachState): String = uiString(
    when (s.phase) {
        BreathPhase.INHALE -> R.string.l10n_breath_camera_phase_in
        BreathPhase.EXHALE -> R.string.l10n_breath_camera_phase_out
        BreathPhase.HOLD -> if (s.holdKind == HoldKind.AFTER_INHALE) {
            R.string.l10n_breath_camera_phase_hold_full
        } else {
            R.string.l10n_breath_camera_phase_hold_empty
        }
        BreathPhase.UNKNOWN -> R.string.l10n_breath_camera_phase_unknown
    },
)

/** The visible "camera is on" line: a solid dot plus words, never colour alone. */
@Composable
private fun CameraOnIndicator() {
    val label = uiString(R.string.l10n_breath_camera_on)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Box(Modifier.size(Metrics.space8).clip(CircleShape).background(Palette.accent))
        Text(label, style = NoopType.footnote, color = Palette.textPrimary)
    }
}

/** A short scrolling trace of the measured breath, drawn from [level] (-1 empty … +1 full). */
@Composable
private fun BreathTrace(level: Double, known: Boolean) {
    val history = remember { ArrayDeque<Float>() }
    if (known) {
        history.addLast(level.toFloat())
        while (history.size > TRACE_POINTS) history.removeFirst()
    }
    val color = Palette.restBright
    Canvas(Modifier.fillMaxWidth().height(Metrics.space24 + Metrics.space24)) {
        if (history.size < 2) return@Canvas
        val dx = size.width / (TRACE_POINTS - 1)
        val path = Path()
        history.forEachIndexed { i, v ->
            val p = Offset(i * dx, size.height * (0.5f - 0.45f * v.coerceIn(-1f, 1f)))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color = color, style = Stroke(width = Metrics.space2.toPx()))
    }
}

private const val TRACE_POINTS = 90

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Metrics.cornerPill)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Palette.restBright.copy(alpha = StrandAlpha.selectedFill))
            .border(Metrics.divider, Palette.restBright.copy(alpha = StrandAlpha.selectedBorder), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Metrics.space16, vertical = Metrics.space10),
    ) {
        Text(label, style = NoopType.subhead, color = Palette.textPrimary)
    }
}
