"""Safety/orchestration tests for the issue-#423 WHOOP 5/MG probe.

All BLE and time seams are fake. These tests must never scan, connect, or write to real hardware.
"""

import argparse
import asyncio
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
import types
import unittest

# Keep the test importable in the documented stdlib-only unit-test path. The production CLI still
# requires bleak; injected fakes below provide every runtime seam this module exercises.
try:
    import bleak  # noqa: F401
except ModuleNotFoundError:
    fake_bleak = types.ModuleType("bleak")
    fake_bleak.BleakClient = object
    fake_bleak.BleakScanner = object
    sys.modules["bleak"] = fake_bleak

import whoop_frame as wf
import whoop_raw_accel_probe as probe


class FakeDevice:
    address = "FA:KE:00:00:00:01"
    name = "WHOOP TEST"


class FakeScanner:
    @staticmethod
    async def find_device_by_address(_address, timeout):
        assert timeout == 20.0
        return FakeDevice()

    @staticmethod
    async def find_device_by_filter(_predicate, timeout):
        assert timeout == 20.0
        return FakeDevice()


class FakeClient:
    def __init__(self, *, emit_raw=False, emit_setup=False, fail_arm=False, fail_disarm=False):
        self.emit_raw = emit_raw
        self.emit_setup = emit_setup
        self.fail_arm = fail_arm
        self.fail_disarm = fail_disarm
        self.is_connected = True
        self.commands = []
        self.callbacks = {}

    async def __aenter__(self):
        return self

    async def __aexit__(self, _exc_type, _exc, _tb):
        self.is_connected = False

    async def start_notify(self, characteristic, callback):
        self.callbacks[characteristic.lower()] = callback

    def _emit(self, frame):
        characteristic = probe.WHOOP5_NOTIFY[0]
        sender = types.SimpleNamespace(uuid=characteristic)
        self.callbacks[characteristic](sender, bytearray(frame))

    async def write_gatt_char(self, _characteristic, frame, response):
        assert response is True
        command = frame[10]
        self.commands.append(command)
        if command == 145:
            if self.emit_setup:
                # Deliberately sensitive-looking non-target bytes: the recorder must hash, not retain.
                self._emit(wf.build_puffin_command(
                    145, seq=1, payload=b"serial-and-session-token", type_=wf.COMMAND_RESPONSE_TYPE))
            return
        if command == wf.PUFFIN_CMD_START_RAW_DATA:
            if self.fail_arm:
                raise RuntimeError("synthetic arm failure")
            self._emit(wf.build_puffin_command(
                command, seq=2, payload=b"\x00\x01", type_=wf.COMMAND_RESPONSE_TYPE))
            if self.emit_raw:
                payload = bytes.fromhex("010002000300")
                self._emit(wf.build_puffin_command(
                    0xA5, seq=3, payload=payload, type_=wf.PACKET_REALTIME_RAW_DATA))
            return
        if command == wf.PUFFIN_CMD_STOP_RAW_DATA:
            if self.fail_disarm:
                raise RuntimeError("synthetic disarm failure")
            self._emit(wf.build_puffin_command(
                command, seq=4, payload=b"\x00\x01", type_=wf.COMMAND_RESPONSE_TYPE))

    async def read_gatt_char(self, characteristic):
        return {
            probe.MODEL_NUMBER: b"MG\x00",
            probe.FIRMWARE_REVISION: b"50.test.0\x00",
            probe.HARDWARE_REVISION: b"WS50_test\x00",
            probe.SERIAL_NUMBER: b"TEST-SERIAL\x00",
        }[characteristic]


class PlannedSleep:
    def __init__(self, active_error=None):
        self.calls = 0
        self.active_error = active_error

    async def __call__(self, _seconds):
        self.calls += 1
        if self.calls == 2 and self.active_error is not None:
            raise self.active_error


class RawAccelProbeSafetyTests(unittest.TestCase):
    def _run(self, client, sleep=None):
        directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        out = directory / "probe.json"
        args = argparse.Namespace(
            address=FakeDevice.address,
            name_filter="WHOOP",
            hardware_model="WHOOP 5.0 MG",
            firmware="50.test.0",
            duration=30.0,
            baseline_seconds=1.0,
            post_seconds=1.0,
            out=str(out),
            acknowledge_research_only=True,
        )
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            code = asyncio.run(probe.run(
                args,
                scanner=FakeScanner,
                client_factory=lambda _device: client,
                sleep=sleep or PlannedSleep(),
            ))
        return code, json.loads(out.read_text()), out

    def test_active_window_failure_still_disarms_and_returns_error(self):
        client = FakeClient()
        code, report, _ = self._run(
            client, PlannedSleep(active_error=RuntimeError("synthetic active failure")))
        self.assertEqual(client.commands, [145, 81, 82])
        self.assertEqual(code, 2)
        self.assertFalse(report["completed"])
        self.assertEqual(report["outcome"], "error")
        self.assertEqual(report["error"]["type"], "RuntimeError")
        self.assertTrue(report["safety"]["disarm_attempted"])
        self.assertTrue(report["safety"]["disarm_completed"])

    def test_active_window_cancellation_still_disarms_and_returns_130(self):
        client = FakeClient()
        code, report, _ = self._run(
            client, PlannedSleep(active_error=asyncio.CancelledError()))
        self.assertEqual(client.commands, [145, 81, 82])
        self.assertEqual(code, 130)
        self.assertTrue(report["cancelled"])
        self.assertFalse(report["completed"])
        self.assertEqual(report["outcome"], "cancelled")
        self.assertTrue(report["safety"]["disarm_attempted"])
        self.assertTrue(report["safety"]["disarm_completed"])

    def test_arm_write_failure_still_attempts_disarm(self):
        client = FakeClient(fail_arm=True)
        code, report, _ = self._run(client)
        self.assertEqual(client.commands, [145, 81, 82])
        self.assertEqual(code, 2)
        self.assertEqual(report["error"]["type"], "RuntimeError")
        self.assertTrue(report["safety"]["disarm_required"])
        self.assertTrue(report["safety"]["disarm_completed"])

    def test_disarm_failure_cannot_report_completion(self):
        client = FakeClient(fail_disarm=True)
        code, report, _ = self._run(client)
        self.assertEqual(client.commands, [145, 81, 82])
        self.assertEqual(code, 2)
        self.assertFalse(report["completed"])
        self.assertEqual(report["outcome"], "incomplete_disarm")
        self.assertTrue(report["safety"]["disarm_attempted"])
        self.assertFalse(report["safety"]["disarm_completed"])
        self.assertEqual(report["safety"]["disarm_error"], "RuntimeError")

    def test_clean_negative_probe_returns_three_with_honest_census(self):
        code, report, _ = self._run(FakeClient())
        self.assertEqual(code, 3)
        self.assertTrue(report["completed"])
        self.assertEqual(report["outcome"], "no_type43_observed")
        self.assertEqual(report["census"]["active"]["type43_crc_valid_count"], 0)

    def test_target_raw_succeeds_non_target_is_redacted_and_file_is_private(self):
        code, report, out = self._run(FakeClient(emit_raw=True, emit_setup=True))
        self.assertEqual(code, 0)
        self.assertTrue(report["completed"])
        self.assertEqual(report["outcome"], "type43_observed")
        self.assertEqual(report["census"]["active"]["type43_crc_valid_count"], 1)
        self.assertTrue(report["sensitive_data"]["contains_health_data"])
        self.assertEqual(stat.S_IMODE(out.stat().st_mode), 0o600)

        setup = [record for record in report["frames"] if record["phase"] == "setup"]
        self.assertEqual(len(setup), 1)
        self.assertNotIn("hex", setup[0])
        self.assertIn("frame_sha256", setup[0])
        self.assertEqual(setup[0]["raw_retention"], "redacted_non_target")
        self.assertNotIn("serial-and-session-token", out.read_text())

        target = [
            record for record in report["frames"]
            if record["packet_type"] == wf.PACKET_REALTIME_RAW_DATA
        ]
        self.assertEqual(len(target), 1)
        self.assertIn("hex", target[0])
        self.assertEqual(target[0]["candidate"]["triplet_count"], 1)


if __name__ == "__main__":
    unittest.main()
