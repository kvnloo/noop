package com.noop.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

internal const val TARGET_REFRESH_HZ = 540.16498
private const val SAMPLE_WINDOW = 120

internal data class FrameStats(
    val targetHz: Double,
    val measuredHz: Double,
    val p50Milliseconds: Double,
    val p95Milliseconds: Double,
    val p99Milliseconds: Double,
    val overBudgetPercent: Double,
    val sampleCount: Int,
) {
    val budgetMilliseconds: Double get() = 1_000.0 / targetHz

    companion object {
        fun waiting(targetHz: Double) = FrameStats(
            targetHz = targetHz,
            measuredHz = 0.0,
            p50Milliseconds = 0.0,
            p95Milliseconds = 0.0,
            p99Milliseconds = 0.0,
            overBudgetPercent = 0.0,
            sampleCount = 0,
        )
    }
}

/**
 * Samples Compose's frame clock while a tiny animated draw node keeps the render loop active.
 * This measures scheduling cadence, not Wayland presentation or scan-out latency.
 */
@Composable
internal fun rememberFrameStats(
    enabled: Boolean,
    targetHz: Double = TARGET_REFRESH_HZ,
): State<FrameStats> {
    val result = remember(targetHz) { mutableStateOf(FrameStats.waiting(targetHz)) }

    LaunchedEffect(enabled, targetHz) {
        result.value = FrameStats.waiting(targetHz)
        if (!enabled) return@LaunchedEffect

        val samples = LongArray(SAMPLE_WINDOW)
        var sampleIndex = 0
        var previous = withFrameNanos { it }
        var warmupFrames = 20

        while (isActive) {
            val current = withFrameNanos { it }
            val delta = current - previous
            previous = current

            if (warmupFrames > 0) {
                warmupFrames -= 1
                continue
            }
            if (delta <= 0L || delta >= 1_000_000_000L) continue

            samples[sampleIndex++] = delta
            if (sampleIndex == samples.size) {
                val snapshot = samples.copyOf()
                sampleIndex = 0
                // Sorting/percentiles do not belong inside a 1.851 ms UI-thread budget. Reset the
                // sampler anchor after publishing so its own aggregation is not counted as a missed frame.
                result.value = withContext(Dispatchers.Default) {
                    calculateFrameStats(snapshot, targetHz)
                }
                previous = withFrameNanos { it }
            }
        }
    }

    return result
}

internal fun calculateFrameStats(samplesNanos: LongArray, targetHz: Double): FrameStats {
    require(samplesNanos.isNotEmpty())
    require(targetHz > 0.0)
    require(samplesNanos.all { it > 0L })

    val sorted = samplesNanos.sortedArray()
    val mean = samplesNanos.average()
    val budget = 1_000_000_000.0 / targetHz

    fun percentile(fraction: Double): Double {
        val index = (fraction * (sorted.size - 1)).roundToInt().coerceIn(sorted.indices)
        return sorted[index] / 1_000_000.0
    }

    return FrameStats(
        targetHz = targetHz,
        measuredHz = 1_000_000_000.0 / mean,
        p50Milliseconds = percentile(0.50),
        p95Milliseconds = percentile(0.95),
        p99Milliseconds = percentile(0.99),
        overBudgetPercent = samplesNanos.count { it > budget }.toDouble() / samplesNanos.size * 100.0,
        sampleCount = samplesNanos.size,
    )
}

@Composable
internal fun FramePacingOverlay(stats: FrameStats, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = NoopPalette.overlay.copy(alpha = 0.96f)),
        border = BorderStroke(1.dp, NoopPalette.gold.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("FRAME CLOCK", color = NoopPalette.gold, fontSize = 10.sp)
                Text(
                    if (stats.sampleCount == 0) "warming up" else "${format(stats.measuredHz, 1)} Hz",
                    color = NoopPalette.textPrimary,
                    fontSize = 12.sp,
                )
            }

            FrameLoadProbe()

            Text(
                "target ${format(stats.targetHz, 3)} Hz · ${format(stats.budgetMilliseconds, 3)} ms",
                color = NoopPalette.textSecondary,
                fontSize = 10.sp,
            )
            if (stats.sampleCount > 0) {
                Text(
                    "p50 ${format(stats.p50Milliseconds, 3)} · p95 ${format(stats.p95Milliseconds, 3)} · p99 ${format(stats.p99Milliseconds, 3)} ms",
                    color = NoopPalette.textSecondary,
                    fontSize = 10.sp,
                )
                Text(
                    "over target budget ${format(stats.overBudgetPercent, 1)}% · n=${stats.sampleCount}",
                    color = if (stats.overBudgetPercent < 5.0) NoopPalette.signalGood else NoopPalette.signalWarn,
                    fontSize = 10.sp,
                )
            }
            Text(
                "Compose callbacks; verify presentation separately",
                color = NoopPalette.textMuted,
                fontSize = 9.sp,
            )
        }
    }
}

/** A cheap draw-only animation that prevents an idle composition from producing misleading samples. */
@Composable
private fun FrameLoadProbe() {
    val transition = rememberInfiniteTransition()
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_000, easing = LinearEasing)),
    )
    Canvas(Modifier.fillMaxWidth().height(3.dp)) {
        drawLine(
            color = NoopPalette.hairline,
            start = Offset.Zero,
            end = Offset(size.width, 0f),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = NoopPalette.gold,
            start = Offset.Zero,
            end = Offset(size.width * progress, 0f),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
    }
}

private fun format(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)
