#!/usr/bin/env python3
"""Breath-camera evaluation protocol: the paced-breathing guide track and its ground-truth schedule.

The owner records short front-camera clips of himself following this guide; the clip's own audio track
carries the guide, so `prepare_clip.py` can find protocol time zero in the recording and label every frame
from the schedule (the metronome IS the ground truth). See docs/experiments/BREATH_CAMERA.md.

Stdlib only (wave/struct/math) so it runs anywhere and in the tools-python CI leg.

    python3 Tools/breathcam/protocol.py --write-wav guide.wav      # the track to play while recording
    python3 Tools/breathcam/protocol.py --print                     # the schedule as JSON
"""
from __future__ import annotations

import argparse
import json
import math
import struct
import sys
import wave

PROTOCOL_ID = "noop-breathcam-v1"
SAMPLE_RATE = 16_000

# Start marker: three short 1 kHz beeps one second apart, then a long 1.5 kHz beep whose ONSET is t = 0.
MARKER_BEEP_HZ = 1000.0
MARKER_ZERO_HZ = 1500.0
MARKER_BEEP_S = 0.15
MARKER_ZERO_S = 0.30
MARKER_LEAD_S = 3.0  # first beep is this long before t = 0

# Guide tones: inhale sweeps up, exhale sweeps down, holds are silent.
LOW_HZ = 220.0
HIGH_HZ = 440.0
AMPLITUDE = 0.25

# (label, phases) blocks. Each phase is (name, seconds); a block repeats its cycle `cycles` times.
BLOCKS: list[dict] = [
    {"name": "settle", "cycle": [("free", 30.0)], "cycles": 1},
    {"name": "pace10", "cycle": [("inhale", 3.0), ("exhale", 3.0)], "cycles": 9},
    {"name": "pace6", "cycle": [("inhale", 4.0), ("exhale", 6.0)], "cycles": 9},
    {"name": "pace4", "cycle": [("inhale", 6.0), ("exhale", 9.0)], "cycles": 6},
    {"name": "box4", "cycle": [("inhale", 4.0), ("hold", 4.0), ("exhale", 4.0), ("hold", 4.0)], "cycles": 5},
]


def schedule() -> list[dict]:
    """Every phase with its start/end in protocol seconds. `free` segments carry no truth."""
    out: list[dict] = []
    t = 0.0
    for block in BLOCKS:
        cycle_s = sum(d for _, d in block["cycle"])
        for c in range(block["cycles"]):
            for name, dur in block["cycle"]:
                out.append({
                    "block": block["name"],
                    "phase": name,
                    "start_s": round(t, 3),
                    "end_s": round(t + dur, 3),
                    "nominal_bpm": None if name == "free" else round(60.0 / cycle_s, 4),
                    "cycle": c,
                })
                t += dur
    return out


def truth_at(sched: list[dict], t_s: float) -> dict | None:
    for seg in sched:
        if seg["start_s"] <= t_s < seg["end_s"]:
            return seg
    return None


def _tone(freq0: float, freq1: float, seconds: float, amp: float) -> list[float]:
    n = int(seconds * SAMPLE_RATE)
    out = []
    phase = 0.0
    for i in range(n):
        f = freq0 + (freq1 - freq0) * i / max(1, n - 1)
        phase += 2 * math.pi * f / SAMPLE_RATE
        # 20 ms raised-cosine fade at both ends so cues do not click.
        edge = min(i, n - 1 - i) / (0.02 * SAMPLE_RATE)
        env = 1.0 if edge >= 1 else 0.5 - 0.5 * math.cos(math.pi * edge)
        out.append(amp * env * math.sin(phase))
    return out


def guide_samples() -> list[float]:
    """The full guide: start marker, then one cue per scheduled phase."""
    s: list[float] = []
    for k in range(3):
        s += _tone(MARKER_BEEP_HZ, MARKER_BEEP_HZ, MARKER_BEEP_S, 0.5)
        s += [0.0] * int((1.0 - MARKER_BEEP_S) * SAMPLE_RATE)
    # t = 0 at the onset of the long beep.
    zero = _tone(MARKER_ZERO_HZ, MARKER_ZERO_HZ, MARKER_ZERO_S, 0.5)
    for seg in schedule():
        dur = seg["end_s"] - seg["start_s"]
        if seg["phase"] == "inhale":
            cue = _tone(LOW_HZ, HIGH_HZ, dur, AMPLITUDE)
        elif seg["phase"] == "exhale":
            cue = _tone(HIGH_HZ, LOW_HZ, dur, AMPLITUDE)
        else:
            cue = [0.0] * int(dur * SAMPLE_RATE)
        if zero:
            # Mix the zero beep over the first cue (the settle block is silent anyway).
            for i, v in enumerate(zero):
                if i < len(cue):
                    cue[i] += v
            zero = []
        s += cue
    s += [0.0] * SAMPLE_RATE
    return s


def write_wav(path: str, samples: list[float]) -> None:
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in samples))


def goertzel_power(block: list[float], freq: float, rate: int) -> float:
    """Power of one frequency in a block (Goertzel)."""
    k = 2 * math.cos(2 * math.pi * freq / rate)
    s1 = s2 = 0.0
    for x in block:
        s0 = x + k * s1 - s2
        s2, s1 = s1, s0
    return s1 * s1 + s2 * s2 - k * s1 * s2


def find_zero(samples: list[float], rate: int) -> float | None:
    """Seconds into [samples] where protocol t = 0 starts: the first strong 1.5 kHz onset that follows
    three 1 kHz beeps. Returns None when the marker is not found (clip started too late, audio muted)."""
    hop = int(0.01 * rate)
    win = int(0.04 * rate)
    beep, zero = [], []
    for i in range(0, max(0, len(samples) - win), hop):
        blk = samples[i:i + win]
        energy = sum(x * x for x in blk) + 1e-12
        beep.append(goertzel_power(blk, MARKER_BEEP_HZ, rate) / energy / win)
        zero.append(goertzel_power(blk, MARKER_ZERO_HZ, rate) / energy / win)
    on = 0.25  # fraction of block energy in the probe frequency
    beeps: list[int] = []
    for j in range(1, len(beep)):
        if beep[j] > on >= beep[j - 1]:
            beeps.append(j)
    for j in range(1, len(zero)):
        if zero[j] > on >= zero[j - 1]:
            prior = [b for b in beeps if 0 < (j - b) * hop / rate <= MARKER_LEAD_S + 0.5]
            if len(prior) >= 3:
                return j * hop / rate
    return None


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--write-wav", metavar="PATH")
    ap.add_argument("--print", action="store_true")
    a = ap.parse_args(argv)
    if a.write_wav:
        write_wav(a.write_wav, guide_samples())
        total = schedule()[-1]["end_s"]
        print(f"wrote {a.write_wav}: {PROTOCOL_ID}, {MARKER_LEAD_S:.0f} s marker + {total:.0f} s protocol")
    if a.print or not a.write_wav:
        print(json.dumps({"protocol": PROTOCOL_ID, "schedule": schedule()}, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
