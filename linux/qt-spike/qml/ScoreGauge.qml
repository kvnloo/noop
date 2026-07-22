import QtQuick
import QtQuick.Shapes

Item {
    id: root
    property string label: "Charge"
    property real value: 0
    property string suffix: "%"
    property string state: "CALIBRATING"
    property color accent: NoopTheme.charge

    implicitWidth: 210
    implicitHeight: 220

    Shape {
        id: track
        width: 156
        height: 156
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.top: parent.top
        anchors.topMargin: 8
        antialiasing: true

        ShapePath {
            strokeWidth: 11
            strokeColor: Qt.rgba(1, 1, 1, 0.075)
            fillColor: "transparent"
            capStyle: ShapePath.RoundCap
            PathAngleArc {
                centerX: track.width / 2
                centerY: track.height / 2
                radiusX: 62
                radiusY: 62
                startAngle: 135
                sweepAngle: 270
                moveToStart: true
            }
        }
        ShapePath {
            strokeWidth: 11
            strokeColor: root.accent
            fillColor: "transparent"
            capStyle: ShapePath.RoundCap
            PathAngleArc {
                centerX: track.width / 2
                centerY: track.height / 2
                radiusX: 62
                radiusY: 62
                startAngle: 135
                sweepAngle: 270 * Math.max(0, Math.min(1, root.value / 100))
                moveToStart: true
            }
        }
    }

    Text {
        anchors.horizontalCenter: track.horizontalCenter
        anchors.verticalCenter: track.verticalCenter
        anchors.verticalCenterOffset: -4
        text: Math.round(root.value) + root.suffix
        color: NoopTheme.textPrimary
        font.family: BrandFont
        font.pixelSize: 34
        font.weight: Font.Bold
        font.letterSpacing: -1.2
    }
    Text {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.top: track.bottom
        anchors.topMargin: 4
        text: root.label
        color: NoopTheme.textPrimary
        font.family: BrandFont
        font.pixelSize: 17
        font.weight: Font.DemiBold
    }
    Text {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        text: root.state
        color: root.accent
        font.family: BrandFont
        font.pixelSize: 10
        font.weight: Font.Bold
        font.letterSpacing: 1.3
    }
}
