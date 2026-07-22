cmake_minimum_required(VERSION 3.24)

project(noop_linux_qt_spike VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(Qt6 6.5 REQUIRED COMPONENTS Core Gui Qml Quick QuickControls2)

qt_standard_project_setup(REQUIRES 6.5)
qt_policy(SET QTP0004 NEW)

qt_add_executable(noop-qt-spike
    src/main.cpp
    src/frametelemetry.cpp
    src/frametelemetry.h
    src/liveplotitem.cpp
    src/liveplotitem.h
)

set_source_files_properties(qml/NoopTheme.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)

qt_add_qml_module(noop-qt-spike
    URI NoopLinux
    VERSION 1.0
    QML_FILES
        qml/Main.qml
        qml/LivePage.qml
        qml/MetricTile.qml
        qml/NavButton.qml
        qml/NoopCard.qml
        qml/NoopTheme.qml
        qml/ScoreGauge.qml
        qml/TodayPage.qml
)

target_link_libraries(noop-qt-spike PRIVATE
    Qt6::Core
    Qt6::Gui
    Qt6::Qml
    Qt6::Quick
    Qt6::QuickControls2
)

target_compile_definitions(noop-qt-spike PRIVATE
    QT_NO_CAST_FROM_ASCII
    QT_NO_CAST_TO_ASCII
)

if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(noop-qt-spike PRIVATE -Wall -Wextra -Wpedantic)
endif()

install(TARGETS noop-qt-spike
    BUNDLE DESTINATION .
    RUNTIME DESTINATION bin
)
