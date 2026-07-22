# NOOP Linux desktop spike

This is an intentionally isolated Compose Multiplatform Desktop project. It does not configure the
Android application, so a Linux contributor can build the UI without an Android SDK. It uses the
repository's existing Gradle wrapper:

```bash
GRADLE_USER_HOME=/tmp/noop-desktop-gradle ../android/gradlew -p . test
```

Run that command from this `desktop/` directory. Automated and agent checks must stop at `test`: do
not invoke the `run` task against the user's active `DISPLAY` or `WAYLAND_DISPLAY`. Human visual and
performance runs belong in an explicitly selected isolated compositor/session. In that environment,
`run --args=--benchmark` starts with the frame-pacing panel
visible. The panel targets the development monitor's measured 540.16498 Hz mode (1.851 ms/frame),
but reports Compose frame-clock intervals rather than compositor presentation timestamps. Use it to
compare UI changes; use PresentMon/Wayland presentation feedback for final end-to-end validation.

## Reuse boundary

`model/AppContract.kt` has no Compose, Android, persistence, or BLE dependencies. Screens consume
immutable `NoopUiState` snapshots and emit typed `NoopIntent` values. `DemoUiStore` proves that the
desktop renderer can run without Android services. The production extraction should move the contract
to a shared Kotlin module and provide separate Android and Linux stores behind `NoopUiStore`.

The first rendered slice covers the desktop shell, Today metrics, and the Live waveform. It deliberately
does not copy Android `AppViewModel`, navigation, resources, preferences, or BLE types into Desktop.
