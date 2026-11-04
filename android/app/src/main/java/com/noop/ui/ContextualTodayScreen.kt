package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.noop.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * An experimental, on-device presentation of NOOP's EXISTING measurements.
 * No new scoring, inference, sync, storage or network access. Only one contextual
 * action is surfaced at a time; dismissals are local to this composition, not stored.
 */
internal enum class ContextualPrompt {
    ACTIVE_SESSION, SCANNING, CONNECT_STRAP, WAITING_FOR_SCORE, REVIEW_DAY
}

/** Stable precedence: a running session cannot be displaced by a transient BLE disconnect. */
internal fun contextualPrompt(
    activeWorkout: Boolean,
    connected: Boolean,
    scanning: Boolean,
    hasCharge: Boolean,
): ContextualPrompt = when {
    activeWorkout -> ContextualPrompt.ACTIVE_SESSION
    scanning && !connected -> ContextualPrompt.SCANNING
    !connected -> ContextualPrompt.CONNECT_STRAP
    !hasCharge -> ContextualPrompt.WAITING_FOR_SCORE
    else -> ContextualPrompt.REVIEW_DAY
}

/** Never turn an unavailable or invalid recovery estimate into a reassuring number. */
internal fun visibleChargePercent(score: Double?): Int? =
    score?.takeIf { it.isFinite() && it in 0.0..100.0 }?.roundToInt()

private data class PromptCopy(val title: String, val detail: String, val action: String)

@Composable
internal fun ContextualTodayScreen(
    viewModel: AppViewModel,
    onOpenDevices: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenTrends: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenJournal: () -> Unit,
    onOpenBreathe: () -> Unit,
    onOpenFullDashboard: () -> Unit,
    onResumeWorkout: () -> Unit,
) {
    val metric by viewModel.today.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val workout by viewModel.activeWorkout.collectAsStateWithLifecycle()
    val charge = visibleChargePercent(metric?.recovery)
    val prompt = contextualPrompt(
        activeWorkout = workout != null,
        connected = live.connected,
        scanning = live.scanning,
        hasCharge = charge != null,
    )
    // Deliberately not rememberSaveable or SharedPreferences: context is fleeting.
    var dismissedPrompt by remember { mutableStateOf<ContextualPrompt?>(null) }
    // A dismissed offline hint must reappear after a connect-then-disconnect transition.
    LaunchedEffect(prompt) { dismissedPrompt = null }
    val copy = when (prompt) {
        ContextualPrompt.ACTIVE_SESSION ->
            PromptCopy(stringResource(R.string.contextual_active_title), stringResource(R.string.contextual_active_detail), stringResource(R.string.contextual_resume))
        ContextualPrompt.SCANNING ->
            PromptCopy(stringResource(R.string.contextual_scanning_title), stringResource(R.string.contextual_scanning_detail), stringResource(R.string.nav_devices))
        ContextualPrompt.CONNECT_STRAP ->
            PromptCopy(stringResource(R.string.contextual_offline_title), stringResource(R.string.contextual_offline_detail), stringResource(R.string.nav_devices))
        ContextualPrompt.WAITING_FOR_SCORE ->
            PromptCopy(stringResource(R.string.contextual_waiting_title), stringResource(R.string.contextual_waiting_detail), stringResource(R.string.nav_sleep))
        ContextualPrompt.REVIEW_DAY ->
            PromptCopy(stringResource(R.string.contextual_review_title), stringResource(R.string.contextual_review_detail), stringResource(R.string.nav_trends))
    }
    val openPrompt: () -> Unit = when (prompt) {
        ContextualPrompt.ACTIVE_SESSION -> onResumeWorkout
        ContextualPrompt.SCANNING, ContextualPrompt.CONNECT_STRAP -> onOpenDevices
        ContextualPrompt.WAITING_FOR_SCORE -> onOpenSleep
        ContextualPrompt.REVIEW_DAY -> onOpenTrends
    }
    val effort = metric?.strain?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
    val sleep = metric?.totalSleepMin?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { String.format(Locale.getDefault(), "%.1f h", it / 60.0) } ?: "—"
    val latestHeartRate = live.heartRate?.takeIf { live.connected && it > 0 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.surfaceBase)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Metrics.screenPadding, vertical = Metrics.screenPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.app_name),
                style = NoopType.caption,
                color = Palette.textSecondary,
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Metrics.screenRowSpacing))
                    .clickable(onClick = onOpenDevices)
                    .padding(horizontal = Metrics.space10, vertical = Metrics.space10),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
            ) {
                Box(
                    Modifier.size(Metrics.space6).background(
                        if (live.connected) Palette.chargeColor else Palette.textTertiary,
                        CircleShape,
                    ),
                )
                Text(
                    if (live.connected) stringResource(R.string.contextual_status_connected) else if (live.scanning) stringResource(R.string.contextual_status_searching) else stringResource(R.string.contextual_status_offline),
                    style = NoopType.overline,
                    color = Palette.textSecondary,
                )
            }
        }

        Spacer(Modifier.height((Metrics.sectionGap + Metrics.space24)))
        Text(
            stringResource(R.string.nav_today),
            style = NoopType.title1,
            color = Palette.textSecondary,
        )
        Spacer(Modifier.height(Metrics.space8))
        Text(
            charge?.let { it.toString() + "%" } ?: "—",
            style = NoopType.display().copy(fontWeight = FontWeight.Light),
            color = Palette.textPrimary,
        )
        Spacer(Modifier.height(Metrics.space8))
        Text(
            when {
                charge != null -> stringResource(R.string.contextual_charge_day, metric?.day ?: "")
                metric != null -> stringResource(R.string.contextual_no_charge_day, metric?.day.orEmpty())
                else -> stringResource(R.string.contextual_no_score)
            },
            style = NoopType.subhead,
            color = Palette.textTertiary,
        )

        Spacer(Modifier.height((Metrics.space24 + Metrics.space12)))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
        ) {
            ContextMetric(
                label = if (latestHeartRate != null) stringResource(R.string.contextual_heart_rate) else stringResource(R.string.contextual_effort),
                value = latestHeartRate?.let { it.toString() + " bpm" } ?: effort,
                modifier = Modifier.weight(1f),
                onClick = if (latestHeartRate != null) onOpenLive else onOpenTrends,
            )
            ContextMetric(
                label = stringResource(R.string.nav_sleep),
                value = sleep,
                modifier = Modifier.weight(1f),
                onClick = onOpenSleep,
            )
        }

        Spacer(Modifier.height((Metrics.sectionGap + Metrics.space16)))
        if (dismissedPrompt != prompt) {
            Surface(
                shape = RoundedCornerShape(Metrics.cardRadius),
                color = Palette.surfaceRaised,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = Metrics.screenRowSpacing, end = Metrics.space12, top = Metrics.space12, bottom = Metrics.space16)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.contextual_in_context),
                            style = NoopType.overline,
                            color = Palette.textTertiary,
                        )
                        IconButton(
                            onClick = { dismissedPrompt = prompt },
                            modifier = Modifier.size((Metrics.iconButton + Metrics.space4)),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.contextual_dismiss),
                                tint = Palette.textSecondary,
                                modifier = Modifier.size(Metrics.space18),
                            )
                        }
                    }
                    Text(
                        copy.title,
                        style = NoopType.title2,
                        color = Palette.textPrimary,
                    )
                    Spacer(Modifier.height(Metrics.space6))
                    Text(
                        copy.detail,
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                    Spacer(Modifier.height(Metrics.space6))
                    TextButton(onClick = openPrompt) {
                        Text(copy.action)
                        Spacer(Modifier.width(Metrics.space2))
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    }
                }
            }
        }

        Spacer(Modifier.height(Metrics.screenPadding))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Explicit user choice: guided breathing already exists, and Journal owns check-ins.
            // No inferred habit completion or auto-start; the user controls both actions.
            TextButton(onClick = onOpenBreathe) { Text(stringResource(R.string.nav_breathe)) }
            TextButton(onClick = onOpenJournal) { Text(stringResource(R.string.contextual_journal)) }
        }
        // Keep the third action on its own line: all three cannot fit at large
        // font sizes or in longer translations on a narrow Android viewport.
        TextButton(
            onClick = onOpenFullDashboard,
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.contextual_full_dashboard)) }
    }
}

@Composable
private fun ContextMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .clickable(onClick = onClick)
            .padding(vertical = Metrics.space10),
    ) {
        Text(label, style = NoopType.caption, color = Palette.textTertiary)
        Spacer(Modifier.height(Metrics.space6))
        Text(value, style = NoopType.title1, color = Palette.textPrimary)
    }
}
