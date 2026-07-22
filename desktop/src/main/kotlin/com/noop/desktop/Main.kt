package com.noop.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.noop.desktop.model.ConnectionPhase
import com.noop.desktop.model.DemoUiStore
import com.noop.desktop.model.LiveSnapshot
import com.noop.desktop.model.MetricSnapshot
import com.noop.desktop.model.NoopDestination
import com.noop.desktop.model.NoopIntent
import com.noop.desktop.model.NoopUiState
import com.noop.desktop.model.TodaySnapshot
import com.noop.desktop.ui.FramePacingOverlay
import com.noop.desktop.ui.NoopDesktopTheme
import com.noop.desktop.ui.NoopPalette
import com.noop.desktop.ui.rememberFrameStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

fun main(args: Array<String>) = application {
    val benchmarkOnLaunch = remember(args.toList()) { args.any { it == "--benchmark" } }
    val store = remember { DemoUiStore(frameOverlayVisible = benchmarkOnLaunch) }
    val state by store.state.collectAsState()

    Window(
        onCloseRequest = ::exitApplication,
        title = "NOOP · Linux spike",
        state = rememberWindowState(width = 1_360.dp, height = 860.dp),
    ) {
        NoopDesktopTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = NoopPalette.canvas) {
                NoopDesktopShell(state = state, dispatch = store::dispatch)
            }
        }
    }
}

@Composable
private fun NoopDesktopShell(
    state: NoopUiState,
    dispatch: (NoopIntent) -> Unit,
) {
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1_000)
            dispatch(NoopIntent.DemoPulse(System.nanoTime()))
        }
    }

    val frameStats by rememberFrameStats(enabled = state.frameOverlayVisible)

    Box(Modifier.fillMaxSize().background(NoopPalette.canvas)) {
        Row(Modifier.fillMaxSize()) {
            DesktopSidebar(
                selected = state.destination,
                benchmarkVisible = state.frameOverlayVisible,
                onNavigate = { dispatch(NoopIntent.Navigate(it)) },
                onToggleBenchmark = { dispatch(NoopIntent.ToggleFrameOverlay) },
            )

            Column(Modifier.fillMaxSize()) {
                DesktopHeader(state)
                Crossfade(targetState = state.destination) { destination ->
                    when (destination) {
                        NoopDestination.TODAY -> TodayPage(state.today)
                        NoopDestination.LIVE -> LivePage(
                            live = state.live,
                            onToggleConnection = { dispatch(NoopIntent.ToggleConnection) },
                        )
                    }
                }
            }
        }

        if (state.frameOverlayVisible) {
            FramePacingOverlay(
                stats = frameStats,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 84.dp, end = 24.dp)
                    .width(330.dp),
            )
        }
    }
}

@Composable
private fun DesktopSidebar(
    selected: NoopDestination,
    benchmarkVisible: Boolean,
    onNavigate: (NoopDestination) -> Unit,
    onToggleBenchmark: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(228.dp)
            .background(NoopPalette.canvasRaised)
            .border(width = 1.dp, color = NoopPalette.hairline.copy(alpha = 0.7f))
            .padding(horizontal = 18.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(
                        Brush.linearGradient(listOf(NoopPalette.gold, NoopPalette.goldDeep)),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("N", color = NoopPalette.canvas, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("NOOP", color = NoopPalette.textPrimary, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("LINUX LAB", color = NoopPalette.textMuted, fontSize = 9.sp, letterSpacing = 1.4.sp)
            }
        }

        Spacer(Modifier.height(44.dp))
        Text("OVERVIEW", color = NoopPalette.textMuted, fontSize = 9.sp, letterSpacing = 1.6.sp)
        Spacer(Modifier.height(10.dp))
        NoopDestination.entries.forEach { destination ->
            SidebarDestination(
                destination = destination,
                selected = selected == destination,
                onClick = { onNavigate(destination) },
            )
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = NoopPalette.hairline)
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = onToggleBenchmark,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = NoopPalette.textSecondary),
        ) {
            Text(if (benchmarkVisible) "Hide frame lab" else "Open frame lab", fontSize = 12.sp)
        }
        Text(
            "Compose Desktop spike\nNo Android services loaded",
            color = NoopPalette.textMuted,
            fontSize = 9.sp,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun SidebarDestination(
    destination: NoopDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) NoopPalette.gold.copy(alpha = 0.12f) else Color.Transparent
    val border = if (selected) NoopPalette.gold.copy(alpha = 0.34f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(7.dp).background(
                if (selected) NoopPalette.gold else NoopPalette.textMuted,
                CircleShape,
            ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            destination.label,
            color = if (selected) NoopPalette.textPrimary else NoopPalette.textSecondary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun DesktopHeader(state: NoopUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(NoopPalette.canvas.copy(alpha = 0.94f))
            .border(1.dp, NoopPalette.hairline.copy(alpha = 0.55f))
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(state.destination.eyebrow, color = NoopPalette.gold, fontSize = 9.sp, letterSpacing = 1.8.sp)
            Text(state.destination.label, color = NoopPalette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        val connected = state.live.connection == ConnectionPhase.STREAMING
        Box(
            Modifier
                .background(
                    if (connected) NoopPalette.signalGood.copy(alpha = 0.10f) else NoopPalette.surface,
                    RoundedCornerShape(100.dp),
                )
                .border(
                    1.dp,
                    if (connected) NoopPalette.signalGood.copy(alpha = 0.35f) else NoopPalette.hairline,
                    RoundedCornerShape(100.dp),
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                if (connected) "●  ${state.live.deviceName}" else "○  Strap offline",
                color = if (connected) NoopPalette.signalGood else NoopPalette.textSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun TodayPage(today: TodaySnapshot) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 42.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item {
            Text(today.greeting, color = NoopPalette.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                today.headline,
                color = NoopPalette.textPrimary,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.height(12.dp))
            Text(today.summary, color = NoopPalette.textSecondary, fontSize = 15.sp, lineHeight = 23.sp)
        }

        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 800.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        today.metrics.forEach { metric -> MetricCard(metric, Modifier.weight(1f)) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        today.metrics.forEach { metric -> MetricCard(metric, Modifier.fillMaxWidth()) }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = NoopPalette.surface),
                border = BorderStroke(1.dp, NoopPalette.hairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("FOCUS WINDOW", color = NoopPalette.gold, fontSize = 10.sp, letterSpacing = 1.6.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Deep work · 2 h 20 m", color = NoopPalette.textPrimary, fontSize = 21.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Autonomic stability is strongest before 4:40 PM.", color = NoopPalette.textSecondary, fontSize = 13.sp)
                    }
                    Box(
                        Modifier
                            .background(NoopPalette.gold.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("Begin block", color = NoopPalette.gold, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }
        }

        item { Text(today.lastUpdatedLabel, color = NoopPalette.textMuted, fontSize = 10.sp) }
    }
}

@Composable
private fun MetricCard(metric: MetricSnapshot, modifier: Modifier = Modifier) {
    val accent = when (metric.id) {
        "charge" -> NoopPalette.gold
        "effort" -> NoopPalette.ember
        else -> NoopPalette.blue
    }
    Card(
        modifier = modifier.height(186.dp),
        colors = CardDefaults.cardColors(containerColor = NoopPalette.surface),
        border = BorderStroke(1.dp, NoopPalette.hairline),
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(metric.label.uppercase(), color = accent, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                MetricRing(metric.normalized, accent)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(metric.value, color = NoopPalette.textPrimary, fontSize = 42.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.width(6.dp))
                Text(metric.unit, color = NoopPalette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 7.dp))
            }
            Text(metric.detail, color = NoopPalette.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MetricRing(value: Float, color: Color) {
    Canvas(Modifier.size(38.dp)) {
        drawArc(
            color = NoopPalette.hairline,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * value,
            useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun LivePage(live: LiveSnapshot, onToggleConnection: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LIVE BODY CONSOLE", color = NoopPalette.gold, fontSize = 10.sp, letterSpacing = 1.7.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        live.heartRateBpm?.toString() ?: "—",
                        color = NoopPalette.textPrimary,
                        fontSize = 64.sp,
                        lineHeight = 66.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text("beats per minute", color = NoopPalette.textSecondary, fontSize = 13.sp)
                }
                Button(
                    onClick = onToggleConnection,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (live.connection == ConnectionPhase.STREAMING) NoopPalette.surfaceStrong else NoopPalette.gold,
                        contentColor = if (live.connection == ConnectionPhase.STREAMING) NoopPalette.textPrimary else NoopPalette.canvas,
                    ),
                ) {
                    Text(if (live.connection == ConnectionPhase.STREAMING) "Disconnect demo" else "Connect demo")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NoopPalette.surface),
                border = BorderStroke(1.dp, NoopPalette.hairline),
            ) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ROLLING PULSE", color = NoopPalette.cyan, fontSize = 10.sp, letterSpacing = 1.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text("draw-only animation", color = NoopPalette.textMuted, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    LiveWaveform(live)
                }
            }
        }

        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stats = listOf(
                    Triple("R–R", live.rrMilliseconds?.toString() ?: "—", "ms"),
                    Triple("Signal", "${(live.signalQuality * 100).toInt()}", "%"),
                    Triple("Battery", live.batteryPercent?.toString() ?: "—", "%"),
                    Triple("Stream age", live.streamAgeMilliseconds?.toString() ?: "—", "ms"),
                )
                if (maxWidth >= 720.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        stats.forEach { (label, value, unit) -> LiveStat(label, value, unit, Modifier.weight(1f)) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        stats.forEach { (label, value, unit) -> LiveStat(label, value, unit, Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveWaveform(live: LiveSnapshot) {
    val transition = rememberInfiniteTransition()
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_200, easing = LinearEasing)),
    )
    val samples = live.samples
    val streaming = live.connection == ConnectionPhase.STREAMING
    Box(
        Modifier
            .fillMaxWidth()
            .height(270.dp)
            .drawWithCache {
                // Rebuild only when samples or size change (the demo updates at 1 Hz), not on every
                // 540 Hz probe frame. The moving probe remains a draw-stage state read below.
                val path = Path()
                samples.forEachIndexed { index, point ->
                    val x = point.x * size.width
                    val y = (1f - point.value) * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val waveformBrush = Brush.horizontalGradient(
                    listOf(NoopPalette.blue, NoopPalette.cyan, NoopPalette.gold),
                )
                val waveformStroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                val probeRadius = 3.5.dp.toPx()

                onDrawBehind {
                    repeat(5) { index ->
                        val y = size.height * index / 4f
                        drawLine(NoopPalette.hairline.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), 1f)
                    }
                    repeat(9) { index ->
                        val x = size.width * index / 8f
                        drawLine(NoopPalette.hairline.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, size.height), 1f)
                    }

                    if (samples.size >= 2 && streaming) {
                        drawPath(path = path, brush = waveformBrush, style = waveformStroke)
                        val index = (phase * samples.lastIndex).toInt().coerceIn(samples.indices)
                        val probe = samples[index]
                        drawCircle(
                            color = NoopPalette.textPrimary,
                            radius = probeRadius,
                            center = Offset(probe.x * size.width, (1f - probe.value) * size.height),
                        )
                    }
                }
            },
    )
}

@Composable
private fun LiveStat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(108.dp),
        colors = CardDefaults.cardColors(containerColor = NoopPalette.surface),
        border = BorderStroke(1.dp, NoopPalette.hairline),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(label.uppercase(), color = NoopPalette.textMuted, fontSize = 9.sp, letterSpacing = 1.1.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = NoopPalette.textPrimary, fontSize = 25.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.width(4.dp))
                Text(unit, color = NoopPalette.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}
