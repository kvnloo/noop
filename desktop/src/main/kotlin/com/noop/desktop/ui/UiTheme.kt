package com.noop.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal object NoopPalette {
    val canvas = Color(0xFF07111F)
    val canvasRaised = Color(0xFF0B1727)
    val surface = Color(0xFF111E2E)
    val surfaceStrong = Color(0xFF172638)
    val overlay = Color(0xFF0C1725)
    val hairline = Color(0xFF2A394A)
    val textPrimary = Color(0xFFF2F5F8)
    val textSecondary = Color(0xFFABB6C3)
    val textMuted = Color(0xFF6F7F90)
    val gold = Color(0xFFE7C96B)
    val goldDeep = Color(0xFF8F6F20)
    val blue = Color(0xFF6CA8FF)
    val cyan = Color(0xFF5DD8D0)
    val ember = Color(0xFFE89046)
    val signalGood = Color(0xFF79D5A5)
    val signalWarn = Color(0xFFE7A956)
}

private val NoopColorScheme = darkColorScheme(
    primary = NoopPalette.gold,
    onPrimary = NoopPalette.canvas,
    secondary = NoopPalette.cyan,
    background = NoopPalette.canvas,
    onBackground = NoopPalette.textPrimary,
    surface = NoopPalette.surface,
    onSurface = NoopPalette.textPrimary,
    outline = NoopPalette.hairline,
)

@Composable
internal fun NoopDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NoopColorScheme, content = content)
}
