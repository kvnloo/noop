# Breath camera: breath phase from the front camera, fused with ring/strap R-R (Experimental)

Status: **prototype behind a default-off Experimental toggle. No accuracy claim yet.** The method is
validated only on synthetic signals (JVM tests). It must pass the recorded-clip gate below before any
number it produces is presented as more than an estimate, and it never feeds a score.

Android only for now (`com.noop.breathcam`). iOS/macOS parity: not ported in this change. The pure
estimator is platform-neutral and would port to Swift beside an `AVCaptureVideoDataOutput` source;
that is a follow-up once the clip gate has passed, so two unvalidated twins are not maintained.

## 1. Question

Can NOOP's live heart meditation watch the user's breathing with the phone camera, on-device, well
enough to say *breathing in / breathing out / holding* in real time, and combine that with the ring or
strap R-R to give HRV-biofeedback cues that help the user raise their heart-rate oscillation (RSA)?

## 2. What the literature says works on-device

Two families of camera methods exist: **motion** (the chest, shoulders and clavicles move) and
**rPPG** (respiration modulates the colour pulse signal of the face). For respiration, motion wins
clearly, and the cheapest motion methods are within about 0.5 breaths/min of the best.

| Method (source) | Dataset / setting | Resp. rate error | Cost | Licence of usable code/model | Phase? |
|---|---|---|---|---|---|
| Profile‑1D, row-profile shift (Bartula et al. 2013, EMBC, doi:10.1109/EMBC.2013.6610090), as benchmarked by Boccignone et al. 2025 | COHFACE | MAE 0.84–1.29 br/min (PCC 0.58–0.74) | ~0.12 ms/frame (desktop CPU, resPyre) | method only; resPyre code GPL‑3.0 (not used) | waveform → yes |
| same | BP4D+ / MAHNOB‑HCI | MAE 3.07–3.92 / 2.23–3.24 | | | |
| Farnebäck dense optical flow, chest ROI (Boccignone 2025) | COHFACE / BP4D+ | MAE 1.02 / 1.87 | ~4.2 ms/frame | OpenCV Apache‑2.0 | waveform → yes |
| RAFT optical flow, chest ROI (Boccignone 2025) | COHFACE / BP4D+ | MAE 0.64 / 1.40 (best overall) | GPU-class; RAFT‑S ~4 ms/frame on desktop | RAFT BSD‑3 | waveform → yes |
| Google front-camera RR: vertical optical flow of upper torso, 5-region ensemble (Bae et al. 2022, Commun Med 2:40, doi:10.1038/s43856-022-00102-x) | prospective, 50 seated adults, phone propped on a table | **MAE 0.78 ± 0.61 br/min** | **1–4 ms/frame on Pixel 3a/4** at 15 fps, 640×480 | closed (method published) | not reported |
| Pixel-intensity at the pit of the neck, laptop webcam (Massaroni et al. 2019, Sensors 19:2758) | 12 seated volunteers | breath-by-breath bias −0.01 br/min | trivial | method only | breath-by-breath |
| MTTS‑CAN (Liu et al. 2020, NeurIPS) face-video multitask CNN | AFRL / BP4D+ / COHFACE | MAE 2.30 (PCC 0.40) / 3.62–3.88 / 2.31 | **6 ms/frame on RK3399 CPU** | code MIT; weights trained on restricted datasets | weak |
| BigSmall (Narayanswamy et al. 2024, WACV) face multitask | BP4D+ / COHFACE | MAE 3.17–5.19 (PCC 0.14–0.30) / 3.92 | ~5.5 ms/frame | code MIT; BP4D+ weights | weak |
| rPPG-derived (POS/CHROM + EMD/SSA/peaks), Boccignone 2025 | all three | MAE 2.5–6.4, PCC ≤ 0.25 | low | various | no |
| EMSTN (J Biomed Eng 42(3):552, 2025, doi:10.7507/1001-5515.202408026; same table: MTTS‑CAN 3.88, BigSmall 3.39) | BP4D+ | MAE 3.34, PCC 0.22 | n/a | n/a | no |
| CliffPhys (motion + depth, Clifford layers) | COHFACE split / BP4D+ | MAE 0.83 / 3.50 (reported in Boccignone 2025) | needs monocular depth model | research | waveform |
| RRPIPs (2025, point tracking + motion magnification + RAFT) | RGB/NIR/IR | "~1 MAE" | heavy (multi-frame tracker) | code announced | waveform |

Sources for the rows: Boccignone G., Cuculo V., D'Amelio A., Grossi G., Lanzarotti R., Patania S.,
*Remote Respiration Measurement with RGB Cameras: A Review and Benchmark*, ACM Computing Surveys
58(5), 2025, doi:10.1145/3771763, Table 5 and Fig. 7 (benchmark code: github.com/phuselab/resPyre,
GPL‑3.0; its chest ROI comes from the MediaPipe pose landmarker). MTTS‑CAN rows: Liu et al.,
arXiv:2006.03790, Table 1; rPPG‑Toolbox (Liu et al. 2023, NeurIPS D&B; Responsible‑AI licence) Table 5
for BigSmall on BP4D+.

What this means for NOOP:

* **Face rPPG / deep face models are the wrong tool for breathing.** They are 2–4× worse than simple
  chest motion on the same benchmark and barely correlate with the reference (PCC ≤ 0.3), and the
  usable weights are trained on research-only datasets. They also need the face, where breathing
  motion is smallest.
* **Chest/shoulder vertical motion is the right signal**, and Google already shipped and validated it
  on phones (0.78 br/min MAE, 1–4 ms/frame). The vertical component alone is sufficient (Bae 2022).
* Profile‑1D is ~35× cheaper than Farnebäck at a small accuracy cost on the clean dataset, needs **no
  native library** (OpenCV's Android SDK adds tens of MB per ABI), and its 1-D signal maps directly to
  phase. That is the chosen estimator.
* **Nobody reports inhale/exhale/hold phase accuracy for RGB video** on a public dataset. Benchmarks
  report rate only (COHFACE, BP4D+, MAHNOB‑HCI; MMPD/UBFC are pulse datasets; SCAMPS is synthetic).
  Phase is therefore NOOP's own claim to validate, which is why the recorded-clip protocol below exists.
  (A public 4-class phase dataset exists for *wearable* tensometer/accelerometer data — Gdańsk, Sci
  Data 2025, doi:10.1038/s41597-025-04625-5 — useful for the phase logic, not the camera.)

### On-device runtimes considered

| Option | Size cost | Notes |
|---|---|---|
| Pure Kotlin Profile‑1D on the CameraX Y plane (**chosen**) | CameraX only (camera-core/camera2/lifecycle, Apache‑2.0, ~1–2 MB) | measured cost is logged on device (`BreathCam` tag); no model file |
| MediaPipe Pose Landmarker lite (BlazePose GHUM, Apache‑2.0) for automatic chest ROI | tasks-vision AAR 17.2 MB + 5.8 MB model | ~20 ms/frame on a Pixel 3 GPU; landmark jitter is larger than a breath at arm's length, so use it for ROI placement, not as the signal. Follow-up if fixed framing proves too fiddly. |
| MediaPipe BlazeFace short-range (224 KB) to place the ROI under the face | tasks-vision AAR | cheaper ROI placement option |
| OpenCV Farnebäck / LK via OpenCV Android SDK | tens of MB native per ABI | 35× the compute of Profile‑1D for ~0.2 br/min on COHFACE |
| MTTS‑CAN / BigSmall via LiteRT / ONNX Runtime Mobile | few MB | worse accuracy for respiration; dataset-licence questions |

Garden OS (`products/garden_room`) uses OpenCV 4.11 (Apache‑2.0) server-side in Python for scan
tracking/RANSAC/undistortion — not on a phone, so nothing to reuse as code; the reusable lesson is the
same "classical geometry first, learned models only where they pay" choice.

## 3. Biofeedback science that shapes the feature

* **Resonance-frequency breathing.** Slow breathing near ~0.1 Hz (adult range 4.5–6.5 br/min)
  maximises heart-rate oscillation; at that frequency heart rate moves **in phase (0°)** with breathing
  and blood pressure moves 180° against it, i.e. the baroreflex resonance (Vaschillo et al. 2002/2006;
  Lehrer & Gevirtz 2014, Front Psychol 5:756, doi:10.3389/fpsyg.2014.00756; Lehrer et al. 2000, Appl
  Psychophysiol Biofeedback 25:177). At ordinary rates HR lags breathing by about half an inhale.
* **Coupling direction.** Heart rate rises on the inhale and falls on the exhale (RSA). That gives a
  sanity check on the camera's polarity and the coupling read-outs.
* **Efficacy.** HRV biofeedback: large pre/post and between-group effects on stress/anxiety (Goessl et
  al. 2017, Psychol Med 47:2578, Hedges' g ≈ 0.8, 24 studies, n = 484); Lehrer et al. 2020 meta-analysis
  (Appl Psychophysiol Biofeedback 45:109) finds the largest effects for anxiety, depression, anger and
  performance. Voluntary slow breathing alone raises vagally mediated HRV during, right after, and
  after multi-session practice (Laborde et al. 2022, Neurosci Biobehav Rev 138:104711, 223 studies).
* **What the evidence does *not* support (so the UI does not over-promise).**
  * Individual RF vs a fixed 0.1 Hz: no meaningful difference in a 4-week RCT (n = 88; Sci Rep 2026,
    doi:10.1038/s41598-026-53333-6). Simple paced breathing vs full HRV-b in a consumer setting: same
    acute HRV gain (n = 28; Frontiers in Computer Science 2022, doi:10.3389/fcomp.2022.926649).
  * Longer exhale than inhale (1:2) did **not** change HRV vs 1:1 in an original + replication study
    (Meehan & Shaffer 2024, Appl Psychophysiol Biofeedback, doi:10.1007/s10484-024-09637-2).
  * RF is not stable between sessions for most people (changed in 66.7% of 21; Capdevila et al. 2021,
    Sci Rep 11:8400) — if a personal pace is used it should be re-estimated, not locked forever.
  * Methodology papers name the hard part as **making sure the person actually breathed at the target
    rate** (Meehan & Shaffer 2024). That is exactly what a camera can check.
* **Coherence metrics.** HeartMath's coherence ratio: peak power in a 0.030 Hz window around the
  highest peak in 0.04–0.26 Hz over a 64 s window, divided by the remaining power (McCraty et al. 2009;
  HeartMath Institute history, PMC9214473). NOOP's meditation shows the same shape as "rhythm focus"
  without calling it a HeartMath score.

Design consequence: the pacer stays the primary intervention (that is what the evidence backs). The
camera adds what only it can: (1) **adherence** — are you actually following the guide; (2) **holds**,
which R-R cannot see; (3) **per-breath RSA on real breath boundaries** instead of assumed ones, and the
**breath→HR phase lag**, a second, amplitude-free indicator of being near resonance (≈0°); (4) an
**optional** adaptive pacer that probes neighbouring slow paces and keeps the one with the largest
measured RSA — default off, because the RCT evidence for individual RF over 0.1 Hz is weak.

## 4. Camera vs ring-only, and fusion

| Signal | Inhale/exhale | Holds | Rate | Latency | Failure modes |
|---|---|---|---|---|---|
| Camera chest motion (this) | direct (motion sign) | yes (stillness) | breath-by-breath | ~0.5–1 s | talking, fidgeting, framing, dark room, loose clothing |
| Ring/strap R-R (RSA) | indirect: HR rises on inhale, but lags by up to half an inhale except near resonance; BLE delivery delay unknown | **no** (HR drifts slowly during holds) | from HF/LF peak, needs ≥ 30–60 s | several s | PPG beat errors, ectopics; *circular* when the thing being fed back is HR itself |
| Ring accelerometer | finger barely moves with breathing; Oura documents its respiration rate as PPG-derived during sleep | no | sleep only | — | not usable live |
| Phone IMU on the chest | good (validated wearable approach) | yes | yes | low | requires lying down with the phone on the sternum; a possible camera-free fallback mode |

Fusion used here: camera gives breath boundaries and phase; R-R gives heart rate; the coupling tracker
computes RSA per measured breath and the cross-correlation lag (HR beats placed at R-R midpoints; the
estimator's own filter delay removed). A pacer-agreement check fixes the camera's in/out polarity
(and R-R could do the same: HR should rise on the inhale).

## 5. What was built (`android/app/src/main/java/com/noop/breathcam/`)

* `BreathMotion.kt` — `UprightMapping` (CameraX rotation → upright rows, no bitmap rotation),
  `verticalProfile`, `ProfileShiftTracker` (sub-pixel parabolic shift, re-keying), `StripSelector`
  (4 torso regions, breathing-band score, hysteresis), `BreathRegions`.
* `BreathPhaseEstimator.kt` — causal phase {INHALE, EXHALE, HOLD, UNKNOWN}, hold kind, breath-by-breath
  rate, confidence, inhale/exhale durations; motion-artifact and lost-tracking → UNKNOWN;
  `PacerPolarityCheck`.
* `BreathHeartCoupling.kt` — RSA per measured breath, breath→HR lag/phase, `ResonanceSeeker`.
* `BreathCoachSession.kt` — fuses the two streams with a causal 20% R-R gate.
* `BreathCameraPipeline.kt` — frames → estimate, shared by the app and the clip evaluator.
* `BreathCameraSource.kt` — CameraX `ImageAnalysis` only (no preview/capture), 320×240, ~15 fps,
  frames reduced to row profiles and closed; prefs (`experimental.breathCamera`, default false).
* `BreathCameraCard.kt` — the meditation-view card: explicit start, runtime CAMERA permission, a
  visible "Camera on · on this phone only" line while running, phase word, trace, estimates, stop.
* Settings → the experimental block: "Breath camera (experimental)" and "Let my breathing tune the
  guide pace (experimental)".

Privacy contract: frames never leave the process, are never written, and are overwritten in one scratch
buffer frame-by-frame; only a scalar displacement per frame survives, and nothing derived is stored.
The camera runs only between "Watch my breathing" and "Stop camera" (or leaving the view); Android's
own camera indicator is also shown.

## 6. Evidence so far (synthetic only)

JVM tests (`android/app/src/test/java/com/noop/breathcam/`), each recovering **multiple injected
values**, per AGENTS.md:

* rates 4, 5.5, 6, 10, 15 br/min recovered within 0.3 br/min, phase ≥ 92% of frames (0.7 s grace);
* asymmetric breaths 4/6 s and 4/8 s: inhale/exhale durations within 0.6 s;
* holds of 3 s and 5 s after inhale and after exhale detected and told apart; no false holds at
  3.5–6 br/min continuous breathing; inverted polarity recovered; noise + 1.5 px/min drift; a body lurch
  goes UNKNOWN and recovers; lost tracking goes UNKNOWN;
* rendered torso frames at 4 rotations: profile identical across rotations; sub-pixel shifts −2.6…+2.8 px
  recovered to ±0.12 px; end-to-end pipeline recovers 6 and 10 br/min within 0.5 from noisy frames;
* coupling: RSA swings of 4–14 bpm at 6 and 10 br/min recovered within 20%; lags 0, 1.0, 1.5 and 3 s
  recovered within 0.6 s; the resonance seeker settles on 4.5, 5.0, 5.5 and 6.5 br/min for four
  different simulated people; a simulated sitter whose RSA peaks at 5.0 or 6.5 br/min is guided to it;
* the clip evaluator runs end-to-end on a synthetic clip in the real file format.

None of this is evidence about real video. Synthetic frames do not have clothing folds sliding,
auto-exposure, compression, posture sway or talking.

## 7. Recording protocol (the owner records; the guide is the ground truth)

1. Generate the guide track once:
   `python3 Tools/breathcam/protocol.py --write-wav guide.wav` (protocol `noop-breathcam-v1`, 3 s start
   marker + 344 s: 30 s settle, 10 br/min ×9 (3 s in/3 s out), 6 br/min ×9 (4 in/6 out), 4 br/min ×6
   (6 in/9 out), box breathing ×5 (4 in/4 hold/4 out/4 hold)). Rising tone = breathe in, falling tone =
   breathe out, silence = hold.
2. Prop the phone at chest height ~50–80 cm away in portrait, front camera, head and shoulders in the
   upper half of the frame. Play `guide.wav` from a laptop/speaker **audible to the phone's
   microphone** (the start beeps align the clip). Record with the phone's normal camera app, 30 fps.
3. Follow the tones. Do not talk. Start the recording before the beeps.
4. Record **at least 6 clips over ≥ 2 days**, varying: a plain and a patterned top; daylight and a lamp;
   upright chair and slight recline. A second person's clips are worth more than more of yours
   (AGENTS.md: the method must track a varying input across subjects/sessions).
5. Keep the clips local. Never commit them.

Evaluate:

```bash
for c in ~/breathcam-clips/*.mp4; do
  python3 Tools/breathcam/prepare_clip.py "$c" --out ~/breathcam-prepared
done
cd android && BREATHCAM_CLIPS=~/breathcam-prepared ./gradlew testFullDebugUnitTest \
  --tests 'com.noop.breathcam.BreathClipEvalTest'
cat ~/breathcam-prepared/report.json      # per clip, per block: rate error, phase accuracy, coverage,
                                          # hold recall/precision, flip latency, confusion; and the gate
rm ~/breathcam-prepared/*.gray            # ~0.4 GB per clip of raw grey frames
```

### Gate before the feature may leave Experimental

All of, on ≥ 6 clips spanning the varied conditions: per paced block, rate error ≤ 1.0 br/min at 4, 6
and 10 br/min; phase accuracy ≥ 85% with coverage ≥ 80% (0.75 s grace after each cue for human
reaction); hold recall and precision ≥ 80% (1.5 s grace). `report.json` computes `gate.pass`. If it
fails, the result is still published (negative results are evidence) and the estimator stays
Experimental; the first suspects are framing (then add BlazeFace/BlazePose ROI placement) and loose
clothing (then try Farnebäck on the chosen strip).

## 8. Limits

* Rate, phase, RSA and lag are **estimates**; the lag includes an unmeasured Bluetooth delivery delay.
* Needs light and a still, framed sitter; talking and movement go UNKNOWN by design.
* Fixed torso regions assume head-and-shoulders framing; no automatic ROI yet.
* The adaptive pacer optimises measured RSA amplitude only over adherent breaths; evidence that a
  personal pace beats 0.1 Hz for outcomes is weak, which is why it is a second, default-off toggle.
* Display-only: nothing is stored, exported, or used by recovery/readiness.
