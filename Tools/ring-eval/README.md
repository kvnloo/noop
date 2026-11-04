# Ring recording replay

Private recordings stay on the device or in the active profile's scratch/ring-rec directory. Never add CSVs, raw frames, pairing tokens or ring keys to Git. This tool compiles and executes OuraTapDetector.kt itself; Python only reads annotations and scores its outputs. It does not run Gradle.

Run with kotlinc on PATH:

    python3 Tools/ring-eval/evaluate.py /private/ring-rec --output /private/eval.json

Alternatively pass --compiler-lib /path/to/gradle/lib to use an already cached Kotlin compiler and stdlib with Java. This invokes the Kotlin CLI, NOT Gradle. The output contains old (cd749dc9 constants), new (current production defaults), candidate (rejected longer quiet tail/motion exception experiment), and off (original constants, stillness disabled). No candidate is silently promoted to production.

## Annotation contract and limitations

- f rows are raw frames in arrival order. Old captures replay each decoded XYZ at max(frame arrival, preceding sample + 20 ms), matching the intended Android sample-clock policy. Historical Android main-thread scheduling is unavailable: old replay is approximate, not an exact hardware reproduction.
- New s rows preserve actual detector sample timestamps, phone elapsed timestamps and XYZ. When present these, not reconstructed f times, drive replay. Label scoring maps sample times back to phone elapsed time; broker labels use elapsed_ms = broker_ts * 1000 - clock_offset_ms. The sidecar records the start-exchange midpoint offset and network RTT uncertainty. Label times are broker acceptance times, not acoustic utterance onset.
- Guided c rows give one requested repetition in [cue, cue + 2500 ms], capped to available data. TAPS blocks with 2/3 taps are positives; 4/5 are rejection windows. NEGATIVE block start/end/abort boundaries are explicit baseline. Walking versus handling comes from block IDs. TAPS are classified as still per that plan's instructions, not independently measured activity.
- v1 m rows labelled normal are the documented twenty manual double marks. The tool uses [mark - 1000, mark + 350 ms] as a disclosed weak matching window; hand-mark timing is uncertain. Results are in activity unknown, not walking/still. Do not confuse these scores with earlier reports using different matching windows. Unmarked files/intervals are replayed but not invented as baseline.
- Matching is one-to-one per annotation window; extra emissions are false positives. Confusion counts double predicted in a triple window or vice versa divided by annotated positive windows. Precision is calculated only over scored windows and explicit negatives. Unscored detections remain visible per file.
- Voice labels define SEGMENTS, not individual attempts. It is mathematically impossible to infer recall from “doing doubles” followed by an unknown number of gestures without independent attempt counts. Live counters are detections only. The tool reports segment detections separately and leaves recall null. Add a reviewed attempts array to a private sidecar to establish repetition-level ground truth, each item {elapsed_ms, label, activity}; each gets a 1500 ms scoring window. Do not use the detector's own emissions as ground truth.
- Notes containing walking/still update activity from that label forward. Session note supplies initial activity. All other activity is unknown.
- Latency is decision sample time minus the detector's last inferred impact peak, NOT measured physical contact/audio latency. Legacy sampling/BLE jitter remains a limitation. Median is reported by activity.
- False fires per 10 min is an extrapolation from the actual baseline duration shown in baseline_ms. A fourteen-second walking baseline cannot certify a ten-minute rate.

## Recording through voice (after coordinated deployment)

Keep NOOP's paired Always listen enabled and foreground notification present. In a z0live call say “start ring recording”, then “walking now”, then “double”. Perform repeated doubles while walking; say “doing triples” to switch; “nothing” for negatives. Say “recording status” for counts, “undo” to remove the last label, and “stop recording” to save. An owner-reported miss is a note, never a fabricated attempt/detection. Baseline-only: “record baseline”, walk/sit/type/talk, “stop recording”. A hard ten-minute timeout restores normal actions. Stop and start a new session for another activity.

Both broker and NOOP suppress double/triple ACTIONS while recording; recognition and private logging continue. NOOP stores acm-*.csv plus acm-*.labels.json under files/diagnostics/oura-acm. Its existing ring capture is reused, with an explicit connectedDevice foreground service and a recording-only bounded partial wake lock. Start does not start/stop a voice call or take microphone ownership. Screen-off/BLE/One UI behavior still requires owner-authorized physical acceptance.

The new APK deliberately does not claim the 95% walking double, 90% triple, <=1 false fire/10 min or <3% confusion targets. Strict stillness is an unresolved promotion blocker: removing it increased observed baseline false fires, while no positive walking recordings exist yet. Do not interpret unchanged production defaults as evidence that walking recall is safe.
