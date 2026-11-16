#!/usr/bin/env python3
"""Prepare a recorded breath-camera clip for the Kotlin evaluator (BreathClipEvalTest).

    python3 Tools/breathcam/prepare_clip.py CLIP.mp4 --out DIR [--label owner-day1-lamp]

Needs ffmpeg on PATH. Writes DIR/<label>.gray (8-bit luminance frames, 240 px wide upright, 15 fps — the
same size the live analyzer sees) and DIR/<label>.json (size, fps, and where protocol t = 0 sits in the
clip, found from the guide's start marker in the clip's own audio). Video stays on this machine: never
commit clips or .gray files. Delete the .gray after evaluating (`--cleanup` on run_eval.sh does).
"""
from __future__ import annotations

import argparse
import json
import os
import struct
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import protocol  # noqa: E402

WIDTH = 240
HEIGHT = 320
FPS = 15
AUDIO_RATE = 8000
MARKER_SEARCH_S = 90


def audio_head(clip: str) -> list[float]:
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", clip, "-t", str(MARKER_SEARCH_S), "-vn", "-ac", "1",
         "-ar", str(AUDIO_RATE), "-f", "s16le", "-"],
        check=True, capture_output=True,
    ).stdout
    n = len(raw) // 2
    return [v / 32768.0 for v in struct.unpack(f"<{n}h", raw[: 2 * n])]


def write_frames(clip: str, out_path: str) -> int:
    # Scale to a fixed upright 240x320 (pad if the aspect differs) at a constant 15 fps, grey only.
    vf = (f"fps={FPS},scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
          f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2,format=gray")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", clip, "-an", "-vf", vf, "-f", "rawvideo", out_path],
                   check=True)
    return os.path.getsize(out_path) // (WIDTH * HEIGHT)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("clip")
    ap.add_argument("--out", required=True)
    ap.add_argument("--label")
    ap.add_argument("--zero-s", type=float, help="override: protocol t = 0 in clip seconds (no marker search)")
    a = ap.parse_args(argv)
    os.makedirs(a.out, exist_ok=True)
    label = a.label or os.path.splitext(os.path.basename(a.clip))[0]
    zero = a.zero_s
    if zero is None:
        zero = protocol.find_zero(audio_head(a.clip), AUDIO_RATE)
        if zero is None:
            print(f"{a.clip}: start marker not found in the first {MARKER_SEARCH_S} s of audio; "
                  "re-record with the guide audible, or pass --zero-s", file=sys.stderr)
            return 2
    frames = write_frames(a.clip, os.path.join(a.out, label + ".gray"))
    meta = {
        "protocol": protocol.PROTOCOL_ID,
        "label": label,
        "width": WIDTH,
        "height": HEIGHT,
        "fps": FPS,
        "frames": frames,
        "zero_ms": int(round(zero * 1000)),
        "schedule": protocol.schedule(),
    }
    with open(os.path.join(a.out, label + ".json"), "w") as f:
        json.dump(meta, f, indent=1)
    print(f"{label}: {frames} frames, protocol t=0 at {zero:.2f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
