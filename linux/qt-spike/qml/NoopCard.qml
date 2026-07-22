import QtQuick

Rectangle {
    id: root
    default property alias contentData: container.data
    property real padding: 20

    color: NoopTheme.surfaceRaised
    radius: 20
    border.width: 1
    border.color: NoopTheme.hairline

    Item {
        id: container
        anchors.fill: parent
        anchors.margins: root.padding
    }
}
