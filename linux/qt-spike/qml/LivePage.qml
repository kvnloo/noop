import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Noop.Perf 1.0

Item {
    id: page

    ScrollView {
        anchors.fill: parent
        clip: true
        ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
        contentWidth: availableWidth

        ColumnLayout {
            x: Math.max(24, (page.width - width) / 2)
            width: Math.min(1100, page.width - 48)
            spacing: 16

            Item { Layout.preferredHeight: 24 }
            RowLayout {
                Layout.fillWidth: true
                ColumnLayout {
                    spacing: 3
                    Text {
                        text: "Live console"
                        color: NoopTheme.textPrimary
                        font.family: BrandFont
                        font.pixelSize: 38
                        font.weight: Font.Bold
                        font.letterSpacing: -1.0
                    }
                    Text {
                        text: "Sensor-rate geometry · display-rate composition"
                        color: NoopTheme.textTertiary
                        font.family: BrandFont
                        font.pixelSize: 14
                    }
                }
                Item { Layout.fillWidth: true }
                Rectangle {
                    implicitWidth: liveStatus.implicitWidth + 30
                    implicitHeight: 40
                    radius: 20
                    color: NoopTheme.surfaceInset
                    border.width: 1
                    border.color: NoopTheme.hairline
                    Row {
                        id: liveStatus
                        anchors.centerIn: parent
                        spacing: 9
                        Rectangle { width: 9; height: 9; radius: 5; color: NoopTheme.positive; anchors.verticalCenter: parent.verticalCenter }
                        Text { text: "Bonded · streaming"; color: NoopTheme.textPrimary; font.family: BrandFont; font.pixelSize: 13 }
                    }
                }
            }

            NoopCard {
                Layout.fillWidth: true
                Layout.preferredHeight: 340
                color: "#151b20"

                ColumnLayout {
                    anchors.fill: parent
                    spacing: 12
                    RowLayout {
                        Layout.fillWidth: true
                        Text {
                            text: "HEART RATE THREAD"
                            color: NoopTheme.textTertiary
                            font.family: BrandFont
                            font.pixelSize: 11
                            font.weight: Font.Bold
                            font.letterSpacing: 1.3
                        }
                        Item { Layout.fillWidth: true }
                        Text {
                            text: "geometry ≤ 62.5 Hz  ·  compositor up to 540 Hz"
                            color: NoopTheme.textTertiary
                            font.family: BrandFont
                            font.pixelSize: 12
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        ColumnLayout {
                            Layout.preferredWidth: 180
                            Text {
                                text: "72"
                                color: NoopTheme.textPrimary
                                font.family: BrandFont
                                font.pixelSize: 76
                                font.weight: Font.Bold
                                font.letterSpacing: -3.0
                            }
                            Text {
                                text: "BPM"
                                color: NoopTheme.heart
                                font.family: BrandFont
                                font.pixelSize: 12
                                font.weight: Font.Bold
                                font.letterSpacing: 1.4
                            }
                            Item { Layout.fillHeight: true }
                            Text {
                                text: "RMSSD  68 ms\nR-R  833 ms\nQuality  GOOD"
                                color: NoopTheme.textSecondary
                                font.family: BrandFont
                                font.pixelSize: 13
                                lineHeight: 1.6
                            }
                        }
                        Item {
                            Layout.fillWidth: true
                            Layout.fillHeight: true
                            clip: true
                            Repeater {
                                model: 7
                                Rectangle {
                                    x: 0
                                    y: index * parent.height / 6
                                    width: parent.width
                                    height: 1
                                    color: Qt.rgba(1, 1, 1, 0.045)
                                }
                            }
                            LivePlot {
                                id: livePlot
                                anchors.fill: parent
                                lineColor: NoopTheme.heart
                                bpm: 72
                            }
                        }
                    }
                }
            }

            NoopCard {
                Layout.fillWidth: true
                Layout.preferredHeight: 210
                color: FrameStats.verdict === "ON 540 HZ BUDGET" ? "#14231f" : NoopTheme.surfaceRaised

                ColumnLayout {
                    anchors.fill: parent
                    spacing: 13
                    RowLayout {
                        Layout.fillWidth: true
                        ColumnLayout {
                            spacing: 2
                            Text {
                                text: "540 HZ FRAME CONTROL"
                                color: NoopTheme.textPrimary
                                font.family: BrandFont
                                font.pixelSize: 15
                                font.weight: Font.Bold
                                font.letterSpacing: 1.1
                            }
                            Text {
                                text: FrameStats.backend + " · screen reports " + FrameStats.screenRefreshHz.toFixed(3) + " Hz"
                                color: NoopTheme.textTertiary
                                font.family: BrandFont
                                font.pixelSize: 12
                            }
                        }
                        Item { Layout.fillWidth: true }
                        Rectangle {
                            implicitWidth: verdictText.implicitWidth + 24
                            implicitHeight: 32
                            radius: 16
                            color: FrameStats.verdict === "ON 540 HZ BUDGET" ? Qt.rgba(0.01, 0.88, 0.58, 0.12) : NoopTheme.surfaceInset
                            Text {
                                id: verdictText
                                anchors.centerIn: parent
                                text: FrameStats.verdict
                                color: FrameStats.verdict === "ON 540 HZ BUDGET" ? NoopTheme.positive : NoopTheme.warning
                                font.family: BrandFont
                                font.pixelSize: 10
                                font.weight: Font.Bold
                                font.letterSpacing: 1.1
                            }
                        }
                    }
                    Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; color: NoopTheme.hairline }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 28
                        Column { Text { text: "FPS"; color: NoopTheme.textTertiary; font.family: BrandFont; font.pixelSize: 10 }
                                 Text { text: FrameStats.fps.toFixed(1); color: NoopTheme.textPrimary; font.family: BrandFont; font.pixelSize: 23; font.weight: Font.Bold } }
                        Column { Text { text: "P50"; color: NoopTheme.textTertiary; font.family: BrandFont; font.pixelSize: 10 }
                                 Text { text: FrameStats.p50Ms.toFixed(3) + " ms"; color: NoopTheme.textPrimary; font.family: BrandFont; font.pixelSize: 23; font.weight: Font.Bold } }
                        Column { Text { text: "P95"; color: NoopTheme.textTertiary; font.family: BrandFont; font.pixelSize: 10 }
                                 Text { text: FrameStats.p95Ms.toFixed(3) + " ms"; color: NoopTheme.textPrimary; font.family: BrandFont; font.pixelSize: 23; font.weight: Font.Bold } }
                        Column { Text { text: "P99"; color: NoopTheme.textTertiary; font.family: BrandFont; font.pixelSize: 10 }
                                 Text { text: FrameStats.p99Ms.toFixed(3) + " ms"; color: NoopTheme.textPrimary; font.family: BrandFont; font.pixelSize: 23; font.weight: Font.Bold } }
                        Column { Text { text: "> BUDGET"; color: NoopTheme.textTertiary; font.family: BrandFont; font.pixelSize: 10 }
                                 Text { text: FrameStats.overBudgetPercent.toFixed(2) + "%"; color: NoopTheme.textPrimary; font.family: BrandFont; font.pixelSize: 23; font.weight: Font.Bold } }
                        Item { Layout.fillWidth: true }
                        Button {
                            id: resetButton
                            text: "Reset run"
                            onClicked: FrameStats.reset()
                            contentItem: Text { text: resetButton.text; color: NoopTheme.textPrimary; font.family: BrandFont; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                            background: Rectangle { radius: 10; color: resetButton.hovered ? NoopTheme.accentMuted : NoopTheme.surfaceInset; border.width: 1; border.color: NoopTheme.hairline }
                        }
                        Button {
                            id: driverButton
                            text: FrameStats.continuous ? "Continuous ON" : "Continuous OFF"
                            onClicked: FrameStats.continuous = !FrameStats.continuous
                            contentItem: Text { text: driverButton.text; color: FrameStats.continuous ? NoopTheme.positive : NoopTheme.textSecondary; font.family: BrandFont; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                            background: Rectangle { radius: 10; color: driverButton.hovered ? NoopTheme.accentMuted : NoopTheme.surfaceInset; border.width: 1; border.color: NoopTheme.hairline }
                        }
                    }
                    Text {
                        text: FrameStats.sampleCount.toLocaleString() + " frame intervals · target budget " + FrameStats.targetFrameMs.toFixed(4) + " ms"
                        color: NoopTheme.textTertiary
                        font.family: BrandFont
                        font.pixelSize: 11
                    }
                }
            }

            Item { Layout.preferredHeight: 28 }
        }
    }
}
