import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ApplicationWindow {
    id: window
    width: 1320
    height: 860
    minimumWidth: 980
    minimumHeight: 680
    visible: true
    title: "NOOP · Linux Qt performance control"
    color: NoopTheme.surfaceBase

    property string route: "today"

    Rectangle {
        id: sidebar
        width: 232
        anchors.left: parent.left
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        color: "#0e1115"
        border.width: 0

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 20
            spacing: 8

            RowLayout {
                Layout.fillWidth: true
                Layout.bottomMargin: 22
                spacing: 11
                Rectangle {
                    Layout.preferredWidth: 35
                    Layout.preferredHeight: 35
                    radius: 12
                    color: NoopTheme.accent
                    Text {
                        anchors.centerIn: parent
                        text: "N"
                        color: "#08121c"
                        font.family: BrandFont
                        font.pixelSize: 18
                        font.weight: Font.Black
                    }
                }
                Column {
                    Text { text: "NOOP"; color: NoopTheme.textPrimary; font.family: BrandFont; font.pixelSize: 17; font.weight: Font.Bold; font.letterSpacing: 1.2 }
                    Text { text: "LINUX CONTROL"; color: NoopTheme.textTertiary; font.family: BrandFont; font.pixelSize: 9; font.weight: Font.Bold; font.letterSpacing: 1.3 }
                }
            }

            NavButton {
                Layout.fillWidth: true
                text: "Today"
                symbol: "◉"
                selected: window.route === "today"
                onClicked: window.route = "today"
            }
            NavButton {
                Layout.fillWidth: true
                text: "Live"
                symbol: "⌁"
                selected: window.route === "live"
                onClicked: window.route = "live"
            }
            NavButton { Layout.fillWidth: true; text: "Trends"; symbol: "↗"; enabled: false }
            NavButton { Layout.fillWidth: true; text: "Sleep"; symbol: "☾"; enabled: false }
            NavButton { Layout.fillWidth: true; text: "Devices"; symbol: "⌁"; enabled: false }

            Item { Layout.fillHeight: true }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 116
                radius: 14
                color: NoopTheme.surfaceInset
                border.width: 1
                border.color: NoopTheme.hairline
                Column {
                    anchors.fill: parent
                    anchors.margins: 14
                    spacing: 6
                    Row {
                        spacing: 8
                        Rectangle {
                            width: 8; height: 8; radius: 4
                            color: NoopTheme.positive
                            anchors.verticalCenter: parent.verticalCenter

                            // Animator types execute in the scene-graph render thread. This tiny
                            // pulse intentionally keeps presentation active at the output rate;
                            // the live trace itself only rebuilds when sensor data changes.
                            SequentialAnimation on opacity {
                                loops: Animation.Infinite
                                OpacityAnimator { from: 0.35; to: 1.0; duration: 650; easing.type: Easing.InOutSine }
                                OpacityAnimator { from: 1.0; to: 0.35; duration: 650; easing.type: Easing.InOutSine }
                            }
                        }
                        Text { text: FrameStats.verdict; color: NoopTheme.textPrimary; font.family: BrandFont; font.pixelSize: 10; font.weight: Font.Bold; font.letterSpacing: 0.8 }
                    }
                    Text { text: FrameStats.fps.toFixed(1) + " FPS"; color: NoopTheme.textPrimary; font.family: BrandFont; font.pixelSize: 22; font.weight: Font.Bold }
                    Text { text: "p99  " + FrameStats.p99Ms.toFixed(3) + " ms"; color: NoopTheme.textTertiary; font.family: BrandFont; font.pixelSize: 11 }
                    Text { text: FrameStats.backend + " · " + FrameStats.screenRefreshHz.toFixed(1) + " Hz"; color: NoopTheme.textTertiary; font.family: BrandFont; font.pixelSize: 10 }
                }
            }
        }
    }

    Rectangle {
        anchors.left: sidebar.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: 1
        color: NoopTheme.hairline
    }

    Loader {
        id: pageLoader
        anchors.left: sidebar.right
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        sourceComponent: window.route === "live" ? liveComponent : todayComponent
    }

    Component { id: todayComponent; TodayPage {} }
    Component { id: liveComponent; LivePage {} }
}
