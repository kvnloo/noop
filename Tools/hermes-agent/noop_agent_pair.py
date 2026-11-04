#!/usr/bin/env python3
"""Pair NOOP's Experimental Hermes agent access with this Hermes host, reusing the z0live broker identity.

  noop-agent-pair pair PHONE_HOSTNAME [--ttl 600]   mint a z0live device pairing, save the Hermes-side
                                                    credential (0600), serve a single-use tailnet page
                                                    whose button opens noop-agent://pair on the phone
  noop-agent-pair revoke                            revoke that pairing on the broker and delete the credential
  noop-agent-pair status                            show pairing metadata (never the token)

The pairing is an ordinary z0live device (same devices.json, same revocation). NOOP stores only the token's
SHA-256 and re-checks it against the broker's /v1/devices/self, so `revoke` cuts the phone side too.
The token is never printed; the one-shot page is the only delivery channel.
"""
from __future__ import annotations

import argparse
import html
import json
import os
import secrets
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlencode

Z0LIVE = Path(os.environ.get("Z0LIVE_DIR", "/mnt/zer0models/workspace/zer0/products/z0live"))
CREDENTIALS = Path(os.environ.get("NOOP_AGENT_CREDENTIALS", Path.home() / ".config/noop-agent/credentials.json"))
PAGE_BASE = os.environ.get("NOOP_AGENT_PAGE_BASE", "https://0.taila30dc.ts.net/z0pair/")  # tailscale serve /z0pair -> :8797
PAGE_PORT = int(os.environ.get("NOOP_AGENT_PAGE_PORT", "8797"))


def store():
    sys.path.insert(0, str(Z0LIVE / "desktop"))
    from z0live_desktop.core import BASE, Store  # noqa: E402

    return BASE, Store()


def save(creds: dict) -> None:
    CREDENTIALS.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    tmp = CREDENTIALS.with_suffix(".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(creds, f)
    os.replace(tmp, CREDENTIALS)


def pair(phone_host: str, ttl: int) -> int:
    with socket.socket() as s:
        if s.connect_ex(("127.0.0.1", PAGE_PORT)) == 0:
            print(f"port {PAGE_PORT} busy (another pairing page is open); try again later", file=sys.stderr)
            return 1
    base, st = store()
    token = st.pair(f"noop-agent:{phone_host}")
    device = st.authenticate(token)
    save({"broker": base, "token": token, "device_id": device, "phone_host": phone_host, "port": 8765, "paired_at": time.time()})
    link = "noop-agent://pair?" + urlencode({"url": base, "token": token})
    nonce = secrets.token_urlsafe(18)
    served = threading.Event()

    class Page(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path.rstrip("/").split("/")[-1] != nonce or served.is_set():
                self.send_response(404); self.end_headers(); self.wfile.write(b"expired or used"); return
            served.set()
            body = f"""<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>Pair NOOP with Hermes</title>
<body style="font:19px system-ui;background:#0e1316;color:#eee;padding:36px"><p>Pair NOOP's <b>Hermes agent access</b> with this Hermes host.</p>
<p><a href="{html.escape(link, quote=True)}" style="display:inline-block;padding:18px 28px;background:#5fd3a0;color:#000;border-radius:14px;text-decoration:none;font-weight:650">Open NOOP &amp; pair</a></p>
<p style="font-size:14px;color:#9aa">Single use. NOOP asks you to confirm, then nothing is shared until you start a grant.</p></body>"""
            self.send_response(200)
            for k, v in (("Content-Type", "text/html; charset=utf-8"), ("Cache-Control", "no-store"), ("Referrer-Policy", "no-referrer")):
                self.send_header(k, v)
            self.end_headers(); self.wfile.write(body.encode())

    srv = ThreadingHTTPServer(("127.0.0.1", PAGE_PORT), Page)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print(PAGE_BASE + nonce, flush=True)  # nonce URL only; the token stays inside the page
    deadline = time.time() + ttl
    while time.time() < deadline and not served.is_set():
        time.sleep(0.5)
    time.sleep(3)
    srv.shutdown()
    print("served" if served.is_set() else "expired-unserved (pairing kept; run revoke to discard)", flush=True)
    return 0


def revoke() -> int:
    if not CREDENTIALS.exists():
        print("no credential"); return 0
    creds = json.loads(CREDENTIALS.read_text())
    _, st = store()
    st.revoke_token(creds["token"])
    ok = st.authenticate(creds["token"]) is None
    CREDENTIALS.unlink()
    print("revoked" if ok else "REVOKE FAILED", flush=True)
    return 0 if ok else 1


def status() -> int:
    if not CREDENTIALS.exists():
        print("not paired"); return 0
    c = json.loads(CREDENTIALS.read_text())
    _, st = store()
    live = st.authenticate(c["token"]) is not None
    print(json.dumps({"phone_host": c.get("phone_host"), "broker": c.get("broker"), "device": (c.get("device_id") or "")[-6:],
                      "paired_at": c.get("paired_at"), "live_on_broker": live, "mode": oct(CREDENTIALS.stat().st_mode & 0o777)}))
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("pair"); p.add_argument("phone_host"); p.add_argument("--ttl", type=int, default=600)
    sub.add_parser("revoke"); sub.add_parser("status")
    a = ap.parse_args()
    sys.exit(pair(a.phone_host, a.ttl) if a.cmd == "pair" else revoke() if a.cmd == "revoke" else status())
