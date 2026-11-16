#!/usr/bin/env python3
"""Hermes-side client for NOOP's Experimental agent API (fork-only, kvnloo/noop).

Runs on the owner's Hermes host. Speaks MCP over stdio to Hermes and MCP-over-HTTP (JSON-RPC POST /mcp)
to NOOP on the owner's phone across the owner's own Tailscale network. No adb, no third-party relay.

Privacy layer (everything that leaves the phone passes through here before Hermes/the LLM sees it):
  * the phone already minimizes (summaries by default, no ids/notes/routes/free text);
  * every string value is scanned with Microsoft Presidio (PERSON, EMAIL, PHONE, LOCATION, ...) and
    replaced with <ENTITY> placeholders; if Presidio is missing a strict regex fallback runs and the
    result says so (`_privacy.engine`);
  * a local receipt (tool, status, bytes, redaction count, never values) is appended per call.

Usage:
  noop-agent-mcp                      # stdio MCP server (register in Hermes config.yaml mcp_servers)
  noop-agent-mcp call TOOL [JSON]     # one call; prints the result SHAPE only unless --show
  noop-agent-mcp dashboard [--days N] [--advice-file F] [--demo]  # render the ephemeral coach page
"""
from __future__ import annotations

import html
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

CREDENTIALS = Path(os.environ.get("NOOP_AGENT_CREDENTIALS", Path.home() / ".config/noop-agent/credentials.json"))
RECEIPTS = Path(os.environ.get("NOOP_AGENT_RECEIPTS", Path.home() / ".local/state/noop-agent/receipts.jsonl"))
DASH_DIR = Path(os.environ.get("NOOP_AGENT_DASH_DIR", Path(os.environ.get("TMPDIR", "/tmp")) / "noop-coach"))
PORT = 8765
TIMEOUT_S = 12
PROTOCOL = "2025-03-26"
NOT_MEDICAL = "Wearable estimates for self-reflection, not medical advice or diagnosis."

PHONE_TOOLS = {
    "health_snapshot": "Recovery, HRV, resting HR, sleep, strain, respiration, SpO2, skin temperature, steps over the last N days (default summary stats; granularity=daily needs the owner's read_detail grant).",
    "metric_series": "One daily metric (key) over N days; summary stats unless granularity=daily.",
    "data_freshness": "How old NOOP's newest heart-rate sample, sleep, workout and daily metric are.",
    "sleep_summary": "Main-sleep nights: duration, efficiency, median bed/wake, bedtime spread, naps.",
    "workout_summary": "Workouts: count, minutes, per-sport totals, mean strain (no notes/routes).",
    "open_screen": "Navigate NOOP on the phone to a screen (today, sleep, breathe, stress, trends, workouts, coach, insights). Needs the owner's actions grant.",
    "show_coach_note": "Post a short Hermes coach notification on the phone (title<=60, body<=600 chars). Needs the owner's actions grant.",
}

# ---------------------------------------------------------------------------------------------
# PII redaction
# ---------------------------------------------------------------------------------------------
SAFE_KEYS = {"day", "from", "to", "latestDay", "wakeDay", "bed", "wake", "unit", "granularity", "key", "screen", "mode",
             "note", "generatedAt", "latestDailyMetricDay", "medianBedtime", "medianWake", "error", "requestedSource",
             # closed vocabulary from NOOP's sourceKind() (e.g. "oura (NOOP-computed)"); spaCy reads brand names as PERSON
             "sources"}
SAFE_VALUE = re.compile(r"^[\d\s:+\-.TZ%/]*$")
ENTITIES = ["PERSON", "EMAIL_ADDRESS", "PHONE_NUMBER", "LOCATION", "IP_ADDRESS", "URL", "CREDIT_CARD", "US_SSN",
            "IBAN_CODE", "US_DRIVER_LICENSE", "US_PASSPORT", "MEDICAL_LICENSE", "CRYPTO", "NRP"]
FALLBACK = [
    ("EMAIL_ADDRESS", re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")),
    ("PHONE_NUMBER", re.compile(r"\+?\d[\d\s().-]{7,}\d")),
    ("IP_ADDRESS", re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}\b")),
    ("URL", re.compile(r"https?://\S+")),
    ("MAC_ADDRESS", re.compile(r"\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\b")),
]


class Redactor:
    def __init__(self) -> None:
        self.engine = "regex-fallback"
        self._analyzer = None
        try:
            from presidio_analyzer import AnalyzerEngine
            from presidio_analyzer.nlp_engine import NlpEngineProvider

            nlp = NlpEngineProvider(nlp_configuration={
                "nlp_engine_name": "spacy", "models": [{"lang_code": "en", "model_name": os.environ.get("NOOP_AGENT_SPACY", "en_core_web_sm")}],
            }).create_engine()
            self._analyzer = AnalyzerEngine(nlp_engine=nlp, supported_languages=["en"])
            from importlib.metadata import version

            self.engine = f"presidio-analyzer {version('presidio-analyzer')}"
        except Exception:  # noqa: BLE001 - fall back, and say so in every result
            self._analyzer = None

    def text(self, value: str) -> tuple[str, int]:
        hits: list[tuple[int, int, str]] = []
        if self._analyzer is not None:
            for r in self._analyzer.analyze(text=value, language="en", entities=ENTITIES, score_threshold=0.5):
                hits.append((r.start, r.end, r.entity_type))
        for name, rx in FALLBACK:
            hits += [(m.start(), m.end(), name) for m in rx.finditer(value)]
        if not hits:
            return value, 0
        out, last, count = [], 0, 0
        for start, end, name in sorted(hits):
            if start < last:
                continue
            out.append(value[last:start]); out.append(f"<{name}>"); last = end; count += 1
        out.append(value[last:])
        return "".join(out), count

    def walk(self, obj, key: str | None = None):
        if isinstance(obj, dict):
            total = 0
            res = {}
            for k, v in obj.items():
                res[k], n = self.walk(v, k)
                total += n
            return res, total
        if isinstance(obj, list):
            total, res = 0, []
            for v in obj:
                r, n = self.walk(v, key)
                res.append(r); total += n
            return res, total
        if isinstance(obj, str) and key not in SAFE_KEYS and not SAFE_VALUE.match(obj):
            return self.text(obj)
        return obj, 0


_REDACTOR: Redactor | None = None


def redactor() -> Redactor:
    global _REDACTOR
    if _REDACTOR is None:
        _REDACTOR = Redactor()
    return _REDACTOR


# ---------------------------------------------------------------------------------------------
# Phone transport
# ---------------------------------------------------------------------------------------------
class PhoneError(Exception):
    pass


def credentials() -> dict:
    try:
        st = CREDENTIALS.stat()
    except FileNotFoundError:
        raise PhoneError(f"not paired: run noop-agent-pair first ({CREDENTIALS} missing)")
    if st.st_mode & 0o077:
        raise PhoneError(f"{CREDENTIALS} must be 0600")
    return json.loads(CREDENTIALS.read_text())


def phone_url(creds: dict) -> str:
    if os.environ.get("NOOP_AGENT_URL"):
        return os.environ["NOOP_AGENT_URL"].rstrip("/")
    host = creds.get("phone_host")
    if not host:
        raise PhoneError("credentials have no phone_host")
    status = json.loads(subprocess.run(["tailscale", "status", "--json"], capture_output=True, text=True, timeout=5, check=True).stdout)
    for peer in (status.get("Peer") or {}).values():
        if peer.get("HostName") == host or (peer.get("DNSName") or "").split(".")[0] == host:
            ips = [ip for ip in peer.get("TailscaleIPs") or [] if "." in ip]
            if not peer.get("Online", True):
                raise PhoneError(f"{host} is offline on the tailnet")
            if ips:
                return f"http://{ips[0]}:{creds.get('port', PORT)}"
    raise PhoneError(f"{host} not found on the tailnet")


def receipt(**fields) -> None:
    RECEIPTS.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with open(RECEIPTS, "a", encoding="utf-8") as f:
        os.chmod(RECEIPTS, 0o600)
        f.write(json.dumps({"ts": time.time(), **fields}) + "\n")


class Phone:
    def __init__(self) -> None:
        self.creds = credentials()
        self.base = phone_url(self.creds)
        self.initialized = False

    def rpc(self, method: str, params: dict | None = None, notify: bool = False) -> dict | None:
        body = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            body["params"] = params
        if not notify:
            body["id"] = str(uuid.uuid4())
        req = urllib.request.Request(self.base + "/mcp", data=json.dumps(body).encode(), method="POST", headers={
            "Content-Type": "application/json", "Accept": "application/json, text/event-stream",
            "Authorization": "Bearer " + self.creds["token"], "MCP-Protocol-Version": PROTOCOL,
        })
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
                raw = resp.read(512 * 1024)
                status = resp.status
        except urllib.error.HTTPError as e:
            raise PhoneError({401: "phone refused the credential (not granted, revoked or re-paired)",
                              403: "phone refused the request origin"}.get(e.code, f"phone HTTP {e.code}"))
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
            raise PhoneError(f"phone unreachable on the tailnet ({type(e).__name__}); is a grant running in NOOP > Hermes agent access?")
        if notify or status == 202:
            return None
        msg = json.loads(raw)
        if "error" in msg:
            raise PhoneError(f"phone JSON-RPC error {msg['error'].get('code')}: {msg['error'].get('message')}")
        return msg["result"]

    def ensure(self) -> None:
        if not self.initialized:
            self.rpc("initialize", {"protocolVersion": PROTOCOL, "capabilities": {}, "clientInfo": {"name": "hermes-noop-agent", "version": "0.1.0"}})
            self.rpc("notifications/initialized", notify=True)
            self.initialized = True

    def call(self, tool: str, args: dict) -> dict:
        t0 = time.time()
        try:
            self.ensure()
            result = self.rpc("tools/call", {"name": tool, "arguments": args})
            payload = result.get("structuredContent")
            if payload is None:
                payload = json.loads(result["content"][0]["text"])
            clean, n = redactor().walk(payload)
            clean["_privacy"] = {"engine": redactor().engine, "redactions": n, "granularity": args.get("granularity", "summary")}
            receipt(tool=tool, ok=not result.get("isError"), ms=int((time.time() - t0) * 1000),
                    bytes=len(json.dumps(payload)), redactions=n, granularity=args.get("granularity", "summary"),
                    error=payload.get("error") if result.get("isError") else None)
            return {"isError": bool(result.get("isError")), "data": clean}
        except PhoneError as e:
            receipt(tool=tool, ok=False, ms=int((time.time() - t0) * 1000), error=str(e)[:200])
            return {"isError": True, "data": {"error": "phone_unavailable", "message": str(e)}}


# ---------------------------------------------------------------------------------------------
# Ephemeral coach dashboard
# ---------------------------------------------------------------------------------------------
CARDS = [("recovery", "Recovery"), ("hrv", "HRV"), ("rhr", "Resting HR"), ("sleep_total_min", "Sleep"),
         ("sleep_efficiency", "Sleep efficiency"), ("strain", "Strain"), ("resp_rate", "Respiration")]


def _fmt(v, unit):
    if v is None:
        return "–"
    if unit == "min":
        return f"{int(v) // 60}h {int(v) % 60:02d}m"
    return f"{v:g} {unit}".strip()


def _card(key, label, m):
    if not m:
        return f'<div class="card dim"><h3>{label}</h3><p class="v">no data</p></div>'
    lo, hi, latest, mean = m.get("min"), m.get("max"), m.get("latest"), m.get("mean")
    span = (hi - lo) or 1
    pos = 100 * (latest - lo) / span if None not in (lo, hi, latest) else 50
    mpos = 100 * (mean - lo) / span if None not in (lo, hi, mean) else 50
    delta = m.get("recent3VsPriorMean")
    trend = "" if delta is None else (f'<span class="t">{"▲" if delta > 0 else "▼" if delta < 0 else "■"} {abs(delta):g} last 3 days vs before</span>')
    return f"""<div class="card"><h3>{label}</h3>
<p class="v">{html.escape(_fmt(latest, m.get("unit", "")))}<span class="sub"> latest ({html.escape(str(m.get("latestDay", "")))})</span></p>
<div class="bar"><i class="mean" style="left:{mpos:.0f}%"></i><i class="now" style="left:{pos:.0f}%"></i></div>
<p class="sub">range {html.escape(_fmt(lo, m.get("unit", "")))} – {html.escape(_fmt(hi, m.get("unit", "")))} · mean {html.escape(_fmt(mean, m.get("unit", "")))} · n={m.get("n")}</p>{trend}</div>"""


def render_dashboard(data: dict, advice: str, ttl_hours: int = 24) -> Path:
    snap, sleep, fresh, work = data.get("snapshot", {}), data.get("sleep", {}), data.get("freshness", {}), data.get("workouts", {})
    metrics = snap.get("metrics", {})
    latest = fresh.get("latest", {})
    stale = fresh.get("stale")
    now = time.strftime("%Y-%m-%d %H:%M")
    expires = time.strftime("%Y-%m-%d %H:%M", time.localtime(time.time() + ttl_hours * 3600))
    cards = "".join(_card(k, label, metrics.get(k)) for k, label in CARDS)
    sport = ", ".join(f"{html.escape(k)} ×{v.get('count')} ({v.get('minutes'):g} min)" for k, v in (work.get("bySport") or {}).items()) or "none logged"
    paras = "".join(f"<p>{html.escape(p.strip())}</p>" for p in advice.strip().split("\n") if p.strip()) or "<p>(no advice supplied)</p>"
    window = snap.get("window", {})
    doc = f"""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Hermes coach · NOOP · {html.escape(str(window.get("from", "")))}–{html.escape(str(window.get("to", "")))}</title>
<style>
body{{margin:0;font:15px/1.45 system-ui,sans-serif;background:#0e1316;color:#e8eef0}} main{{max-width:880px;margin:auto;padding:24px}}
h1{{font-size:22px;margin:0}} h2{{font-size:15px;text-transform:uppercase;letter-spacing:.08em;color:#8fb3bf;margin:28px 0 10px}}
.meta{{color:#8a9aa0;font-size:13px}} .warn{{background:#3a2a05;border:1px solid #a77b00;padding:10px 12px;border-radius:10px;margin-top:14px}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:12px}}
.card{{background:#162026;border:1px solid #23323a;border-radius:14px;padding:14px}} .card.dim{{opacity:.5}}
.card h3{{margin:0 0 4px;font-size:13px;color:#9fb4bb;font-weight:600}} .v{{font-size:24px;margin:2px 0 8px;font-weight:650}}
.sub{{font-size:12px;color:#8a9aa0;font-weight:400}} .t{{font-size:12px;color:#c9d3d6}}
.bar{{position:relative;height:8px;background:linear-gradient(90deg,#24414c,#2f6a5b);border-radius:6px;margin:6px 0}}
.bar i{{position:absolute;top:-3px;width:3px;height:14px;border-radius:2px}} .bar .mean{{background:#9fb4bb}} .bar .now{{background:#ffd166;width:5px}}
.facts td{{padding:4px 12px 4px 0}} .advice{{background:#13241c;border:1px solid #2b5a43;border-radius:14px;padding:4px 16px}}
footer{{margin-top:30px;color:#7d8d93;font-size:12px}}
</style><main>
<h1>How you slept and recovered · {html.escape(str(window.get("days", "")))} days</h1>
<p class="meta">Ephemeral Hermes coach view · generated {now} · expires {expires} · data read live from NOOP on your phone over your tailnet ({html.escape(snap.get("granularity", "summary"))} granularity)</p>
{'<div class="warn">NOOP data looks stale (no recent heart-rate sample). Treat everything below with caution and sync your ring/strap.</div>' if stale else ''}
<h2>Facts from NOOP</h2><div class="grid">{cards}</div>
<h2>Sleep pattern</h2><table class="facts">
<tr><td>Main-sleep nights</td><td>{sleep.get("nights", "–")} (+{sleep.get("naps", 0)} naps)</td></tr>
<tr><td>Average duration</td><td>{html.escape(_fmt((sleep.get("durationMin") or {}).get("mean"), "min"))}</td></tr>
<tr><td>Median bedtime / wake</td><td>{html.escape(str(sleep.get("medianBedtime", "–")))} / {html.escape(str(sleep.get("medianWake", "–")))}</td></tr>
<tr><td>Bedtime spread (sd)</td><td>{sleep.get("bedtimeSpreadMin", "–")} min</td></tr>
<tr><td>Efficiency (mean)</td><td>{sleep.get("efficiencyMean", "–")}</td></tr></table>
<h2>Training</h2><p>{work.get("count", 0)} workouts, {work.get("totalMinutes", 0):g} min: {sport}</p>
<h2>Freshness</h2><p class="meta">Newest heart-rate sample {latest.get("heartRateSampleAgeHours", "–")} h old · newest daily metric {html.escape(str(latest.get("latestDailyMetricDay", "–")))} · {fresh.get("dailyCoverageLast30Days", "–")}/30 days covered · sources: {html.escape(", ".join(snap.get("sources", [])))}</p>
<h2>Hermes' suggestions (inference, not data)</h2><div class="advice">{paras}</div>
<footer>{NOT_MEDICAL} If something feels wrong with your health, talk to a clinician. Numbers are device estimates with error;
this page holds summaries only, is stored only on your Hermes host and is deleted after {ttl_hours} h. Privacy: {html.escape(str((snap.get("_privacy") or {}).get("engine", "")))} scanned every field.</footer>
</main></html>"""
    DASH_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    for old in DASH_DIR.glob("coach-*.html"):
        if time.time() - old.stat().st_mtime > ttl_hours * 3600:
            old.unlink(missing_ok=True)
    out = DASH_DIR / f"coach-{time.strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:6]}.html"
    out.write_text(doc, encoding="utf-8")
    out.chmod(0o600)
    return out


def gather(phone: Phone, days: int) -> dict:
    out = {}
    for name, tool, args in [("snapshot", "health_snapshot", {"days": days}), ("sleep", "sleep_summary", {"days": days}),
                             ("freshness", "data_freshness", {}), ("workouts", "workout_summary", {"days": days})]:
        r = phone.call(tool, args)
        if r["isError"]:
            raise PhoneError(f"{tool}: {r['data'].get('message')}")
        out[name] = r["data"]
    return out


DEMO = {  # synthetic, for public screenshots and tests only
    "snapshot": {"window": {"from": "2026-03-09", "to": "2026-03-15", "days": 7}, "granularity": "summary", "sources": ["oura (NOOP-computed)"],
                 "metrics": {"recovery": {"unit": "%", "n": 7, "mean": 63, "min": 48, "max": 79, "latest": 55, "latestDay": "2026-03-15", "recent3VsPriorMean": -8},
                             "hrv": {"unit": "ms", "n": 7, "mean": 44, "min": 36, "max": 52, "latest": 39, "latestDay": "2026-03-15", "recent3VsPriorMean": -5},
                             "rhr": {"unit": "bpm", "n": 7, "mean": 56, "min": 53, "max": 60, "latest": 59, "latestDay": "2026-03-15", "recent3VsPriorMean": 2},
                             "sleep_total_min": {"unit": "min", "n": 7, "mean": 402, "min": 341, "max": 468, "latest": 355, "latestDay": "2026-03-15", "recent3VsPriorMean": -41},
                             "sleep_efficiency": {"unit": "%", "n": 7, "mean": 88, "min": 82, "max": 93, "latest": 85, "latestDay": "2026-03-15"},
                             "strain": {"unit": "score", "n": 7, "mean": 11.2, "min": 6.4, "max": 15.9, "latest": 14.1, "latestDay": "2026-03-15"}},
                 "_privacy": {"engine": "presidio-analyzer (demo)"}},
    "sleep": {"nights": 7, "naps": 1, "durationMin": {"mean": 402, "min": 341, "max": 468}, "medianBedtime": "00:20", "medianWake": "07:05", "bedtimeSpreadMin": 48, "efficiencyMean": 88},
    "freshness": {"latest": {"heartRateSampleAgeHours": 0.4, "latestDailyMetricDay": "2026-03-15"}, "dailyCoverageLast30Days": 27, "stale": False},
    "workouts": {"count": 3, "totalMinutes": 124, "bySport": {"running": {"count": 2, "minutes": 79}, "strength": {"count": 1, "minutes": 45}}},
}


# ---------------------------------------------------------------------------------------------
# MCP stdio server
# ---------------------------------------------------------------------------------------------
def serve() -> None:
    from mcp.server.fastmcp import FastMCP

    app = FastMCP("noop", instructions=(
        "NOOP health data and safe app actions on the owner's phone, over their tailnet. Summaries by default; "
        "say when data is stale; cite numbers you used; never diagnose; frame advice as non-medical suggestions. "
        "Every call is logged on the phone. If the phone says no_grant, ask the owner to open NOOP > Settings > "
        "Advanced > Hermes agent access and tap Allow."))
    state: dict = {}

    def phone() -> Phone:
        if "p" not in state:
            state["p"] = Phone()
        return state["p"]

    def run(tool: str, args: dict) -> str:
        try:
            return json.dumps(phone().call(tool, {k: v for k, v in args.items() if v is not None}))
        except PhoneError as e:
            state.pop("p", None)
            return json.dumps({"isError": True, "data": {"error": "phone_unavailable", "message": str(e)}})

    @app.tool(description=PHONE_TOOLS["health_snapshot"])
    def health_snapshot(days: int = 7, granularity: str = "summary") -> str:
        return run("health_snapshot", {"days": days, "granularity": granularity})

    @app.tool(description=PHONE_TOOLS["metric_series"])
    def metric_series(key: str, days: int = 14, granularity: str = "summary") -> str:
        return run("metric_series", {"key": key, "days": days, "granularity": granularity})

    @app.tool(description=PHONE_TOOLS["data_freshness"])
    def data_freshness() -> str:
        return run("data_freshness", {})

    @app.tool(description=PHONE_TOOLS["sleep_summary"])
    def sleep_summary(days: int = 7, granularity: str = "summary") -> str:
        return run("sleep_summary", {"days": days, "granularity": granularity})

    @app.tool(description=PHONE_TOOLS["workout_summary"])
    def workout_summary(days: int = 14, granularity: str = "summary") -> str:
        return run("workout_summary", {"days": days, "granularity": granularity})

    @app.tool(description=PHONE_TOOLS["open_screen"])
    def open_screen(screen: str) -> str:
        return run("open_screen", {"screen": screen})

    @app.tool(description=PHONE_TOOLS["show_coach_note"])
    def show_coach_note(title: str, body: str) -> str:
        return run("show_coach_note", {"title": title, "body": body})

    @app.tool(description="Render an ephemeral HTML coach dashboard from live NOOP summaries plus your advice text "
                          "(plain text, one suggestion per line, cite the numbers). Returns the local file path. Expires in 24 h.")
    def coach_dashboard(advice: str, days: int = 7) -> str:
        try:
            path = render_dashboard(gather(phone(), max(1, min(days, 31))), advice)
            receipt(tool="coach_dashboard", ok=True, path=str(path))
            return json.dumps({"isError": False, "data": {"path": str(path), "expiresHours": 24}})
        except PhoneError as e:
            state.pop("p", None)
            return json.dumps({"isError": True, "data": {"error": "phone_unavailable", "message": str(e)}})

    app.run()


def shape(obj, depth=0):
    """Structure without values, for logs and public evidence."""
    if isinstance(obj, dict):
        return {k: shape(v, depth + 1) for k, v in obj.items()}
    if isinstance(obj, list):
        return [f"list[{len(obj)}]"] + ([shape(obj[0], depth + 1)] if obj else [])
    return type(obj).__name__


def main(argv: list[str]) -> int:
    if not argv:
        serve(); return 0
    if argv[0] == "call":
        tool = argv[1]
        args = json.loads(argv[2]) if len(argv) > 2 and not argv[2].startswith("--") else {}
        r = Phone().call(tool, args)
        print(json.dumps(r if "--show" in argv else {"isError": r["isError"], "shape": shape(r["data"]),
                                                      "error": r["data"].get("error"), "privacy": r["data"].get("_privacy")}, indent=1))
        return 1 if r["isError"] else 0
    if argv[0] == "dashboard":
        days = int(argv[argv.index("--days") + 1]) if "--days" in argv else 7
        advice = Path(argv[argv.index("--advice-file") + 1]).read_text() if "--advice-file" in argv else "Advice goes here."
        data = DEMO if "--demo" in argv else gather(Phone(), days)
        print(render_dashboard(data, advice))
        return 0
    print(__doc__); return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
