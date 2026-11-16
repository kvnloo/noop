#!/usr/bin/env python3
"""End-to-end check of NOOP's agent API on a real phone, from the Hermes host, over the tailnet only.

Never calls adb. Prints and writes only structure, statuses, counts and latencies: no health values.
Requires: `noop_agent_pair.py pair <phone>` done, and a grant running in NOOP > Hermes agent access.

  e2e_phone.py OUT.json [--action open_screen:sleep] [--expect-detail-refused] [--latency-n 10]
"""
from __future__ import annotations

import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import noop_agent_mcp as m  # noqa: E402

FORBIDDEN_KEYS = {"deviceId", "notes", "routePolyline", "stagesJSON", "motionJSON", "zonesJSON", "serial", "mac"}


def keys(obj, acc=None):
    acc = set() if acc is None else acc
    if isinstance(obj, dict):
        for k, v in obj.items():
            acc.add(k); keys(v, acc)
    elif isinstance(obj, list):
        for v in obj:
            keys(v, acc)
    return acc


def raw_status(phone: m.Phone, token: str, extra: dict | None = None) -> int:
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).encode()
    headers = {"Content-Type": "application/json", "Authorization": "Bearer " + token, **(extra or {})}
    try:
        with urllib.request.urlopen(urllib.request.Request(phone.base + "/mcp", data=body, headers=headers, method="POST"), timeout=8) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def main(argv: list[str]) -> int:
    out = Path(argv[0])
    action = next((a.split("=", 1)[1] for a in argv if a.startswith("--action=")), "open_screen:sleep")
    n = int(next((a.split("=", 1)[1] for a in argv if a.startswith("--latency-n=")), "10"))
    phone = m.Phone()
    checks: list[dict] = []

    def check(name: str, ok: bool, **info):
        checks.append({"check": name, "pass": bool(ok), **info})
        print(f"{'PASS' if ok else 'FAIL'}  {name}  {json.dumps(info) if info else ''}", flush=True)

    check("transport_is_tailnet_http", phone.base.startswith("http://100."), base_host_prefix=phone.base.split("//")[1].split(".")[0])
    check("wrong_token_refused", raw_status(phone, "Tok_" + "x" * 40) == 401)
    check("browser_origin_refused", raw_status(phone, phone.creds["token"], {"Origin": "http://evil.example"}) == 403)

    t0 = time.time(); phone.ensure()
    tools = phone.rpc("tools/list")["tools"]
    check("initialize_and_list_tools", len(tools) == 7, tools=[t["name"] for t in tools], ms=int((time.time() - t0) * 1000))

    reads = {}
    for tool, args in [("data_freshness", {}), ("health_snapshot", {"days": 7}), ("sleep_summary", {"days": 7}),
                       ("workout_summary", {"days": 14}), ("metric_series", {"key": "hrv", "days": 14})]:
        t = time.time(); r = phone.call(tool, args); ms = int((time.time() - t) * 1000)
        ks = keys(r["data"])
        reads[tool] = {"isError": r["isError"], "ms": ms, "top_keys": sorted(r["data"].keys()), "redactions": r["data"].get("_privacy", {}).get("redactions")}
        check(f"read_{tool}", not r["isError"] and not (ks & FORBIDDEN_KEYS), ms=ms, forbidden_keys=sorted(ks & FORBIDDEN_KEYS),
              summary_only=("daily" not in r["data"] and "points" not in r["data"] and "sessions" not in r["data"] and "workouts" not in r["data"]))

    detail = phone.call("health_snapshot", {"days": 7, "granularity": "daily"})
    detail_state = "refused_scope" if detail["isError"] and detail["data"].get("error") == "scope_required" else ("allowed" if not detail["isError"] else "error")
    check("detail_gate", detail_state in ("refused_scope", "allowed"), state=detail_state)

    tool, arg = action.split(":", 1)
    t = time.time()
    act = phone.call(tool, {"screen": arg} if tool == "open_screen" else {"title": "Hermes coach (test)", "body": arg})
    check(f"action_{tool}", not act["isError"], ms=int((time.time() - t) * 1000),
          result={k: v for k, v in act["data"].items() if k in ("mode", "landed", "posted", "screen", "error")})

    lat = []
    for _ in range(n):
        t = time.time(); r = phone.call("data_freshness", {}); lat.append((time.time() - t) * 1000)
        if r["isError"]:
            break
    q = sorted(lat)
    check("latency_data_freshness", len(lat) == n, n=len(lat), p50_ms=round(statistics.median(q)), p95_ms=round(q[max(0, int(0.95 * len(q)) - 1)]))

    result = {"generated": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "engine": m.redactor().engine, "checks": checks, "reads": reads,
              "passed": sum(c["pass"] for c in checks), "total": len(checks)}
    out.write_text(json.dumps(result, indent=1))
    print(f"{result['passed']}/{result['total']} checks passed -> {out}")
    return 0 if result["passed"] == result["total"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
