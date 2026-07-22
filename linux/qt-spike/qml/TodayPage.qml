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
                spacing: 16
                ColumnLayout {
                    spacing: 3
                    Text {
                        text: "Today"
                        color: NoopTheme.textPrimary
                        font.family: BrandFont
                        font.pixelSize: 38
                        font.weight: Font.Bold
                        font.letterSpacing: -1.0
                    }
                    Text {
                        text: "Tuesday, 14 July  ·  Representative mock data"
                        color: NoopTheme.textTertiary
                        font.family: BrandFont
                        font.pixelSize: 14
                    }
                }
                Item { Layout.fillWidth: true }
                Rectangle {
                    implicitWidth: syncRow.implicitWidth + 28
                    implicitHeight: 38
                    radius: 19
                    color: NoopTheme.surfaceInset
                    border.width: 1
                    border.color: NoopTheme.hairline
                    Row {
                        id: syncRow
                        anchors.centerIn: parent
                        spacing: 8
                        Rectangle {
                            width: 8; height: 8; radius: 4
                            color: NoopTheme.positive
                            anchors.verticalCenter: parent.verticalCenter
                        }
                        Text {
                            text: "DEMO  ·  WHOOP 5.0 MG  ·  72%"
                            color: NoopTheme.textSecondary
                            font.family: BrandFont
                            font.pixelSize: 13
                        }
                    }
                }
            }

            NoopCard {
                Layout.fillWidth: true
                Layout.preferredHeight: 274
                color: "#151b20"

                RowLayout {
                    anchors.fill: parent
                    spacing: 8
                    ScoreGauge {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        label: "Charge"
                        value: 78
                        state: "PRIMED"
                        accent: NoopTheme.charge
                    }
                    Rectangle { Layout.preferredWidth: 1; Layout.fillHeight: true; color: NoopTheme.hairline }
                    ScoreGauge {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        label: "Effort"
                        value: 43
                        state: "MODERATE"
                        accent: NoopTheme.effort
                    }
                    Rectangle { Layout.preferredWidth: 1; Layout.fillHeight: true; color: NoopTheme.hairline }
                    ScoreGauge {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        label: "Rest"
                        value: 84
                        state: "RESTORED"
                        accent: NoopTheme.rest
                    }
                }
            }

            NoopCard {
                Layout.fillWidth: true
                Layout.preferredHeight: 194

                RowLayout {
                    anchors.fill: parent
                    spacing: 22
                    ColumnLayout {
                        Layout.preferredWidth: 160
                        spacing: 1
                        Text {
                            text: "LIVE HEART RATE"
                            color: NoopTheme.textTertiary
                            font.family: BrandFont
                            font.pixelSize: 11
                            font.weight: Font.Bold
                            font.letterSpacing: 1.2
                        }
                        Item { Layout.fillHeight: true }
                        Row {
                            spacing: 7
                            Text {
                                text: "72"
                                color: NoopTheme.textPrimary
                                font.family: BrandFont
                                font.pixelSize: 56
                                font.weight: Font.Bold
                                font.letterSpacing: -2.0
                            }
                            Text {
                                text: "BPM"
                                color: NoopTheme.textTertiary
                                font.family: BrandFont
                                font.pixelSize: 12
                                anchors.baseline: parent.children[0].baseline
                            }
                        }
                        Text {
                            text: "Synthetic stream · mock quality"
                            color: NoopTheme.positive
                            font.family: BrandFont
                            font.pixelSize: 12
                        }
                    }

                    Item {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        clip: true
                        Repeater {
                            model: 5
                            Rectangle {
                                x: 0
                                y: index * parent.height / 4
                                width: parent.width
                                height: 1
                                color: Qt.rgba(1, 1, 1, 0.04)
                            }
                        }
                        LivePlot {
                            anchors.fill: parent
                            lineColor: NoopTheme.heart
                            bpm: 72
                        }
                    }
                }
            }

            Text {
                text: "KEY METRICS"
                color: NoopTheme.textTertiary
                font.family: BrandFont
                font.pixelSize: 11
                font.weight: Font.Bold
                font.letterSpacing: 1.4
                Layout.topMargin: 8
            }

            GridLayout {
                Layout.fillWidth: true
                columns: width < 760 ? 2 : 4
                columnSpacing: 12
                rowSpacing: 12
                MetricTile { Layout.fillWidth: true; label: "HRV"; value: "68 ms"; detail: "+8 vs baseline"; accent: NoopTheme.metricPurple }
                MetricTile { Layout.fillWidth: true; label: "Resting HR"; value: "49"; detail: "bpm · stable"; accent: NoopTheme.heart }
                MetricTile { Layout.fillWidth: true; label: "Respiratory"; value: "14.2"; detail: "breaths / min"; accent: NoopTheme.accent }
                MetricTile { Layout.fillWidth: true; label: "Sleep"; value: "7h 46m"; detail: "91% efficiency"; accent: NoopTheme.rest }
            }

            Item { Layout.preferredHeight: 28 }
        }
    }
}
