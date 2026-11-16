"""Stdlib tests for the breath-camera evaluation protocol (runs in the tools-python leg)."""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "breathcam"))
import protocol  # noqa: E402


class ScheduleTest(unittest.TestCase):
    def test_blocks_cover_three_paces_and_holds(self):
        s = protocol.schedule()
        rates = {seg["nominal_bpm"] for seg in s if seg["phase"] != "free"}
        self.assertEqual({10.0, 6.0, 4.0, round(60 / 16, 4)}, rates)
        self.assertIn("hold", {seg["phase"] for seg in s})
        # Contiguous, no gaps or overlaps.
        for a, b in zip(s, s[1:]):
            self.assertAlmostEqual(a["end_s"], b["start_s"], places=6)

    def test_truth_lookup(self):
        s = protocol.schedule()
        phases = [(protocol.truth_at(s, t) or {}).get("phase") for t in (10.0, 31.0, 34.5)]
        self.assertEqual(["free", "inhale", "exhale"], phases)
        self.assertIsNone(protocol.truth_at(s, 10_000.0))


class MarkerTest(unittest.TestCase):
    def _clip(self, lead_silence_s: float, rate: int) -> list[float]:
        # Re-render the marker at the clip's audio rate: beeps then the zero beep, with room noise.
        import math
        import random
        rnd = random.Random(3)
        out = [0.0] * int(lead_silence_s * rate)

        def tone(f, sec):
            return [0.5 * math.sin(2 * math.pi * f * i / rate) for i in range(int(sec * rate))]

        for _ in range(3):
            out += tone(protocol.MARKER_BEEP_HZ, protocol.MARKER_BEEP_S)
            out += [0.0] * int((1.0 - protocol.MARKER_BEEP_S) * rate)
        out += tone(protocol.MARKER_ZERO_HZ, protocol.MARKER_ZERO_S)
        out += [0.0] * rate
        return [v + 0.01 * rnd.gauss(0, 1) for v in out]

    def test_finds_time_zero_at_two_offsets(self):
        for lead in (1.25, 6.5):
            clip = self._clip(lead, 8000)
            z = protocol.find_zero(clip, 8000)
            assert z is not None
            self.assertAlmostEqual(lead + 3.0, z, delta=0.05)

    def test_no_marker_no_zero(self):
        self.assertIsNone(protocol.find_zero([0.0] * 8000 * 5, 8000))


if __name__ == "__main__":
    unittest.main()
