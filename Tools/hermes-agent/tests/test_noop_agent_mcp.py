"""Tests for the Hermes-side NOOP agent client. Synthetic data only; a fake phone stands in for NOOP."""
import json
import os
import stat
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import noop_agent_mcp as m  # noqa: E402

TOKEN = "Tok_" + "a" * 40


class FakePhone(BaseHTTPRequestHandler):
    seen: list = []

    def log_message(self, *a):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        FakePhone.seen.append((self.headers.get("Authorization"), self.headers.get("Origin"), body.get("method")))
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            self.send_response(401); self.end_headers(); return
        if "id" not in body:
            self.send_response(202); self.send_header("Content-Length", "0"); self.end_headers(); return
        if body["method"] == "initialize":
            result = {"protocolVersion": "2025-03-26", "capabilities": {"tools": {}}, "serverInfo": {"name": "noop-agent"}}
        else:
            payload = {"metrics": {"hrv": {"unit": "ms", "mean": 44, "latestDay": "2026-03-15"}},
                       "sources": ["oura (NOOP-computed)"], "label": "Run with Jane Doe, jane@example.com"}
            result = {"content": [{"type": "text", "text": json.dumps(payload)}], "structuredContent": payload, "isError": False}
        raw = json.dumps({"jsonrpc": "2.0", "id": body["id"], "result": result}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(raw)))
        self.end_headers(); self.wfile.write(raw)


class ClientTests(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory()
        d = Path(self.tmp.name)
        self.creds = d / "credentials.json"
        self.creds.write_text(json.dumps({"token": TOKEN, "phone_host": "fake"}))
        os.chmod(self.creds, 0o600)
        m.CREDENTIALS, m.RECEIPTS, m.DASH_DIR = self.creds, d / "receipts.jsonl", d / "dash"
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), FakePhone)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        os.environ["NOOP_AGENT_URL"] = f"http://127.0.0.1:{self.srv.server_port}"
        FakePhone.seen = []

    def tearDown(self):
        self.srv.shutdown(); self.tmp.cleanup(); os.environ.pop("NOOP_AGENT_URL", None)

    def test_call_handshakes_redacts_and_logs_receipt_without_values(self):
        r = m.Phone().call("health_snapshot", {"days": 7})
        self.assertFalse(r["isError"])
        self.assertEqual([s[2] for s in FakePhone.seen], ["initialize", "notifications/initialized", "tools/call"])
        self.assertTrue(all(s[0] == "Bearer " + TOKEN and s[1] is None for s in FakePhone.seen))
        label = r["data"]["label"]
        self.assertNotIn("jane@example.com", label)
        self.assertIn("<EMAIL_ADDRESS>", label)
        if m.redactor().engine.startswith("presidio"):
            self.assertNotIn("Jane Doe", label)
        self.assertEqual(r["data"]["metrics"]["hrv"]["latestDay"], "2026-03-15")  # safe fields untouched
        self.assertGreaterEqual(r["data"]["_privacy"]["redactions"], 1)
        rec = m.RECEIPTS.read_text()
        self.assertNotIn("44", rec.replace('"ms":', ""))  # no metric values in the receipt
        self.assertNotIn("Jane", rec)
        self.assertEqual(stat.S_IMODE(m.RECEIPTS.stat().st_mode), 0o600)

    def test_wrong_token_is_reported_not_raised(self):
        self.creds.write_text(json.dumps({"token": "Tok_" + "b" * 40, "phone_host": "fake"}))
        r = m.Phone().call("data_freshness", {})
        self.assertTrue(r["isError"])
        self.assertIn("refused the credential", r["data"]["message"])

    def test_loose_credential_permissions_refused(self):
        os.chmod(self.creds, 0o644)
        with self.assertRaises(m.PhoneError):
            m.Phone()

    def test_dashboard_is_static_and_framed(self):
        path = m.render_dashboard(m.DEMO, "Keep bedtime within 30 min of 00:20 tonight.\nEasy aerobic day.")
        doc = path.read_text()
        self.assertNotIn("<script", doc.lower())
        self.assertIn("not medical advice", doc)
        self.assertIn("Facts from NOOP", doc)
        self.assertIn("inference, not data", doc)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_dashboard_escapes_model_text(self):
        doc = m.render_dashboard(m.DEMO, "<img src=x onerror=alert(1)>").read_text()
        self.assertNotIn("<img src=x", doc)


class RedactorTests(unittest.TestCase):
    def test_walk_skips_safe_keys_and_numbers(self):
        r = m.Redactor()
        out, n = r.walk({"day": "2026-03-15", "mean": 44, "sport": "running", "msg": "call +1 555 123 4567"})
        self.assertEqual(out["day"], "2026-03-15")
        self.assertEqual(out["mean"], 44)
        self.assertEqual(out["sport"], "running")
        self.assertIn("<PHONE_NUMBER>", out["msg"])
        self.assertEqual(n, 1)

    def test_source_kinds_are_not_mistaken_for_people(self):
        out, n = m.Redactor().walk({"sources": ["oura (NOOP-computed)", "whoop"]})
        self.assertEqual(out["sources"], ["oura (NOOP-computed)", "whoop"])
        self.assertEqual(n, 0)

    def test_mac_addresses_are_redacted_even_without_presidio(self):
        out, n = m.Redactor().text("strap AA:BB:CC:DD:EE:FF")
        self.assertIn("<MAC_ADDRESS>", out)


if __name__ == "__main__":
    unittest.main()
