import QtQuick
import QtQuick.Controls

Button {
    id: root
    property bool selected: false
    property string symbol: ""

    implicitHeight: 48
    font.family: BrandFont
    font.pixelSize: 15
    font.weight: selected ? Font.DemiBold : Font.Medium

    contentItem: Row {
        spacing: 12
        leftPadding: 2
        Text {
            width: 22
            text: root.symbol
            color: root.selected ? NoopTheme.accent : NoopTheme.textTertiary
            font.family: BrandFont
            font.pixelSize: 17
            anchors.verticalCenter: parent.verticalCenter
            horizontalAlignment: Text.AlignHCenter
        }
        Text {
            text: root.text
            color: root.selected ? NoopTheme.textPrimary : NoopTheme.textSecondary
            font: root.font
            anchors.verticalCenter: parent.verticalCenter
        }
    }

    background: Rectangle {
        radius: 12
        color: root.selected ? NoopTheme.accentMuted
                             : root.hovered ? Qt.rgba(1, 1, 1, 0.035) : "transparent"
        border.width: root.activeFocus ? 1 : 0
        border.color: NoopTheme.accent
    }
}
