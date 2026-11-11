# Android concurrent collection draft

`SourceCoordinator` can retain one paired Oura source beside the existing WHOOP client. Selecting
WHOOP or Oura does not stop the other transport. Other non-WHOOP source kinds remain exclusive.
The registry still has one active row. That row is a foreground selection, not a second active row.

- Oura persistence retains its own device ID. Background Oura HR/battery does not feed the shared
  foreground sink. Ring-primary startup re-enables the WHOOP connection disabled by bootstrap.
- Archiving or forgetting the companion takes effect on the next coordinator reconciliation.
- The history transport reuses the outstanding-request/final-summary admission fix from fork PR #138.
  It also arms a 10-second response timeout for each GetEvents request, retains the existing 1.5-second
  inter-record quiet interval, and enforces the existing 300-second drain limit with an independent
  timer. Missing summaries cannot hold FetchingHistory forever. Seen records steer continuation;
  only anchored stored records may advance the durable cursor. No response means no invented progress.
- Restore the Kotlin cursor bookkeeping already present in Swift for history-only temp/SpO2/HRV/resp/
  motion samples, including samples parked until an anchor arrives. Previously only hypnograms updated
  that high-water mark. Possibly-live IBI and wall-clock fallback timestamps still cannot advance it.
- Automatic background collection does not consume an adopt-consent marker.

Implementation: `android/app/src/main/java/com/noop/ble/SourceCoordinator.kt` and `OuraLiveSource.kt`.
Regression tests: `SourceCoordinatorConcurrentTest`, `OuraHistoryRecoveryTest`, and the existing
`SourceCoordinatorAdoptionTest` / `OuraHistoryDrainTest`. The recovery tests use a synthetic TLV
fixture through notification parsing, stream mapping, and an in-memory Room database.

```sh
cd android
./gradlew :app:testFullDebugUnitTest --tests 'com.noop.ble.SourceCoordinator*' --tests '*OuraHistory*'
```

This is not a hardware-verified release. The 10-second initial-response allowance needs ring testing.
Outstanding work includes per-metric primary selection and Today UI, simultaneous Devices status,
address rediscovery after repeated connection failures, failed-adopt registry rollback, Swift twins
for concurrency/timeouts, and real-device HR/sleep readback. The shared foreground live-state design
still needs review when Oura is selected while WHOOP sends live updates. Do not infer any phone result
from the JVM tests. No schema, auth key, destructive command, scoring formula, or install changes.
