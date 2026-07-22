#!/usr/bin/env python3
"""Bounded, research-only WHOOP 5/MG command-81 raw-frame census (issue #423).

This probe changes one transient variable: after CLIENT_HELLO it sends START_RAW_DATA (81) with
payload [0x01], observes notifications for a bounded window, then sends STOP_RAW_DATA (82) with
payload [0x00] in a finally block. It does not enable command 63/105/106, write device config, trim
history, or persist a guessed sensor value.

Every complete frame contributes to the census, but full bytes are retained only for target type-43
frames and command-81/82 responses. Other frames are hashed so CLIENT_HELLO/session material is not
copied into the report. CRC-valid type-43 bodies are exposed as *candidate* signed-i16 LE triplets,
without axis names, scale, units, or sample-rate claims. A real hardware capture is required before
those semantics can enter the app decoder.
"""

import argparse
import asyncio
from collections import Counter
from datetime import datetime, timezone
import hashlib
from importlib import metadata
import json
import os
from pathlib import Path
import platform
import sys
import tempfile
import time

from bleak import BleakClient, BleakScanner

import whoop_frame as wf


WHOOP5_SERVICE = "fd4b0001-cce1-4033-93ce-002d5875f58a"
WHOOP5_COMMAND = "fd4b0002-cce1-4033-93ce-002d5875f58a"
WHOOP5_NOTIFY = (
    "fd4b0003-cce1-4033-93ce-002d5875f58a",
    "fd4b0004-cce1-4033-93ce-002d5875f58a",
    "fd4b0005-cce1-4033-93ce-002d5875f58a",
    "fd4b0007-cce1-4033-93ce-002d5875f58a",
)

MODEL_NUMBER = "00002a24-0000-1000-8000-00805f9b34fb"
SERIAL_NUMBER = "00002a25-0000-1000-8000-00805f9b34fb"
FIRMWARE_REVISION = "00002a26-0000-1000-8000-00805f9b34fb"
HARDWARE_REVISION = "00002a27-0000-1000-8000-00805f9b34fb"

SCHEMA = "noop.whoop5_raw_probe.v1"
WHOOP_VAULT_AUDITED_COMMIT = "bd43855e5d27f72125b0085a7f5f14beca585395"


def _utc_now():
    return datetime.now(timezone.utc).isoformat()


def _stable_private_id(value: str):
    if not value:
        return None
    return hashlib.sha256(("noop-issue-423:" + value.lower()).encode()).hexdigest()[:16]


def _package_version(name: str):
    try:
        return metadata.version(name)
    except metadata.PackageNotFoundError:
        return None


class ProbeRecorder:
    def __init__(self):
        self.phase = "setup"
        self.started_monotonic_ns = time.monotonic_ns()
        self.reassemblers = {}
        self.records = []

    def on_notify(self, sender, data: bytearray):
        char = str(getattr(sender, "uuid", sender)).lower()
        reassembler = self.reassemblers.setdefault(char, wf.Reassembler("whoop5"))
        for frame in reassembler.feed(bytes(data)):
            wall_ns = time.time_ns()
            mono_ns = time.monotonic_ns()
            packet_type = frame[wf.WHOOP5_INNER_OFF] if len(frame) > wf.WHOOP5_INNER_OFF else None
            record = {
                "phase": self.phase,
                "char": char,
                "wall_ts_ms": wall_ns // 1_000_000,
                "elapsed_ms": round((mono_ns - self.started_monotonic_ns) / 1_000_000, 3),
                "frame_len": len(frame),
                "packet_type": packet_type,
                "crc_ok": wf.verify_whoop5_frame(frame),
            }
            response_cmd = wf.command_response_cmd(frame, "whoop5")
            if response_cmd is not None:
                record["command_response_cmd"] = response_cmd
            # Minimise sensitive material: GET_HELLO can carry a serial/session token and unrelated
            # realtime/event frames are health data. The probe needs bytes only for its type-43 target
            # and the 81/82 responses that prove which command elicited the observation.
            if packet_type == wf.PACKET_REALTIME_RAW_DATA or response_cmd in (
                    wf.PUFFIN_CMD_START_RAW_DATA, wf.PUFFIN_CMD_STOP_RAW_DATA):
                record["hex"] = frame.hex()
                record["raw_retention"] = "target_or_probe_response"
            else:
                record["frame_sha256"] = hashlib.sha256(frame).hexdigest()
                record["raw_retention"] = "redacted_non_target"
            candidate = wf.decode_whoop5_raw_triplet_candidate(frame)
            if candidate is not None:
                record["candidate"] = {
                    "seq": candidate.seq,
                    "record_byte": candidate.record_byte,
                    "payload_len": len(candidate.payload),
                    "triplet_count": len(candidate.triplets),
                    "trailing_hex": candidate.trailing.hex(),
                }
            self.records.append(record)


def _phase_census(records, phase):
    selected = [r for r in records if r["phase"] == phase]
    type_counts = Counter(str(r["packet_type"]) for r in selected if r["packet_type"] is not None)
    raw = [r for r in selected if r["packet_type"] == wf.PACKET_REALTIME_RAW_DATA]
    candidates = [r for r in raw if "candidate" in r]
    arrival_ms = [r["elapsed_ms"] for r in candidates]
    span_s = (arrival_ms[-1] - arrival_ms[0]) / 1000 if len(arrival_ms) > 1 else None
    cadence_hz = ((len(arrival_ms) - 1) / span_s) if span_s and span_s > 0 else None
    return {
        "frame_count": len(selected),
        "packet_type_counts": dict(sorted(type_counts.items())),
        "crc_failure_count": sum(not r["crc_ok"] for r in selected),
        "type43_frame_count": len(raw),
        "type43_crc_valid_count": len(candidates),
        "type43_frame_lengths": dict(sorted(Counter(str(r["frame_len"]) for r in raw).items())),
        "candidate_payload_lengths": dict(sorted(Counter(
            str(r["candidate"]["payload_len"]) for r in candidates).items())),
        "candidate_triplets_per_frame": dict(sorted(Counter(
            str(r["candidate"]["triplet_count"]) for r in candidates).items())),
        "candidate_trailing_hex": dict(sorted(Counter(
            r["candidate"]["trailing_hex"] for r in candidates).items())),
        "candidate_record_bytes": dict(sorted(Counter(
            f'{r["candidate"]["record_byte"]:02x}' for r in candidates).items())),
        "arrival_span_s": round(span_s, 6) if span_s is not None else None,
        "arrival_cadence_hz": round(cadence_hz, 6) if cadence_hz is not None else None,
        "candidate_triplet_total": sum(r["candidate"]["triplet_count"] for r in candidates),
    }


async def _resolve(address, name_filter, scanner):
    if address:
        return await scanner.find_device_by_address(address, timeout=20.0)
    return await scanner.find_device_by_filter(
        lambda device, adv: (
            WHOOP5_SERVICE.lower() in [s.lower() for s in (adv.service_uuids or [])]
            or (name_filter and name_filter.lower() in (device.name or "").lower())
        ),
        timeout=20.0,
    )


async def _read_device_info(client, characteristic):
    try:
        raw = bytes(await client.read_gatt_char(characteristic))
    except Exception as error:
        return {"available": False, "error": type(error).__name__}
    text = raw.rstrip(b"\x00").decode("utf-8", errors="replace")
    return {"available": True, "text": text, "raw_hex": raw.hex()}


async def run(args, *, scanner=None, client_factory=None, sleep=None):
    """Run one probe; injectable BLE/time seams keep every safety edge unit-testable."""
    scanner = scanner or BleakScanner
    client_factory = client_factory or BleakClient
    sleep = sleep or asyncio.sleep
    recorder = ProbeRecorder()
    report = {
        "schema": SCHEMA,
        "research_only": True,
        "started_at": _utc_now(),
        "completed_at": None,
        "completed": False,
        "cancelled": False,
        "outcome": "incomplete",
        "error": None,
        "sensitive_data": {
            "may_contain_raw_sensor_frames": True,
            "contains_raw_sensor_frames": False,
            "contains_health_data": False,
            "non_target_frames_redacted": True,
            "file_mode": "0600",
            "warning": (
                "Target type-43 frames may contain physiological/motion data. Keep this report local "
                "and do not publish it without review and scrubbing."
            ),
        },
        "safety": {
            "disarm_required": False,
            "disarm_attempted": False,
            "disarm_completed": False,
            "disarm_error": None,
        },
        "probe": {
            "issue": "https://github.com/ryanbr/noop/issues/423",
            "candidate_layout_source": "https://github.com/Sophonbot0/whoop-vault",
            "candidate_layout_source_commit": WHOOP_VAULT_AUDITED_COMMIT,
            "source_evidence_limit": (
                "The audited public source has a synthetic decoder test but no captured type-43 "
                "fixture; this report therefore asserts no sensor semantics."
            ),
            "baseline_seconds": args.baseline_seconds,
            "active_seconds": args.duration,
            "post_seconds": args.post_seconds,
            "arm": {"command": 81, "payload_hex": "01"},
            "disarm": {"command": 82, "payload_hex": "00"},
        },
        "operator_provenance": {
            "model": args.hardware_model,
            "firmware": args.firmware,
        },
        "gatt_provenance": {},
        "host": {
            "platform": platform.platform(),
            "kernel": platform.release(),
            "python": platform.python_version(),
            "bleak": _package_version("bleak"),
        },
        "address_hash": None,
        "subscribed_characteristics": [],
        "command_trace": [],
        "census": {},
        "frames": recorder.records,
        "interpretation_contract": {
            "type43": "candidate raw stream only",
            "tuple_layout": "three consecutive signed i16 little-endian channels",
            "axis_mapping": None,
            "scale": None,
            "unit": None,
            "sample_rate_hz": None,
        },
    }

    device = None
    try:
        device = await _resolve(args.address, args.name_filter, scanner)
        if device is None:
            raise RuntimeError("WHOOP 5/MG not found; wake it and ensure no phone owns the BLE link")
        address = str(getattr(device, "address", args.address or ""))
        report["address_hash"] = _stable_private_id(address)

        async with client_factory(device) as client:
            for characteristic in WHOOP5_NOTIFY:
                try:
                    await client.start_notify(characteristic, recorder.on_notify)
                    report["subscribed_characteristics"].append(characteristic)
                except Exception as error:
                    report["command_trace"].append({
                        "at": _utc_now(),
                        "action": "subscribe_failed",
                        "characteristic": characteristic,
                        "error": type(error).__name__,
                    })
            if not report["subscribed_characteristics"]:
                raise RuntimeError("no WHOOP 5/MG notification characteristic could be subscribed")

            await client.write_gatt_char(WHOOP5_COMMAND, wf.WHOOP5_CLIENT_HELLO, response=True)
            report["command_trace"].append({"at": _utc_now(), "action": "client_hello_written"})

            report["gatt_provenance"] = {
                "model_number": await _read_device_info(client, MODEL_NUMBER),
                "firmware_revision": await _read_device_info(client, FIRMWARE_REVISION),
                "hardware_revision": await _read_device_info(client, HARDWARE_REVISION),
            }
            serial = await _read_device_info(client, SERIAL_NUMBER)
            if serial.get("available"):
                report["gatt_provenance"]["serial_hash"] = _stable_private_id(serial.get("text", ""))
            else:
                report["gatt_provenance"]["serial_hash"] = None

            recorder.phase = "baseline"
            await sleep(args.baseline_seconds)

            arm_attempted = False
            active_completed = False
            disarm_completed = False
            try:
                recorder.phase = "active"
                arm = wf.build_puffin_command(
                    wf.PUFFIN_CMD_START_RAW_DATA, seq=0x41, payload=b"\x01")
                arm_attempted = True
                report["safety"]["disarm_required"] = True
                report["command_trace"].append({
                    "at": _utc_now(), "action": "arm_attempt", "frame_hex": arm.hex()})
                await client.write_gatt_char(WHOOP5_COMMAND, arm, response=True)
                report["command_trace"].append({"at": _utc_now(), "action": "arm_write_completed"})
                await sleep(args.duration)
                active_completed = True
            finally:
                if arm_attempted and client.is_connected:
                    recorder.phase = "disarm"
                    report["safety"]["disarm_attempted"] = True
                    stop = wf.build_puffin_command(
                        wf.PUFFIN_CMD_STOP_RAW_DATA, seq=0x42, payload=b"\x00")
                    report["command_trace"].append({
                        "at": _utc_now(), "action": "disarm_attempt", "frame_hex": stop.hex()})
                    try:
                        await client.write_gatt_char(WHOOP5_COMMAND, stop, response=True)
                        disarm_completed = True
                        report["safety"]["disarm_completed"] = True
                        report["command_trace"].append({
                            "at": _utc_now(), "action": "disarm_write_completed"})
                    except Exception as error:
                        report["safety"]["disarm_error"] = type(error).__name__
                        report["command_trace"].append({
                            "at": _utc_now(), "action": "disarm_write_failed",
                            "error": type(error).__name__,
                        })
                elif arm_attempted:
                    report["safety"]["disarm_error"] = "link_disconnected"
                    report["command_trace"].append({
                        "at": _utc_now(), "action": "disarm_unavailable_link_disconnected"})

            if active_completed and disarm_completed:
                recorder.phase = "post"
                await sleep(args.post_seconds)
            report["completed"] = active_completed and disarm_completed
    except asyncio.CancelledError:
        # Cancellation is an expected operator action (Ctrl-C). The inner finally has already had its
        # chance to disarm; preserve an honest report and return the conventional shell code 130.
        report["cancelled"] = True
        report["error"] = {"type": "CancelledError", "message": "probe cancelled"}
    except Exception as error:
        report["error"] = {"type": type(error).__name__, "message": str(error)}
    finally:
        report["completed_at"] = _utc_now()
        report["census"] = {
            phase: _phase_census(recorder.records, phase)
            for phase in ("setup", "baseline", "active", "disarm", "post")
        }
        active_raw = report["census"]["active"]["type43_crc_valid_count"]
        retained_raw = sum(
            phase["type43_frame_count"] for phase in report["census"].values())
        report["sensitive_data"]["contains_raw_sensor_frames"] = retained_raw > 0
        report["sensitive_data"]["contains_health_data"] = retained_raw > 0
        if report["cancelled"]:
            report["outcome"] = "cancelled"
        elif report["error"] is not None:
            report["outcome"] = "error"
        elif not report["completed"]:
            report["outcome"] = "incomplete_disarm"
        elif active_raw == 0:
            report["outcome"] = "no_type43_observed"
        else:
            report["outcome"] = "type43_observed"
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        # Raw target frames are health data and GET_HELLO-like material is sensitive even after our
        # minimisation pass. mkstemp is owner-only + collision/symlink safe; never expose a transient
        # predictable-name 0644 file. Replace atomically only after the complete JSON is durable.
        fd, tmp_name = tempfile.mkstemp(prefix=f".{out.name}.", dir=out.parent)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(report, handle, indent=2, sort_keys=True)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(tmp_name, out)
            os.chmod(out, 0o600)
        except BaseException:
            try:
                os.close(fd)
            except OSError:
                pass
            try:
                os.unlink(tmp_name)
            except FileNotFoundError:
                pass
            raise

    active_raw = report["census"]["active"]["type43_crc_valid_count"]
    if report["cancelled"]:
        print(f"probe cancelled; disarm/report status preserved at {args.out}", file=sys.stderr)
        return 130
    if report["error"] is not None or not report["completed"]:
        print(f"probe incomplete; report preserved at {args.out}", file=sys.stderr)
        return 2
    if active_raw == 0:
        print(f"probe completed but saw no CRC-valid type-43 frames; report: {args.out}")
        return 3
    print(f"probe captured {active_raw} CRC-valid type-43 frames; report: {args.out}")
    return 0


def main():
    parser = argparse.ArgumentParser(
        description="Bounded research-only WHOOP 5/MG command-81 raw-frame census (#423).")
    parser.add_argument("--address", help="known strap BLE address; otherwise scan by service/name")
    parser.add_argument("--name-filter", default="WHOOP", help="scan-name fallback (default: WHOOP)")
    parser.add_argument("--hardware-model", required=True, choices=("WHOOP 5.0", "WHOOP 5.0 MG"),
                        help="operator-confirmed physical model stored in the report")
    parser.add_argument("--firmware", required=True,
                        help="operator-observed firmware string; GATT firmware is captured separately")
    parser.add_argument("--duration", type=float, default=30.0,
                        help="armed observation seconds, 5..60 (default: 30)")
    parser.add_argument("--baseline-seconds", type=float, default=3.0,
                        help="passive census before command 81, 0..10 (default: 3)")
    parser.add_argument("--post-seconds", type=float, default=3.0,
                        help="passive census after command 82, 0..10 (default: 3)")
    parser.add_argument("--out", default="whoop5-raw-probe.json",
                        help="atomic JSON report path (default: whoop5-raw-probe.json)")
    parser.add_argument("--acknowledge-research-only", action="store_true", required=True,
                        help="required acknowledgement: this is protocol research, not health data")
    args = parser.parse_args()
    if not 5 <= args.duration <= 60:
        parser.error("--duration must be between 5 and 60 seconds")
    if not 0 <= args.baseline_seconds <= 10:
        parser.error("--baseline-seconds must be between 0 and 10 seconds")
    if not 0 <= args.post_seconds <= 10:
        parser.error("--post-seconds must be between 0 and 10 seconds")
    print(
        "WARNING: this probe can capture sensitive motion/physiological frames; "
        "the local report is minimised and written mode 0600.",
        file=sys.stderr,
    )
    try:
        raise SystemExit(asyncio.run(run(args)))
    except KeyboardInterrupt:
        raise SystemExit(130)


if __name__ == "__main__":
    main()
