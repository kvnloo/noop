# 540 Hz benchmark contract

The verified display mode is **540.16498 Hz**, or **1.851286 ms per refresh**.
The number is an output budget, not a promise that every part of the application
must update 540 times per second. Sensor state changes at its native rate while
the retained scene graph can present smoothly at the display rate.

## Controlled run

1. Build `Release`; disable sanitizers, Qt debug logging, screen recording, and
   unrelated GPU-heavy applications.
2. Run natively in the active Hyprland session with
   `QSG_RENDER_LOOP=threaded`. Record the reported RHI backend and refresh rate.
3. Put the window fully on the 540 Hz output, unoccluded and not minimized. Open
   **Live** so the plot and telemetry card are visible, and confirm
   **Continuous ON** for the frame-capacity run.
4. Warm up for 30 seconds, press **Reset run**, then collect at least 30,000
   intervals (about 55.5 seconds at target rate). Repeat five times.
5. Save console `frame-control` summaries plus CPU/GPU clocks, utilization, and
   power state. Compare medians across runs, not the single best run.

## Acceptance gates

| Signal | Gate |
|---|---:|
| `QScreen::refreshRate()` | 530–550 Hz |
| interval samples | ≥ 30,000 per run |
| p50 frame interval | ≤ 1.8883 ms (budget + 2% clock tolerance) |
| p95 frame interval | ≤ 1.8883 ms |
| p99 frame interval | ≤ 1.8883 ms |
| intervals > 102% budget | ≤ 1% |
| >10 ms stalls | zero after warm-up |

The in-app `ON 540 HZ BUDGET` indicator applies all three percentile gates after a short
collection period. The full acceptance decision still uses the 30,000-interval
run and five-run repeatability requirement.

## Scenarios

Run the gates independently for:

- Today idle with the live trace visible;
- Today continuous scroll;
- Live idle with the synthetic 62.5 Hz sample feed;
- Live while a real WHOOP history sync writes in the background;
- window resize and screen-scale transition (diagnostic only; transient misses
  do not count against steady-state but must not crash or grow a queue).

Repeat Today-idle and Live-idle with **Continuous OFF** as the power/damage
control. Those runs are not expected to emit a frame when nothing changes and
therefore are not judged against 540 continuously presented frames.

## Interpretation

- A stable interval near 3.70 ms means the compositor/application is presenting
  every other vblank (~270 Hz), not that the plot math is slow.
- Low CPU/GPU utilization with a lower cap usually points to compositor policy,
  VRR, damage tracking, or RHI/backend selection.
- Rising latency or memory during sync points to an unbounded bridge or store
  contention; adding UI threads will not fix it.
- CUDA is not part of this rendering path. It belongs in offline model training
  or sufficiently large analytics batches; tiny real-time feature windows should
  remain on CPU unless profiling proves transfer overhead is amortized.

This telemetry measures application frame-end spacing. Final input-to-photon and
scan-out verification requires compositor presentation timestamps or external
high-speed capture; do not describe this probe alone as end-to-end latency.
