# NOOP Linux Qt performance control

This is the Qt 6/QML branch of the Linux UI spike. It implements representative
**Today** and **Live** surfaces in NOOP's current design language and, more
importantly, makes frame pacing measurable on the 540.16498 Hz output.

It is intentionally a control, not a second application architecture. The
screens use mock values until the Linux capture/store boundary is stable.

## Build

Requirements: Qt 6.5+ with Core, Gui, Qml, Quick and QuickControls2; CMake 3.24+;
and Ninja (or another CMake generator).

```sh
cmake -S linux/qt-spike -B build/qt-spike -G Ninja \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build/qt-spike --parallel
```

### Human-operated native benchmark only

The following opens a real window. Only the person operating the desktop should
run it, after choosing the intended workspace/output. Agents and CI must never
use this command or inherit a live `DISPLAY`/`WAYLAND_DISPLAY`.

```sh
QSG_RENDER_LOOP=threaded QSG_RHI_BACKEND=opengl \
  ./build/qt-spike/noop-qt-spike
```

The harness enumerates every `QScreen` and selects the highest-refresh output.
Override that choice with `NOOP_TARGET_SCREEN=DP-1` (using Qt's reported screen
name) when reproducing a specific run.

CI/agent visual checks must stay off the live compositor and explicitly remove
both display variables:

```sh
env -u DISPLAY -u WAYLAND_DISPLAY \
  QT_QPA_PLATFORM=offscreen QSG_RHI_BACKEND=software QSG_RENDER_LOOP=basic \
  NOOP_SCREENSHOT_PATH=/tmp/noop-qt-spike.png \
  ./build/qt-spike/noop-qt-spike
```

Set `QSG_INFO=1` while diagnosing backend selection. Vulkan is not required:
Qt Quick's Rendering Hardware Interface maps the same retained scene graph to
OpenGL or Vulkan. Both NVIDIA OpenGL and Vulkan runtime paths are available on
the development host. CMake reports that standalone Vulkan development headers
are absent, which only blocks adding direct Vulkan code; this spike uses Qt's
RHI and does not include Vulkan headers. Compare `QSG_RHI_BACKEND=opengl` and
`QSG_RHI_BACKEND=vulkan` with the same benchmark contract.

## Architecture represented here

```text
BLE/capture thread
  -> bounded timestamped sample ring
  -> analytics worker(s)
  -> immutable display snapshot at sensor/feature rate
  -> Qt GUI properties
  -> retained Qt Quick scene graph
  -> threaded RHI renderer at presentation rate
```

`LivePlotItem` is the critical proof point. It tessellates a constant-width
polyline into triangles in `updatePaintNode()`. There is no `Canvas`, JavaScript
per-point drawing, or per-frame model churn. Its geometry rebuilds only when a
sample arrives (62.5 Hz in the synthetic feed); Qt retains and recomposes that
geometry for intervening 540 Hz presentations.

The small connection-status pulse uses Qt's `OpacityAnimator`, which runs in the
scene-graph render thread. It keeps the benchmark presenting frames without
driving a 540 Hz GUI-thread timer.

Benchmark **Continuous ON** additionally chains `frameSwapped` to the next
`QQuickWindow::update()` through one queued event. This self-paces against the
swapchain instead of relying on the platform animation-timer cadence. Turn it
off for normal damage-driven power measurements; it is deliberately on for the
worst-case 540 Hz frame-pacing control.

`FrameTelemetry` listens at `QQuickWindow::afterFrameEnd`. Its render-thread
callback performs only a monotonic timestamp read and atomic writes into a
fixed 16,384-entry SPSC ring. Percentiles, allocation, sorting, strings, and QML
notifications run on the GUI thread twice per second.

## Integration seams

1. Replace `LivePlotItem`'s demo timer with `appendSample()` calls from an
   immutable Linux capture snapshot. Never call QML once per raw PPG/IMU sample.
2. Keep BLE, decoding, storage, and analytics outside the GUI thread. Publish a
   bounded/coalesced view model; dropping superseded display snapshots is safer
   than building an unbounded UI queue.
3. Reuse the repository's protocol/store test vectors. This UI spike does not
   reinterpret WHOOP packets.
4. Preserve `sample_time`, `received_time`, source identity, quality, and loss
   metadata through the bridge; charts should plot acquisition time rather than
   widget arrival time.
5. Only invalidate the sub-item whose data changed. The Today trace and Live
   readout are deliberately leaf items, matching the isolation already used by
   NOOP's SwiftUI Live screen.

See [BENCHMARK.md](BENCHMARK.md) for the measurement contract and pass/fail
thresholds.
