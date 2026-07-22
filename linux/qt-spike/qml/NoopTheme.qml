pragma Singleton
import QtQuick

QtObject {
    // Exact dark-side values from StrandDesign/NoopTheme.swift at 06b03982.
    readonly property color surfaceBase: "#121518"
    readonly property color surfaceRaised: "#25292c"
    readonly property color surfaceOverlay: "#1c1f26"
    readonly property color surfaceInset: "#1f2229"
    readonly property color hairline: "#21304a"
    readonly property color hairlineStrong: "#2e3c57"

    readonly property color textPrimary: "#f4f6f8"
    readonly property color textSecondary: "#c8cfd8"
    readonly property color textTertiary: "#8a94a4"

    readonly property color accent: "#60a0e0"
    readonly property color accentHover: "#8fbeec"
    readonly property color accentMuted: "#16233a"
    readonly property color positive: "#03e095"
    readonly property color warning: "#f0a020"
    readonly property color critical: "#e0662f"
    readonly property color heart: "#ff6b81"

    readonly property color charge: "#03e095"
    readonly property color effort: "#4090e0"
    readonly property color rest: "#83a0b8"
    readonly property color metricPurple: "#9b7bff"
}
