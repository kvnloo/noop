package com.noop.desktop.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.sin

interface NoopUiStore {
    val state: StateFlow<NoopUiState>
    fun dispatch(intent: NoopIntent)
}

/**
 * Deterministic desktop adapter used until Linux persistence and BLE adapters implement [NoopUiStore].
 * It is intentionally boring: all rendering behavior is driven through the same typed intents the
 * production store will consume.
 */
class DemoUiStore(frameOverlayVisible: Boolean = false) : NoopUiStore {
    private val mutableState = MutableStateFlow(DemoFixtures.initialState(frameOverlayVisible))
    override val state: StateFlow<NoopUiState> = mutableState.asStateFlow()

    override fun dispatch(intent: NoopIntent) {
        mutableState.update { current -> reduce(current, intent) }
    }

    internal fun reduce(current: NoopUiState, intent: NoopIntent): NoopUiState = when (intent) {
        is NoopIntent.Navigate -> current.copy(destination = intent.destination)
        NoopIntent.ToggleFrameOverlay -> current.copy(
            frameOverlayVisible = !current.frameOverlayVisible,
        )
        NoopIntent.ToggleConnection -> {
            val next = when (current.live.connection) {
                ConnectionPhase.DISCONNECTED -> ConnectionPhase.STREAMING
                ConnectionPhase.CONNECTING -> ConnectionPhase.DISCONNECTED
                ConnectionPhase.STREAMING -> ConnectionPhase.DISCONNECTED
            }
            current.copy(
                live = current.live.copy(
                    connection = next,
                    heartRateBpm = if (next == ConnectionPhase.STREAMING) 67 else null,
                    rrMilliseconds = if (next == ConnectionPhase.STREAMING) 896 else null,
                    streamAgeMilliseconds = if (next == ConnectionPhase.STREAMING) 0 else null,
                ),
            )
        }
        is NoopIntent.DemoPulse -> {
            if (current.live.connection != ConnectionPhase.STREAMING) {
                current
            } else {
                val seconds = intent.monotonicNanos / 1_000_000_000.0
                val phase = (seconds % 120.0 / 120.0).toFloat()
                val heartRate = (67.0 + sin(seconds * 0.63) * 4.0).toInt()
                current.copy(
                    live = current.live.copy(
                        heartRateBpm = heartRate,
                        rrMilliseconds = (60_000.0 / heartRate.coerceAtLeast(1)).toInt(),
                        streamAgeMilliseconds = 0,
                        samples = DemoFixtures.waveform(phase),
                    ),
                )
            }
        }
    }
}
