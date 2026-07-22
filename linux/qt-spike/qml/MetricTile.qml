import QtQuick
import QtQuick.Layouts

Rectangle {
    id: root
    property string label: "Metric"
    property string value: "—"
    property string detail: ""
    property color accent: NoopTheme.accent

    implicitHeight: 132
    radius: 16
    color: NoopTheme.surfaceRaised
    border.width: 1
    border.color: NoopTheme.hairline

    Rectangle {
        width: 4
        height: 34
        radius: 2
        color: root.accent
        anchors.left: parent.left
        anchors.leftMargin: 16
        anchors.top: parent.top
        anchors.topMargin: 18
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 18
        anchors.leftMargin: 32
        spacing: 4
        Text {
            text: root.label.toUpperCase()
            color: NoopTheme.textTertiary
            font.family: BrandFont
            font.pixelSize: 11
            font.weight: Font.DemiBold
            font.letterSpacing: 1.2
        }
        Item { Layout.fillHeight: true }
        Text {
            text: root.value
            color: NoopTheme.textPrimary
            font.family: BrandFont
            font.pixelSize: 30
            font.weight: Font.Bold
            font.letterSpacing: -0.6
        }
        Text {
            text: root.detail
            color: NoopTheme.textTertiary
            font.family: BrandFont
            font.pixelSize: 12
        }
    }
}
