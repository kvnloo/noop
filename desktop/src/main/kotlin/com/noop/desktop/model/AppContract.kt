package com.noop.desktop.model

/**
 * Platform-neutral rendering contract shared by Android and Linux.
 *
 * Keep this file free of Compose, Android, database, Bluetooth, and wall-clock APIs. Platform adapters
 * take ownership of those concerns and publish immutable snapshots to the renderer.
 */
enum class NoopDestination(val label: String, val eyebrow: String) {
    TODAY("Today", "READINESS"),
    LIVE("Live", "BODY CONSOLE"),
}

data class MetricSnapshot(
    val id: String,
    val label: String,
    val value: String,
    val unit: String,
    val detail: String,
    val normalized: Float,
) {
    init {
        require(id.isNotBlank())
        require(normalized in 0f..1f)
    }
}

data class TodaySnapshot(
    val greeting: String,
    val headline: String,
    val summary: String,
    val metrics: List<MetricSnapshot>,
    val lastUpdatedLabel: String,
)

enum class ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    STREAMING,
}

data class SignalPoint(
    /** Position within the visible rolling window. */
    val x: Float,
    val value: Float,
) {
    init {
        require(x in 0f..1f)
        require(value in 0f..1f)
    }
}

data class LiveSnapshot(
    val connection: ConnectionPhase,
    val deviceName: String,
    val heartRateBpm: Int?,
    val rrMilliseconds: Int?,
    val batteryPercent: Int?,
    val signalQuality: Float,
    val streamAgeMilliseconds: Long?,
    val samples: List<SignalPoint>,
) {
    init {
        require(signalQuality in 0f..1f)
        require(batteryPercent == null || batteryPercent in 0..100)
        require(streamAgeMilliseconds == null || streamAgeMilliseconds >= 0)
    }
}

data class NoopUiState(
    val destination: NoopDestination,
    val today: TodaySnapshot,
    val live: LiveSnapshot,
    val frameOverlayVisible: Boolean,
)

sealed interface NoopIntent {
    data class Navigate(val destination: NoopDestination) : NoopIntent
    data object ToggleConnection : NoopIntent
    data object ToggleFrameOverlay : NoopIntent

    /** Demo-only clock input. Production stores consume sensor events instead. */
    data class DemoPulse(val monotonicNanos: Long) : NoopIntent
}

object DemoFixtures {
    fun initialState(frameOverlayVisible: Boolean = false): NoopUiState = NoopUiState(
        destination = NoopDestination.TODAY,
        today = TodaySnapshot(
            greeting = "Good afternoon",
            headline = "You are primed for focused work.",
            summary = "Recovery is above baseline and overnight stability is strong. Front-load the hard block.",
            metrics = listOf(
                MetricSnapshot(
                    id = "charge",
                    label = "Charge",
                    value = "82",
                    unit = "%",
                    detail = "+9 vs 30-day baseline",
                    normalized = 0.82f,
                ),
                MetricSnapshot(
                    id = "effort",
                    label = "Effort",
                    value = "38",
                    unit = "/ 100",
                    detail = "Quiet start",
                    normalized = 0.38f,
                ),
                MetricSnapshot(
                    id = "rest",
                    label = "Rest",
                    value = "91",
                    unit = "%",
                    detail = "7 h 48 m asleep",
                    normalized = 0.91f,
                ),
            ),
            lastUpdatedLabel = "Demo data · updated just now",
        ),
        live = LiveSnapshot(
            connection = ConnectionPhase.STREAMING,
            deviceName = "DEMO · WHOOP 5.0 MG",
            heartRateBpm = 67,
            rrMilliseconds = 896,
            batteryPercent = 43,
            signalQuality = 0.94f,
            streamAgeMilliseconds = 180,
            samples = waveform(phase = 0f),
        ),
        frameOverlayVisible = frameOverlayVisible,
    )

    fun waveform(phase: Float, count: Int = 240): List<SignalPoint> {
        require(count >= 2)
        return List(count) { index ->
            val x = index.toFloat() / (count - 1).toFloat()
            val carrier = kotlin.math.sin((x * 13f + phase) * 2f * kotlin.math.PI.toFloat())
            val breath = kotlin.math.sin((x * 1.7f + phase * 0.17f) * 2f * kotlin.math.PI.toFloat())
            val value = (0.5f + carrier * 0.12f + breath * 0.09f).coerceIn(0.04f, 0.96f)
            SignalPoint(x = x, value = value)
        }
    }
}
